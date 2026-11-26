package com.jay.animacapes.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jay.animacapes.cloud.CapeShareService;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles in-game client commands for owner whitelist, ban management, and authentication:
 *   /auth <key>
 *   /cape auth <key>
 *   /whitelist [add|remove|list] <player>
 *   /unwhitelist <player>
 *   /banlist
 *   /ban <player>
 *   /unban <player>
 *   /cape help
 *
 * Fully integrated with Brigadier CommandDispatcher for tab-completion,
 * auto-complete popups, and native syntax highlighting.
 */
public class ClientCommandHandler {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AnimatedCapes-CommandHandler");
        t.setDaemon(true);
        return t;
    });

    public static void init() {
        // 1. Register with Brigadier Command Dispatcher (Enables tab-completion, autocomplete popups, white text)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            registerBrigadierCommands(dispatcher);
        });

        // 2. Register fallback interceptor (Allows execution even on custom clients or typing variations)
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String raw = command.trim();
            if (raw.startsWith("/")) {
                raw = raw.substring(1).trim();
            }

            String lower = raw.toLowerCase();

            boolean isAuth = lower.startsWith("auth ") || lower.equals("auth") ||
                             lower.startsWith("cape auth ") || lower.startsWith("cape key ") ||
                             lower.startsWith("admin key ") || lower.startsWith("admin ") || lower.startsWith("cape admin ");
            boolean isWhitelist = lower.startsWith("whitelist") || lower.startsWith("cape whitelist") || lower.startsWith("ac whitelist");
            boolean isUnwhitelist = lower.startsWith("unwhitelist") || lower.startsWith("cape unwhitelist") || lower.startsWith("ac unwhitelist");
            boolean isBan = lower.startsWith("ban ") || lower.startsWith("cape ban ") || lower.startsWith("ac ban ");
            boolean isUnban = lower.startsWith("unban ") || lower.startsWith("cape unban ") || lower.startsWith("ac unban ");
            boolean isBanList = lower.equals("banlist") || lower.startsWith("banlist ") || lower.equals("cape banlist") || lower.startsWith("cape banlist ") || lower.equals("ac banlist") || lower.equals("cape bans");
            boolean isWear = lower.startsWith("wearcape ") || lower.startsWith("cape wear ") || lower.startsWith("cape copy ") || lower.startsWith("cape steal ") || lower.startsWith("ac wear ");
            boolean isReport = lower.startsWith("reportcape ") || lower.startsWith("cape report ") || lower.startsWith("ac report ");
            boolean isBlock = lower.startsWith("blockcape ") || lower.startsWith("cape block ") || lower.startsWith("ac block ");
            boolean isUnblock = lower.startsWith("unblockcape ") || lower.startsWith("cape unblock ") || lower.startsWith("ac unblock ");
            boolean isBlockList = lower.equals("blocklist") || lower.equals("cape blocklist") || lower.equals("cape blocks") || lower.equals("ac blocklist");
            boolean isHelp = lower.equals("cape") || lower.equals("cape help") || lower.equals("ac") || lower.equals("ac help");

            if (!isAuth && !isWhitelist && !isUnwhitelist && !isBan && !isUnban && !isBanList &&
                !isWear && !isReport && !isBlock && !isUnblock && !isBlockList && !isHelp) {
                return true;
            }

            String clean = raw;
            if (clean.toLowerCase().startsWith("cape ")) clean = clean.substring(5).trim();
            else if (clean.toLowerCase().startsWith("ac ")) clean = clean.substring(3).trim();

            if (isAuth) {
                String key = clean;
                if (clean.toLowerCase().startsWith("admin key ")) key = clean.substring(10).trim();
                else if (clean.toLowerCase().startsWith("admin ")) key = clean.substring(6).trim();
                else if (clean.toLowerCase().startsWith("auth ")) key = clean.substring(5).trim();
                else if (clean.toLowerCase().startsWith("key ")) key = clean.substring(4).trim();
                else if (raw.toLowerCase().startsWith("auth ")) key = raw.substring(5).trim();
                handleAuth(key);
                return false;
            }

            if (isHelp) {
                printHelp();
                return false;
            }

            if (isWear) {
                String target = "";
                if (clean.toLowerCase().startsWith("wear ")) target = clean.substring(5).trim();
                else if (clean.toLowerCase().startsWith("copy ")) target = clean.substring(5).trim();
                else if (clean.toLowerCase().startsWith("steal ")) target = clean.substring(6).trim();
                else if (raw.toLowerCase().startsWith("wearcape ")) target = raw.substring(9).trim();
                if (!target.isEmpty()) {
                    CapeShareService.wearPlayerCape(target, ClientCommandHandler::printFeedback);
                    return false;
                }
            }

            if (isReport) {
                String remainder = "";
                if (clean.toLowerCase().startsWith("report ")) remainder = clean.substring(7).trim();
                else if (raw.toLowerCase().startsWith("reportcape ")) remainder = raw.substring(11).trim();
                if (!remainder.isEmpty()) {
                    String[] repParts = remainder.split("\\s+", 2);
                    String target = repParts[0];
                    String reason = repParts.length > 1 ? repParts[1] : "Inappropriate cape";
                    CapeShareService.reportPlayer(target, reason, ClientCommandHandler::printFeedback);
                    return false;
                }
            }

            if (isBlock) {
                String target = "";
                if (clean.toLowerCase().startsWith("block ")) target = clean.substring(6).trim();
                else if (raw.toLowerCase().startsWith("blockcape ")) target = raw.substring(10).trim();
                if (!target.isEmpty()) {
                    CapeShareService.blockPlayer(target, ClientCommandHandler::printFeedback);
                    return false;
                }
            }

            if (isUnblock) {
                String target = "";
                if (clean.toLowerCase().startsWith("unblock ")) target = clean.substring(8).trim();
                else if (raw.toLowerCase().startsWith("unblockcape ")) target = raw.substring(12).trim();
                if (!target.isEmpty()) {
                    CapeShareService.unblockPlayer(target, ClientCommandHandler::printFeedback);
                    return false;
                }
            }

            if (isBlockList) {
                printBlockList();
                return false;
            }

            String[] parts = clean.split("\\s+");
            String action = parts[0].toLowerCase();

            if (isBanList || (action.equals("ban") && parts.length == 1)) {
                handleList("ban");
                return false;
            }

            if (action.equals("whitelist")) {
                if (parts.length == 1 || (parts.length == 2 && parts[1].equalsIgnoreCase("list"))) {
                    handleList("whitelist");
                    return false;
                }
                if (parts.length >= 3 && parts[1].equalsIgnoreCase("add")) {
                    handleModify("whitelist", "POST", parts[2]);
                    return false;
                }
                if (parts.length >= 3 && parts[1].equalsIgnoreCase("remove")) {
                    handleModify("whitelist", "DELETE", parts[2]);
                    return false;
                }
                if (parts.length == 2) {
                    handleModify("whitelist", "POST", parts[1]);
                    return false;
                }
            } else if (action.equals("unwhitelist") && parts.length >= 2) {
                handleModify("whitelist", "DELETE", parts[1]);
                return false;
            } else if (action.equals("ban") && parts.length >= 2) {
                handleModify("ban", "POST", parts[1]);
                return false;
            } else if (action.equals("unban") && parts.length >= 2) {
                handleModify("ban", "DELETE", parts[1]);
                return false;
            }

            printHelp();
            return false;
        });
    }

    private static void registerBrigadierCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        // /auth <key>
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("auth")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("key", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String key = StringArgumentType.getString(ctx, "key");
                        handleAuth(key);
                        return 1;
                    }))
        );

        // /cape
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("cape")
                .executes(ctx -> {
                    printHelp();
                    return 1;
                })
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("help")
                    .executes(ctx -> {
                        printHelp();
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("auth")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "key");
                            handleAuth(key);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("whitelist")
                    .executes(ctx -> {
                        handleList("whitelist");
                        return 1;
                    })
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                        .executes(ctx -> {
                            handleList("whitelist");
                            return 1;
                        }))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                            .executes(ctx -> {
                                String player = StringArgumentType.getString(ctx, "player");
                                handleModify("whitelist", "POST", player);
                                return 1;
                            })))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                            .executes(ctx -> {
                                String player = StringArgumentType.getString(ctx, "player");
                                handleModify("whitelist", "DELETE", player);
                                return 1;
                            }))))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unwhitelist")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            handleModify("whitelist", "DELETE", player);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("banlist")
                    .executes(ctx -> {
                        handleList("ban");
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("ban")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            handleModify("ban", "POST", player);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unban")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            handleModify("ban", "DELETE", player);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("wear")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            CapeShareService.wearPlayerCape(player, ClientCommandHandler::printFeedback);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("copy")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            CapeShareService.wearPlayerCape(player, ClientCommandHandler::printFeedback);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("report")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            CapeShareService.reportPlayer(player, "Inappropriate cape", ClientCommandHandler::printFeedback);
                            return 1;
                        })
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("reason", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String player = StringArgumentType.getString(ctx, "player");
                                String reason = StringArgumentType.getString(ctx, "reason");
                                CapeShareService.reportPlayer(player, reason, ClientCommandHandler::printFeedback);
                                return 1;
                            }))))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("block")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            CapeShareService.blockPlayer(player, ClientCommandHandler::printFeedback);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unblock")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            CapeShareService.unblockPlayer(player, ClientCommandHandler::printFeedback);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("blocklist")
                    .executes(ctx -> {
                        printBlockList();
                        return 1;
                    }))
        );

        // /whitelist
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("whitelist")
                .executes(ctx -> {
                    handleList("whitelist");
                    return 1;
                })
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                    .executes(ctx -> {
                        handleList("whitelist");
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            handleModify("whitelist", "POST", player);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            handleModify("whitelist", "DELETE", player);
                            return 1;
                        })))
        );

        // /unwhitelist <player>
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("unwhitelist")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        String player = StringArgumentType.getString(ctx, "player");
                        handleModify("whitelist", "DELETE", player);
                        return 1;
                    }))
        );

        // /ban <player>
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("ban")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        String player = StringArgumentType.getString(ctx, "player");
                        handleModify("ban", "POST", player);
                        return 1;
                    }))
        );

        // /unban <player>
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("unban")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        String player = StringArgumentType.getString(ctx, "player");
                        handleModify("ban", "DELETE", player);
                        return 1;
                    }))
        );

        // /banlist
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("banlist")
                .executes(ctx -> {
                    handleList("ban");
                    return 1;
                })
        );

        // /wearcape <player>
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("wearcape")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        String player = StringArgumentType.getString(ctx, "player");
                        CapeShareService.wearPlayerCape(player, ClientCommandHandler::printFeedback);
                        return 1;
                    }))
        );

        // /reportcape <player> [reason]
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("reportcape")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        String player = StringArgumentType.getString(ctx, "player");
                        CapeShareService.reportPlayer(player, "Inappropriate cape", ClientCommandHandler::printFeedback);
                        return 1;
                    })
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            String reason = StringArgumentType.getString(ctx, "reason");
                            CapeShareService.reportPlayer(player, reason, ClientCommandHandler::printFeedback);
                            return 1;
                        })))
        );

        // /blockcape <player>
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("blockcape")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        String player = StringArgumentType.getString(ctx, "player");
                        CapeShareService.blockPlayer(player, ClientCommandHandler::printFeedback);
                        return 1;
                    }))
        );

        // /unblockcape <player>
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("unblockcape")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        String player = StringArgumentType.getString(ctx, "player");
                        CapeShareService.unblockPlayer(player, ClientCommandHandler::printFeedback);
                        return 1;
                    }))
        );

        // /blocklist
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("blocklist")
                .executes(ctx -> {
                    printBlockList();
                    return 1;
                })
        );
    }

    public static void handleAuth(String key) {
        if (key == null || key.trim().isEmpty()) {
            printFeedback("§c[Animated Capes] Usage: /auth <secret_key> or /cape auth <secret_key>");
            return;
        }
        AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
        cfg.adminKey = key.trim();
        cfg.save();
        printFeedback("§a[Animated Capes] Admin key configured and saved successfully! You now have owner access.");
    }

    private static void handleModify(String endpointType, String httpMethod, String targetPlayer) {
        EXECUTOR.submit(() -> {
            AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
            String backend = cfg.backendUrl;
            if (backend == null || backend.isEmpty()) {
                printFeedback("§c[Animated Capes] Error: Backend URL not configured.");
                return;
            }
            if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

            String adminKey = cfg.adminKey;
            if (adminKey == null || adminKey.trim().isEmpty()) {
                printFeedback("§c[Animated Capes] Admin key required to modify! Type: §f/auth <your_secret_key>");
                return;
            }

            printFeedback("§7[Animated Capes] Contacting Cloudflare Edge for " + targetPlayer + "...");

            try {
                String url = backend + "/admin/" + endpointType;
                String jsonBody = "{\"user\":\"" + targetPlayer + "\"}";

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .header("Content-Type", "application/json")
                        .header("X-Admin-Secret", adminKey);

                if ("POST".equalsIgnoreCase(httpMethod)) {
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
                } else {
                    reqBuilder.method("DELETE", HttpRequest.BodyPublishers.ofString(jsonBody));
                }

                HttpResponse<String> resp = HTTP_CLIENT.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                    String resolvedName = obj.has("name") ? obj.get("name").getAsString() : targetPlayer;
                    String resolvedUuid = obj.has("uuid") ? obj.get("uuid").getAsString() : "";

                    if ("whitelist".equals(endpointType)) {
                        if ("POST".equals(httpMethod)) {
                            printFeedback("§a[Animated Capes] Successfully whitelisted §f" + resolvedName +
                                          (resolvedUuid.isEmpty() ? "" : " §7(" + resolvedUuid + ")") + " §afor Animated WebP!");
                        } else {
                            printFeedback("§e[Animated Capes] Successfully removed §f" + resolvedName + " §efrom Animated WebP whitelist.");
                        }
                    } else {
                        if ("POST".equals(httpMethod)) {
                            printFeedback("§c[Animated Capes] Successfully BANNED §f" + resolvedName +
                                          (resolvedUuid.isEmpty() ? "" : " §7(" + resolvedUuid + ")") + "§c! Cape purged globally.");
                        } else {
                            printFeedback("§a[Animated Capes] Successfully UNBANNED §f" + resolvedName + ".");
                        }
                    }
                } else if (resp.statusCode() == 401) {
                    printFeedback("§c[Animated Capes] Access Denied: Invalid admin key. Type /auth <key> to set the correct key.");
                } else {
                    printFeedback("§c[Animated Capes] Server returned HTTP " + resp.statusCode() + ": " + resp.body());
                }
            } catch (Exception e) {
                printFeedback("§c[Animated Capes] Failed to execute command: " + e.getMessage());
            }
        });
    }

    private static void handleList(String endpointType) {
        EXECUTOR.submit(() -> {
            AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
            String backend = cfg.backendUrl;
            if (backend == null || backend.isEmpty()) {
                printFeedback("§c[Animated Capes] Error: Backend URL not configured.");
                return;
            }
            if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

            String adminKey = cfg.adminKey;
            boolean hasKey = (adminKey != null && !adminKey.trim().isEmpty());

            try {
                String url = hasKey ? (backend + "/admin/" + endpointType) : (backend + "/" + (endpointType.equals("whitelist") ? "whitelist" : "banlist"));

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .GET();

                if (hasKey) {
                    reqBuilder.header("X-Admin-Secret", adminKey);
                }

                HttpResponse<String> resp = HTTP_CLIENT.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                    if ("whitelist".equals(endpointType)) {
                        JsonArray list = obj.has("combinedList") ? obj.getAsJsonArray("combinedList") : new JsonArray();
                        printFeedback("§b[Animated Capes] Animated WebP Whitelist (" + list.size() + " entries):");
                        if (list.size() == 0) {
                            printFeedback("  §7(No players whitelisted yet)");
                        } else {
                            for (JsonElement el : list) {
                                printFeedback("  §f• " + el.getAsString());
                            }
                        }
                    } else {
                        JsonArray list = obj.has("bannedList") ? obj.getAsJsonArray("bannedList") : new JsonArray();
                        printFeedback("§c[Animated Capes] Global Banlist (" + list.size() + " entries):");
                        if (list.size() == 0) {
                            printFeedback("  §7(No players banned)");
                        } else {
                            for (JsonElement el : list) {
                                printFeedback("  §f• " + el.getAsString());
                            }
                        }
                    }
                } else if (resp.statusCode() == 401) {
                    printFeedback("§c[Animated Capes] Access Denied: Invalid admin key. Type /auth <key> to re-authenticate.");
                } else {
                    printFeedback("§c[Animated Capes] HTTP " + resp.statusCode() + ": " + resp.body());
                }
            } catch (Exception e) {
                printFeedback("§c[Animated Capes] Failed to query list: " + e.getMessage());
            }
        });
    }

    public static void printBlockList() {
        java.util.List<String> list = AnimaCapesConfig.getInstance().getBlockedUsers();
        printFeedback("§e[Animated Capes] Blocked Users (" + list.size() + " entries):");
        if (list.isEmpty()) {
            printFeedback("  §7(No players blocked)");
        } else {
            for (String u : list) {
                printFeedback("  §f• " + u);
            }
        }
    }

    public static void printHelp() {
        printFeedback("§b=== Animated Capes Commands ===");
        printFeedback("§f/cape wear <player> §7- Search & equip another player's cape");
        printFeedback("§f/cape report <player> [reason] §7- Report inappropriate cape to moderation");
        printFeedback("§f/cape block <player> §7- Block a player so you never see their cape");
        printFeedback("§f/cape unblock <player> §7- Unblock a player");
        printFeedback("§f/cape blocklist §7- View your blocked players list");
        printFeedback("§f/auth <key> §7- Authenticate as the owner with your admin secret");
        printFeedback("§f/whitelist §7- View Animated WebP whitelist");
        printFeedback("§f/whitelist add <player> §7- Whitelist a player for Animated WebP");
        printFeedback("§f/unwhitelist <player> §7- Remove player from whitelist");
        printFeedback("§f/banlist §7- View banned players list");
        printFeedback("§f/ban <player> §7- Ban a player & purge their cape globally");
        printFeedback("§f/unban <player> §7- Unban a player");
    }

    public static void printFeedback(String msg) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal(msg));
                    }
                });
            } else {
                System.out.println(msg);
            }
        } catch (Throwable t) {
            System.out.println(msg);
        }
    }
}
