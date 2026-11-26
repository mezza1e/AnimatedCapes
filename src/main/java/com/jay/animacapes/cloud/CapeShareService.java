package com.jay.animacapes.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.jay.animacapes.engine.CapeAnimationEngine;
import com.jay.animacapes.gui.GalleryFolder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class CapeShareService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AnimatedCapes-ShareService");
        t.setDaemon(true);
        return t;
    });

    /**
     * Searches for a player's active cape, downloads it to the gallery, and equips it.
     */
    public static void wearPlayerCape(String targetPlayer, Consumer<String> callback) {
        if (targetPlayer == null || targetPlayer.trim().isEmpty()) {
            if (callback != null) callback.accept("§c[Animated Capes] Please provide a valid player name or UUID.");
            return;
        }

        final String cleanTarget = targetPlayer.trim();
        EXECUTOR.submit(() -> {
            try {
                AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
                String backend = cfg.backendUrl;
                if (backend == null || backend.trim().isEmpty()) {
                    if (callback != null) callback.accept("§c[Animated Capes] Backend URL is not configured.");
                    return;
                }
                if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

                if (callback != null) callback.accept("§7[Animated Capes] Searching for " + cleanTarget + "'s cape...");

                // 1. Query cape metadata: try /capes/by-name/<target> first
                String queryUrl = backend + "/capes/by-name/" + URLEncoder.encode(cleanTarget, StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(queryUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .GET()
                        .build();

                HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 404) {
                    // Try direct /capes/<target> in case it's a UUID
                    queryUrl = backend + "/capes/" + URLEncoder.encode(cleanTarget, StandardCharsets.UTF_8);
                    req = HttpRequest.newBuilder()
                            .uri(URI.create(queryUrl))
                            .timeout(Duration.ofSeconds(10))
                            .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                            .GET()
                            .build();
                    resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                }

                if (resp.statusCode() != 200) {
                    String err = "Player '" + cleanTarget + "' has no cape equipped or could not be found.";
                    try {
                        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                        if (obj.has("error")) err = obj.get("error").getAsString();
                    } catch (Throwable ignored) {}
                    if (callback != null) callback.accept("§c[Animated Capes] " + err);
                    return;
                }

                JsonObject data = JsonParser.parseString(resp.body()).getAsJsonObject();
                String fileUrl = data.has("url") ? data.get("url").getAsString() : null;
                String hash = data.has("hash") ? data.get("hash").getAsString() : null;
                String fileName = data.has("fileName") ? data.get("fileName").getAsString() : "cape.webp";
                String playerName = data.has("playerName") ? data.get("playerName").getAsString() : cleanTarget;

                if (fileUrl == null || fileUrl.isEmpty()) {
                    if (callback != null) callback.accept("§c[Animated Capes] Invalid cape data returned from server.");
                    return;
                }

                // 2. Download binary cape file
                if (callback != null) callback.accept("§7[Animated Capes] Downloading cape for " + playerName + "...");
                HttpRequest dlReq = HttpRequest.newBuilder()
                        .uri(URI.create(fileUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .GET()
                        .build();

                HttpResponse<byte[]> dlResp = HTTP_CLIENT.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());
                if (dlResp.statusCode() != 200 || dlResp.body() == null || dlResp.body().length == 0) {
                    if (callback != null) callback.accept("§c[Animated Capes] Failed to download cape file (HTTP " + dlResp.statusCode() + ").");
                    return;
                }

                byte[] capeBytes = dlResp.body();

                // 3. Save to local gallery folder
                File galleryDir = GalleryFolder.getFolder();
                String safeName = playerName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                String ext = ".webp";
                if (fileName.contains(".")) {
                    ext = fileName.substring(fileName.lastIndexOf('.'));
                }
                File destFile = new File(galleryDir, safeName + "_cape" + ext);

                Files.write(destFile.toPath(), capeBytes);

                // 4. Equip the cape locally and update config
                cfg.capeFilePath = destFile.getAbsolutePath();
                cfg.save();

                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        CapeAnimationEngine.getInstance().loadFromFile(destFile);
                    });
                }

                // 5. If cloud sync is enabled, sync equipped cape to server
                if (cfg.cloudSyncEnabled) {
                    CloudCapeSync.getInstance().syncCurrentCapeAsync(destFile);
                }

                if (callback != null) {
                    callback.accept("§a[Animated Capes] Successfully equipped " + playerName + "'s cape (" + destFile.getName() + ")!");
                }
            } catch (Exception e) {
                if (callback != null) {
                    callback.accept("§c[Animated Capes] Error wearing cape: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Reports a player for wearing an inappropriate cape.
     * The report is routed to the server and forwarded to Discord with cape preview embed.
     */
    public static void reportPlayer(String targetPlayer, String reason, Consumer<String> callback) {
        if (targetPlayer == null || targetPlayer.trim().isEmpty()) {
            if (callback != null) callback.accept("§c[Animated Capes] Usage: /cape report <player> [reason]");
            return;
        }

        final String cleanTarget = targetPlayer.trim();
        final String cleanReason = (reason == null || reason.trim().isEmpty()) ? "Inappropriate cape" : reason.trim();

        EXECUTOR.submit(() -> {
            try {
                AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
                String backend = cfg.backendUrl;
                if (backend == null || backend.trim().isEmpty()) {
                    if (callback != null) callback.accept("§c[Animated Capes] Backend URL is not configured.");
                    return;
                }
                if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

                String reporterName = "Anonymous";
                String reporterUuid = "";
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.getUser() != null) {
                        reporterName = mc.getUser().getName();
                        if (mc.getUser().getProfileId() != null) {
                            reporterUuid = mc.getUser().getProfileId().toString();
                        }
                    }
                } catch (Throwable ignored) {}

                JsonObject body = new JsonObject();
                body.addProperty("reportedUser", cleanTarget);
                body.addProperty("reporterName", reporterName);
                body.addProperty("reporterUuid", reporterUuid);
                body.addProperty("reason", cleanReason);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(backend + "/report"))
                        .timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();

                HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonObject res = JsonParser.parseString(resp.body()).getAsJsonObject();
                    String reported = res.has("reportedUser") ? res.get("reportedUser").getAsString() : cleanTarget;
                    boolean capeEquipped = res.has("capeEquipped") && res.get("capeEquipped").getAsBoolean();

                    StringBuilder sb = new StringBuilder();
                    sb.append("§a[Animated Capes] Report successfully submitted for §f").append(reported).append("§a.");
                    if (capeEquipped) {
                        sb.append(" Active cape captured and forwarded for moderation review.");
                    } else {
                        sb.append(" (Player has no active cape equipped).");
                    }
                    if (callback != null) callback.accept(sb.toString());
                } else {
                    String err = "HTTP " + resp.statusCode();
                    try {
                        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                        if (obj.has("error")) err = obj.get("error").getAsString();
                    } catch (Throwable ignored) {}
                    if (callback != null) callback.accept("§c[Animated Capes] Failed to report player: " + err);
                }
            } catch (Exception e) {
                if (callback != null) {
                    callback.accept("§c[Animated Capes] Failed to send report: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Blocks a player, preventing the user from seeing their cape.
     */
    public static void blockPlayer(String targetPlayer, Consumer<String> callback) {
        if (targetPlayer == null || targetPlayer.trim().isEmpty()) {
            if (callback != null) callback.accept("§c[Animated Capes] Usage: /cape block <player>");
            return;
        }

        String clean = targetPlayer.trim();
        boolean added = AnimaCapesConfig.getInstance().blockUser(clean);
        RemoteCapeManager.getInstance().purgePlayer(clean);

        if (added) {
            if (callback != null) callback.accept("§e[Animated Capes] Blocked §f" + clean + "§e. You will no longer see their cape.");
        } else {
            if (callback != null) callback.accept("§e[Animated Capes] Player §f" + clean + "§e is already in your blocklist.");
        }
    }

    /**
     * Unblocks a player.
     */
    public static void unblockPlayer(String targetPlayer, Consumer<String> callback) {
        if (targetPlayer == null || targetPlayer.trim().isEmpty()) {
            if (callback != null) callback.accept("§c[Animated Capes] Usage: /cape unblock <player>");
            return;
        }

        String clean = targetPlayer.trim();
        boolean removed = AnimaCapesConfig.getInstance().unblockUser(clean);

        if (removed) {
            if (callback != null) callback.accept("§a[Animated Capes] Unblocked §f" + clean + "§a.");
        } else {
            if (callback != null) callback.accept("§c[Animated Capes] Player §f" + clean + "§c was not in your blocklist.");
        }
    }
}
