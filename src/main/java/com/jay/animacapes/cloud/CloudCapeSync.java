package com.jay.animacapes.cloud;

import com.jay.animacapes.config.AnimaCapesConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CloudCapeSync {
    private static final CloudCapeSync INSTANCE = new CloudCapeSync();

    public enum Status {
        IDLE,
        UPLOADING,
        SYNCED,
        LOCAL_ONLY,
        ERROR
    }

    private volatile Status status = Status.IDLE;
    private volatile String lastSyncedHash = "";
    private volatile String errorMessage = "";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AnimatedCapes-CloudSync");
        t.setDaemon(true);
        return t;
    });

    public static CloudCapeSync getInstance() {
        return INSTANCE;
    }

    public Status getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return (errorMessage != null && !errorMessage.isEmpty()) ? errorMessage : "Connection failed";
    }

    public static String computeFileSha256(File file) {
        try (InputStream is = Files.newInputStream(file.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public void syncCurrentCapeAsync(File capeFile) {
        if (!AnimaCapesConfig.getInstance().cloudSyncEnabled) {
            this.status = Status.IDLE;
            return;
        }

        if (capeFile == null || !capeFile.exists() || !capeFile.isFile()) {
            deleteCurrentCapeAsync();
            return;
        }

        if (!com.jay.animacapes.codec.AnimationDecoder.isSupportedFormat(capeFile.getName())) {
            this.status = Status.ERROR;
            this.errorMessage = "Only .webp, .gif, and static .png/.jpg/.jpeg files are supported";
            System.err.println("[AnimatedCapes] " + this.errorMessage);
            return;
        }

        String lower = capeFile.getName().toLowerCase();
        if (lower.endsWith(".png") && com.jay.animacapes.codec.AnimationDecoder.isApng(capeFile)) {
            this.status = Status.ERROR;
            this.errorMessage = "Animated PNG (APNG) is not permitted. Only static PNGs are allowed.";
            System.err.println("[AnimatedCapes] " + this.errorMessage);
            return;
        }

        long maxBytes = com.jay.animacapes.codec.AnimationDecoder.getMaxAllowedBytes(capeFile.getName());
        if (capeFile.length() > maxBytes) {
            this.status = Status.ERROR;
            this.errorMessage = "File exceeds " + (maxBytes / (1024 * 1024)) + "MB limit";
            System.err.println("[AnimatedCapes] " + this.errorMessage);
            return;
        }

        this.status = Status.UPLOADING;
        this.errorMessage = "";

        executor.submit(() -> {
            try {
                String fileHash = computeFileSha256(capeFile);
                if (fileHash == null || fileHash.isEmpty()) {
                    this.status = Status.ERROR;
                    this.errorMessage = "Failed to hash file";
                    return;
                }

                if (fileHash.equalsIgnoreCase(lastSyncedHash) && status == Status.SYNCED) {
                    return; // Already in sync
                }

                Minecraft mc = Minecraft.getInstance();
                User user = (mc != null) ? mc.getUser() : null;
                UUID uuid = (user != null) ? user.getProfileId() : null;
                String username = (user != null) ? user.getName() : "Player";

                if (uuid == null) {
                    this.status = Status.ERROR;
                    this.errorMessage = "No valid player session";
                    return;
                }

                String baseBackend = AnimaCapesConfig.getInstance().backendUrl;
                if (baseBackend == null || baseBackend.trim().isEmpty()) {
                    this.status = Status.ERROR;
                    this.errorMessage = "Backend URL not configured";
                    return;
                }
                if (baseBackend.endsWith("/")) {
                    baseBackend = baseBackend.substring(0, baseBackend.length() - 1);
                }

                File fileToUpload = capeFile;
                File originalGif = null;
                String lowerName = fileToUpload.getName().toLowerCase();
                if (lowerName.endsWith(".gif")) {
                    originalGif = fileToUpload;
                    File opt = com.jay.animacapes.codec.WebpOptimizer.optimizeGifToWebp(fileToUpload, com.jay.animacapes.gui.GalleryFolder.getFolder());
                    if (opt != null && opt.exists() && opt.length() > 0 && opt.length() < fileToUpload.length()) {
                        fileToUpload = opt;
                        com.jay.animacapes.config.AnimaCapesConfig.getInstance().capeFilePath = opt.getAbsolutePath();
                        com.jay.animacapes.config.AnimaCapesConfig.getInstance().save();
                    }
                } else if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
                    File opt = com.jay.animacapes.codec.WebpOptimizer.optimizeStaticImageToLosslessWebp(fileToUpload, com.jay.animacapes.gui.GalleryFolder.getFolder());
                    if (opt != null && opt.exists() && opt.length() > 0 && opt.length() < fileToUpload.length()) {
                        fileToUpload = opt;
                        com.jay.animacapes.config.AnimaCapesConfig.getInstance().capeFilePath = opt.getAbsolutePath();
                        com.jay.animacapes.config.AnimaCapesConfig.getInstance().save();
                    }
                }

                String actualHash = computeFileSha256(fileToUpload);
                byte[] fileBytes = Files.readAllBytes(fileToUpload.toPath());
                String endpoint = baseBackend + "/capes/" + uuid.toString();

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "AnimatedCapes-MinecraftMod/1.0.0 (Fabric)")
                        .header("Content-Type", "application/octet-stream")
                        .header("X-Player-UUID", uuid.toString())
                        .header("X-Player-Name", username)
                        .header("X-Cape-Hash", actualHash)
                        .header("X-File-Name", fileToUpload.getName())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(fileBytes));

                String adminKey = AnimaCapesConfig.getInstance().adminKey;
                if (adminKey != null && !adminKey.trim().isEmpty()) {
                    reqBuilder.header("X-Admin-Key", adminKey.trim());
                    reqBuilder.header("Authorization", "Bearer " + adminKey.trim());
                }

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    this.lastSyncedHash = actualHash;
                    this.status = Status.SYNCED;
                    System.out.println("[AnimatedCapes] Cape synced to cloud successfully for " + username + " (" + uuid + ")");
                } else if (resp.statusCode() == 403 && originalGif != null && originalGif.exists()) {
                    // Fallback to uploading raw GIF directly since server whitelists GIF for all users
                    System.out.println("[AnimatedCapes] WebP upload returned 403 (whitelist required). Falling back to GIF upload...");
                    byte[] gifBytes = Files.readAllBytes(originalGif.toPath());
                    String gifHash = computeFileSha256(originalGif);
                    HttpRequest.Builder gifReqBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(15))
                            .header("User-Agent", "AnimatedCapes-MinecraftMod/1.0.0 (Fabric)")
                            .header("Content-Type", "application/octet-stream")
                            .header("X-Player-UUID", uuid.toString())
                            .header("X-Player-Name", username)
                            .header("X-Cape-Hash", gifHash)
                            .header("X-File-Name", originalGif.getName())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(gifBytes));

                    if (adminKey != null && !adminKey.trim().isEmpty()) {
                        gifReqBuilder.header("X-Admin-Key", adminKey.trim());
                        gifReqBuilder.header("Authorization", "Bearer " + adminKey.trim());
                    }

                    HttpResponse<String> gifResp = httpClient.send(gifReqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                    if (gifResp.statusCode() >= 200 && gifResp.statusCode() < 300) {
                        this.lastSyncedHash = gifHash;
                        this.status = Status.SYNCED;
                        System.out.println("[AnimatedCapes] GIF cape synced to cloud successfully for " + username + " (" + uuid + ")");
                    } else if (gifResp.statusCode() == 403) {
                        this.status = Status.LOCAL_ONLY;
                        this.errorMessage = "Local Only (Cloud Whitelist)";
                        System.out.println("[AnimatedCapes] Cape is active locally. Cloud sync restricted.");
                    } else {
                        this.status = Status.ERROR;
                        this.errorMessage = "HTTP " + gifResp.statusCode();
                        System.err.println("[AnimatedCapes] Cloud sync GIF upload failed: " + this.errorMessage);
                    }
                } else if (resp.statusCode() == 403) {
                    this.status = Status.LOCAL_ONLY;
                    this.errorMessage = "Local Only (Cloud Whitelist)";
                    System.out.println("[AnimatedCapes] Direct WebP cloud upload requires whitelist. Cape is fully active locally.");
                } else {
                    this.status = Status.ERROR;
                    this.errorMessage = "HTTP " + resp.statusCode();
                    System.err.println("[AnimatedCapes] Cloud sync upload failed: HTTP " + resp.statusCode() + ": " + resp.body());
                }
            } catch (Exception e) {
                this.status = Status.ERROR;
                String msg = e.getMessage();
                this.errorMessage = (msg != null && !msg.isEmpty()) ? msg : e.getClass().getSimpleName();
                System.err.println("[AnimatedCapes] Cloud sync exception: " + this.errorMessage);
            }
        });
    }

    public void deleteCurrentCapeAsync() {
        if (!AnimaCapesConfig.getInstance().cloudSyncEnabled) return;

        executor.submit(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                User user = (mc != null) ? mc.getUser() : null;
                UUID uuid = (user != null) ? user.getProfileId() : null;
                if (uuid == null) return;

                String baseBackend = AnimaCapesConfig.getInstance().backendUrl;
                if (baseBackend == null || baseBackend.trim().isEmpty()) return;
                if (baseBackend.endsWith("/")) baseBackend = baseBackend.substring(0, baseBackend.length() - 1);

                String endpoint = baseBackend + "/capes/" + uuid.toString();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "AnimatedCapes-MinecraftMod/1.0.0 (Fabric)")
                        .DELETE()
                        .build();

                httpClient.send(req, HttpResponse.BodyHandlers.discarding());
                this.lastSyncedHash = "";
                this.status = Status.IDLE;
                System.out.println("[AnimatedCapes] Cape removed from cloud for " + uuid);
            } catch (Exception ignored) {}
        });
    }
}
