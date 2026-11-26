package com.jay.animacapes.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jay.animacapes.codec.AnimationDecoder;
import com.jay.animacapes.codec.DecodedAnimation;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.jay.animacapes.gui.GalleryFolder;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoteCapeManager {
    private static final RemoteCapeManager INSTANCE = new RemoteCapeManager();

    public static final int MAX_ACTIVE_REMOTE_INSTANCES = 16; // Hard cap: max 128MB VRAM for all remote capes combined

    // UUID -> Hash of player's cape
    private final Map<UUID, String> playerCapeHashes = new ConcurrentHashMap<>();

    // Hash -> Shared cape animation instance (deduplicates VRAM across players!)
    private final Map<String, RemotePlayerCapeInstance> sharedInstances = new ConcurrentHashMap<>();

    // In-flight queries to prevent duplicate HTTP requests
    private final Set<UUID> pendingQueries = ConcurrentHashMap.newKeySet();

    // Negative cache: UUID -> timestamp when query returned 404 (don't query again for 2 mins)
    private final Map<UUID, Long> negativeCache = new ConcurrentHashMap<>();
    private static final long NEGATIVE_TTL_MS = 120_000L; // 2 minutes

    // Timestamp when player's cape was last queried (refresh after 5 minutes)
    private final Map<UUID, Long> lastQueryTime = new ConcurrentHashMap<>();
    private static final long REFRESH_TTL_MS = 300_000L; // 5 minutes

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "AnimatedCapes-RemoteLoader");
        t.setDaemon(true);
        return t;
    });

    public static RemoteCapeManager getInstance() {
        return INSTANCE;
    }

    private File getCacheDir() {
        File folder = new File(GalleryFolder.getFolder(), "cache");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    /**
     * Called during Avatar rendering for other players. Returns the active animated
     * cape texture identifier for this player, or null if none/not yet loaded.
     */
    public Identifier getOrQueryCape(UUID playerUuid) {
        if (!AnimaCapesConfig.getInstance().cloudSyncEnabled || !AnimaCapesConfig.getInstance().showOtherPlayerCapes || playerUuid == null) {
            return null;
        }

        if (AnimaCapesConfig.getInstance().isBlocked(playerUuid.toString())) {
            return null;
        }

        String hash = playerCapeHashes.get(playerUuid);
        if (hash != null) {
            RemotePlayerCapeInstance inst = sharedInstances.get(hash);
            if (inst != null) {
                return inst.getActiveCapeIdentifier();
            }
        }

        // Check if query should be dispatched
        long now = System.currentTimeMillis();
        Long negTime = negativeCache.get(playerUuid);
        if (negTime != null && (now - negTime < NEGATIVE_TTL_MS)) {
            return null; // Negative cached
        }

        Long lastQ = lastQueryTime.get(playerUuid);
        if (lastQ == null || (now - lastQ > REFRESH_TTL_MS)) {
            queryPlayerCapeAsync(playerUuid);
        }

        return null;
    }

    private void queryPlayerCapeAsync(UUID uuid) {
        if (!pendingQueries.add(uuid)) {
            return;
        }

        worker.submit(() -> {
            try {
                String baseBackend = AnimaCapesConfig.getInstance().backendUrl;
                if (baseBackend == null || baseBackend.trim().isEmpty()) {
                    pendingQueries.remove(uuid);
                    return;
                }
                if (baseBackend.endsWith("/")) baseBackend = baseBackend.substring(0, baseBackend.length() - 1);

                String endpoint = baseBackend + "/capes/" + uuid.toString();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(6))
                        .header("User-Agent", "AnimatedCapes-MinecraftMod/1.0.0 (Fabric)")
                        .GET()
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                lastQueryTime.put(uuid, System.currentTimeMillis());

                if (resp.statusCode() == 404) {
                    negativeCache.put(uuid, System.currentTimeMillis());
                    playerCapeHashes.remove(uuid);
                    return;
                }

                if (resp.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                    String hash = json.has("hash") ? json.get("hash").getAsString() : "";
                    String fileUrl = json.has("url") ? json.get("url").getAsString() : "";

                    if (!hash.isEmpty()) {
                        negativeCache.remove(uuid);
                        playerCapeHashes.put(uuid, hash);

                        // If shared instance already exists for this hash, we're done!
                        if (!sharedInstances.containsKey(hash)) {
                            loadOrDownloadCape(hash, fileUrl, baseBackend);
                        }
                    }
                }
            } catch (Exception e) {
                // Network failure: silent fallback
            } finally {
                pendingQueries.remove(uuid);
            }
        });
    }

    private void loadOrDownloadCape(String hash, String fileUrl, String baseBackend) {
        File cacheDir = getCacheDir();
        // Check if file already exists in disk cache
        File cachedFile = null;
        for (String ext : new String[]{".webp", ".gif", ".png", ".jpg", ".jpeg"}) {
            File test = new File(cacheDir, hash + ext);
            if (test.exists() && test.length() > 0) {
                cachedFile = test;
                break;
            }
        }

        // If not on disk, download it
        if (cachedFile == null) {
            String downloadUrl = fileUrl;
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                downloadUrl = baseBackend + "/capes/file/" + hash;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "AnimatedCapes-MinecraftMod/1.0.0 (Fabric)")
                        .GET()
                        .build();

                File tempFile = new File(cacheDir, hash + ".tmp");
                HttpResponse<java.nio.file.Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(tempFile.toPath()));
                if (resp.statusCode() == 200) {
                    String ext = ".webp";
                    String contentType = resp.headers().firstValue("Content-Type").orElse("");
                    if (contentType.contains("gif")) ext = ".gif";
                    else if (contentType.contains("png")) ext = ".png";
                    else if (contentType.contains("jpeg") || contentType.contains("jpg")) ext = ".jpg";

                    File finalFile = new File(cacheDir, hash + ext);
                    Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    cachedFile = finalFile;
                } else {
                    tempFile.delete();
                    return;
                }
            } catch (Exception e) {
                return;
            }
        }

        // Decode animation and register shared instance on render thread
        if (cachedFile != null && cachedFile.exists()) {
            final File toDecode = cachedFile;
            try {
                DecodedAnimation anim = AnimationDecoder.decodeRemote(toDecode);
                if (anim != null && anim.getFrameCount() > 0) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) {
                        mc.execute(() -> {
                            // Enforce strict VRAM budget cap before allocating a new texture
                            ensureCapacityLimit();

                            RemotePlayerCapeInstance inst = new RemotePlayerCapeInstance(hash, anim);
                            sharedInstances.put(hash, inst);
                            System.out.println("[AnimatedCapes] Registered remote animated cape: " + hash.substring(0, 8) + " (" + anim.getFrameCount() + " frames)");
                        });
                    }
                }
            } catch (Exception e) {
                System.err.println("[AnimatedCapes] Failed to decode remote cape: " + e.getMessage());
            }
        }
    }

    private synchronized void ensureCapacityLimit() {
        if (sharedInstances.size() >= MAX_ACTIVE_REMOTE_INSTANCES) {
            // Find and evict the least recently rendered instance
            sharedInstances.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().getLastRenderTimeMs()))
                    .ifPresent(oldest -> {
                        oldest.getValue().close();
                        sharedInstances.remove(oldest.getKey());
                    });
        }
    }

    /**
     * Purges cached instances that haven't been rendered in >60 seconds to conserve GPU VRAM.
     */
    public void cleanupUnusedTextures() {
        long now = System.currentTimeMillis();
        sharedInstances.entrySet().removeIf(entry -> {
            RemotePlayerCapeInstance inst = entry.getValue();
            if (inst != null && (now - inst.getLastRenderTimeMs() > 60_000L)) {
                inst.close();
                return true;
            }
            return false;
        });
    }

    public int getActiveInstanceCount() {
        return sharedInstances.size();
    }

    public void purgePlayer(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.trim().isEmpty()) return;
        String clean = nameOrUuid.trim();
        try {
            UUID u = UUID.fromString(clean);
            playerCapeHashes.remove(u);
            pendingQueries.remove(u);
            negativeCache.remove(u);
            lastQueryTime.remove(u);
            return;
        } catch (IllegalArgumentException ignored) {}

        // Clear all entries matching any blocked user
        purgeAllBlocked();
    }

    public void purgeAllBlocked() {
        AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
        for (UUID u : playerCapeHashes.keySet()) {
            if (cfg.isBlocked(u.toString())) {
                playerCapeHashes.remove(u);
                pendingQueries.remove(u);
                negativeCache.remove(u);
                lastQueryTime.remove(u);
            }
        }
    }

    public void clearAll() {
        for (RemotePlayerCapeInstance inst : sharedInstances.values()) {
            if (inst != null) inst.close();
        }
        sharedInstances.clear();
        playerCapeHashes.clear();
        pendingQueries.clear();
        negativeCache.clear();
        lastQueryTime.clear();
    }
}
