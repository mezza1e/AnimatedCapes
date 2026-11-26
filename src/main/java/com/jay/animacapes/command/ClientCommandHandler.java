package com.jay.animacapes.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jay.animacapes.cloud.CapeShareService;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.jay.animacapes.gui.AnimaCapesScreen;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles in-game client commands for players and administrators:
 *
 * General Player Commands:
 *   /cape menu                       - Open GUI
 *   /cape wear <player>              - Copy & equip cape (subject to permissions)
 *   /cape lock                       - Lock your cape from being copied (default)
 *   /cape unlock                     - Unlock your cape for anyone to copy
 *   /friend add <player>             - Add friend (can copy cape automatically)
 *   /friend remove <player>          - Remove friend
 *   /friend list                     - View friends list
 *   /cape report <player> [reason]   - Report an inappropriate cape to moderation
 *   /cape block <player>             - Block player from viewing cape
 *   /cape unblock <player>           - Unblock player
 *   /cape blocklist                  - View blocked players list
 *   /whitelist                       - View Animated WebP whitelist (Owner & Whitelisted only)
 *   /auth <key>                      - Authenticate yourself
 *   /cape help                       - Show general commands
 *
 * Administrator Commands (/admin):
 *   /admin auth <key>                - Authenticate as administrator
 *   /admin lookup <player>           - Look up player cape & status
 *   /admin purge <player>            - Purge active cape globally from cloud
 *   /admin ban <player> [reason]     - Ban a player (preserves cape in DB)
 *   /admin unban <player>            - Unban a player
 *   /admin banlist                   - View banned players list
 *   /admin whitelist add <player>    - Add a player to the whitelist
 *   /admin whitelist remove <player> - Remove a player from the whitelist
 *   /admin status                    - View backend cloud status
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
        // 1. Register with Brigadier Command Dispatcher
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            registerBrigadierCommands(dispatcher);
        });

        // 2. Register fallback interceptor
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String raw = command.trim();
            if (raw.startsWith("/")) {
                raw = raw.substring(1).trim();
            }

            String lower = raw.toLowerCase();

            // Menu
            if (lower.equals("cape menu") || lower.equals("ac menu") || lower.equals("menu")) {
                openMenu();
                return false;
            }

            // Lock / Unlock
            if (lower.equals("cape lock") || lower.equals("ac lock") || lower.equals("capelock")) {
                handleLock(true);
                return false;
            }
            if (lower.equals("cape unlock") || lower.equals("ac unlock") || lower.equals("capeunlock")) {
                handleLock(false);
                return false;
            }

            // Friend commands
            if (lower.startsWith("friend ") || lower.equals("friend") ||
                lower.startsWith("cape friend ") || lower.equals("cape friend") ||
                lower.startsWith("ac friend ") || lower.equals("ac friend")) {
                handleFriendRaw(raw);
                return false;
            }

            // Admin commands
            if (lower.startsWith("admin ") || lower.equals("admin") ||
                lower.startsWith("cape admin ") || lower.equals("cape admin") ||
                lower.startsWith("ac admin ") || lower.equals("ac admin")) {
                handleAdminRaw(raw);
                return false;
            }

            // Auth commands
            boolean isAuth = lower.startsWith("auth ") || lower.equals("auth") ||
                             lower.startsWith("cape auth ") || lower.startsWith("cape key ");
            if (isAuth) {
                String key = raw;
                if (lower.startsWith("cape auth ")) key = raw.substring(10).trim();
                else if (lower.startsWith("cape key ")) key = raw.substring(9).trim();
                else if (lower.startsWith("auth ")) key = raw.substring(5).trim();
                handleAuth(key);
                return false;
            }

            // Whitelist commands
            if (lower.startsWith("whitelist") || lower.startsWith("cape whitelist") || lower.startsWith("ac whitelist")) {
                handleWhitelistRaw(raw);
                return false;
            }
            if (lower.startsWith("unwhitelist") || lower.startsWith("cape unwhitelist") || lower.startsWith("ac unwhitelist")) {
                handleUnwhitelistRaw(raw);
                return false;
            }

            // Ban commands
            if (lower.startsWith("ban ") || lower.startsWith("cape ban ") || lower.startsWith("ac ban ")) {
                if (!AnimaCapesConfig.getInstance().isAdmin()) {
                    printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required. Use /admin auth <key>.");
                    return false;
                }
                String target = raw;
                if (lower.startsWith("cape ban ")) target = raw.substring(9).trim();
                else if (lower.startsWith("ac ban ")) target = raw.substring(7).trim();
                else if (lower.startsWith("ban ")) target = raw.substring(4).trim();
                handleAdminBan(target, "Inappropriate behavior");
                return false;
            }
            if (lower.startsWith("unban ") || lower.startsWith("cape unban ") || lower.startsWith("ac unban ")) {
                if (!AnimaCapesConfig.getInstance().isAdmin()) {
                    printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required. Use /admin auth <key>.");
                    return false;
                }
                String target = raw;
                if (lower.startsWith("cape unban ")) target = raw.substring(11).trim();
                else if (lower.startsWith("ac unban ")) target = raw.substring(9).trim();
                else if (lower.startsWith("unban ")) target = raw.substring(6).trim();
                handleModify("ban", "DELETE", target);
                return false;
            }
            if (lower.equals("banlist") || lower.equals("cape banlist") || lower.equals("ac banlist")) {
                if (!AnimaCapesConfig.getInstance().isAdmin()) {
                    printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required to view the banlist.");
                    return false;
                }
                handleList("ban");
                return false;
            }

            // Wear / Copy
            boolean isWear = lower.startsWith("wearcape ") || lower.startsWith("cape wear ") || lower.startsWith("cape copy ") || lower.startsWith("ac wear ");
            if (isWear) {
                String target = "";
                if (lower.startsWith("cape wear ")) target = raw.substring(10).trim();
                else if (lower.startsWith("cape copy ")) target = raw.substring(10).trim();
                else if (lower.startsWith("ac wear ")) target = raw.substring(8).trim();
                else if (lower.startsWith("wearcape ")) target = raw.substring(9).trim();
                if (!target.isEmpty()) {
                    CapeShareService.wearPlayerCape(target, ClientCommandHandler::printFeedback);
                    return false;
                }
            }

            // Report
            boolean isReport = lower.startsWith("reportcape ") || lower.startsWith("cape report ") || lower.startsWith("ac report ");
            if (isReport) {
                String rem = "";
                if (lower.startsWith("cape report ")) rem = raw.substring(12).trim();
                else if (lower.startsWith("ac report ")) rem = raw.substring(10).trim();
                else if (lower.startsWith("reportcape ")) rem = raw.substring(11).trim();
                if (!rem.isEmpty()) {
                    String[] repParts = rem.split("\\s+", 2);
                    String target = repParts[0];
                    String reason = repParts.length > 1 ? repParts[1] : "Inappropriate cape";
                    CapeShareService.reportPlayer(target, reason, ClientCommandHandler::printFeedback);
                    return false;
                }
            }

            // Block / Unblock / Blocklist
            if (lower.startsWith("blockcape ") || lower.startsWith("cape block ") || lower.startsWith("ac block ")) {
                String target = "";
                if (lower.startsWith("cape block ")) target = raw.substring(11).trim();
                else if (lower.startsWith("ac block ")) target = raw.substring(9).trim();
                else if (lower.startsWith("blockcape ")) target = raw.substring(10).trim();
                if (!target.isEmpty()) {
                    CapeShareService.blockPlayer(target, ClientCommandHandler::printFeedback);
                    return false;
                }
            }
            if (lower.startsWith("unblockcape ") || lower.startsWith("cape unblock ") || lower.startsWith("ac unblock ")) {
                String target = "";
                if (lower.startsWith("cape unblock ")) target = raw.substring(13).trim();
                else if (lower.startsWith("ac unblock ")) target = raw.substring(11).trim();
                else if (lower.startsWith("unblockcape ")) target = raw.substring(12).trim();
                if (!target.isEmpty()) {
                    CapeShareService.unblockPlayer(target, ClientCommandHandler::printFeedback);
                    return false;
                }
            }
            if (lower.equals("blocklist") || lower.equals("cape blocklist") || lower.equals("ac blocklist")) {
                printBlockList();
                return false;
            }

            // Help
            if (lower.equals("cape") || lower.equals("cape help") || lower.equals("ac") || lower.equals("ac help")) {
                printHelp();
                return false;
            }

            return true;
        });
    }

    public static void openMenu() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    mc.setScreenAndShow(new AnimaCapesScreen(null));
                });
            }
        } catch (Throwable t) {
            printFeedback("§c[Animated Capes] Failed to open menu: " + t.getMessage());
        }
    }

    public static void handleLock(boolean lock) {
        AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
        cfg.allowCapeCopy = !lock;
        cfg.save();
        if (lock) {
            printFeedback("§e[Animated Capes] Cape copying locked. Non-friends cannot copy your cape (Default).");
        } else {
            printFeedback("§a[Animated Capes] Cape copying unlocked. Any player can now copy and wear your cape.");
        }
    }

    private static void handleFriendRaw(String raw) {
        String clean = raw.trim();
        if (clean.toLowerCase().startsWith("cape friend ")) clean = clean.substring(12).trim();
        else if (clean.toLowerCase().startsWith("ac friend ")) clean = clean.substring(10).trim();
        else if (clean.toLowerCase().startsWith("friend ")) clean = clean.substring(7).trim();
        else if (clean.equalsIgnoreCase("friend") || clean.equalsIgnoreCase("cape friend") || clean.equalsIgnoreCase("ac friend")) {
            printFriendsList();
            return;
        }

        String[] parts = clean.split("\\s+", 2);
        String action = parts[0].toLowerCase();
        if (action.equals("list")) {
            printFriendsList();
        } else if (action.equals("add") && parts.length > 1) {
            handleAddFriend(parts[1].trim());
        } else if (action.equals("remove") && parts.length > 1) {
            handleRemoveFriend(parts[1].trim());
        } else {
            printFeedback("§c[Animated Capes] Usage: /friend <add|remove|list> [player]");
        }
    }

    public static void handleAddFriend(String player) {
        if (player == null || player.trim().isEmpty()) {
            printFeedback("§c[Animated Capes] Usage: /friend add <player>");
            return;
        }
        boolean added = AnimaCapesConfig.getInstance().addFriend(player.trim());
        if (added) {
            printFeedback("§a[Animated Capes] Added §f" + player.trim() + "§a to your friends list! They can now automatically copy your cape.");
        } else {
            printFeedback("§e[Animated Capes] Player §f" + player.trim() + "§e is already on your friends list.");
        }
    }

    public static void handleRemoveFriend(String player) {
        if (player == null || player.trim().isEmpty()) {
            printFeedback("§c[Animated Capes] Usage: /friend remove <player>");
            return;
        }
        boolean removed = AnimaCapesConfig.getInstance().removeFriend(player.trim());
        if (removed) {
            printFeedback("§a[Animated Capes] Removed §f" + player.trim() + "§a from your friends list.");
        } else {
            printFeedback("§c[Animated Capes] Player §f" + player.trim() + "§c was not on your friends list.");
        }
    }

    public static void printFriendsList() {
        java.util.List<String> friends = AnimaCapesConfig.getInstance().getFriends();
        printFeedback("§b[Animated Capes] Friends List (" + friends.size() + " friends):");
        if (friends.isEmpty()) {
            printFeedback("  §7(No friends added yet. Use /friend add <player> to add friends!)");
        } else {
            for (String f : friends) {
                printFeedback("  §f• " + f);
            }
        }
    }

    private static void handleAdminRaw(String raw) {
        String clean = raw.trim();
        if (clean.toLowerCase().startsWith("cape admin ")) clean = clean.substring(11).trim();
        else if (clean.toLowerCase().startsWith("ac admin ")) clean = clean.substring(9).trim();
        else if (clean.toLowerCase().startsWith("admin ")) clean = clean.substring(6).trim();
        else if (clean.equalsIgnoreCase("admin") || clean.equalsIgnoreCase("cape admin") || clean.equalsIgnoreCase("ac admin")) {
            if (AnimaCapesConfig.getInstance().isAdmin()) {
                printAdminHelp();
            } else {
                printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required. Use /admin auth <key>.");
            }
            return;
        }

        String[] parts = clean.split("\\s+", 3);
        String action = parts[0].toLowerCase();

        if (action.equals("auth") && parts.length > 1) {
            handleAuth(parts[1].trim());
            return;
        }

        if (!AnimaCapesConfig.getInstance().isAdmin()) {
            printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required. Use /admin auth <key>.");
            return;
        }

        if (action.equals("lookup") && parts.length > 1) {
            handleLookup(parts[1].trim());
        } else if (action.equals("purge") && parts.length > 1) {
            handlePurge(parts[1].trim());
        } else if (action.equals("ban") && parts.length > 1) {
            String reason = parts.length > 2 ? parts[2].trim() : "Banned by administrator";
            handleAdminBan(parts[1].trim(), reason);
        } else if (action.equals("unban") && parts.length > 1) {
            handleModify("ban", "DELETE", parts[1].trim());
        } else if (action.equals("banlist")) {
            handleList("ban");
        } else if (action.equals("whitelist")) {
            if (parts.length >= 3 && parts[1].equalsIgnoreCase("add")) {
                handleModify("whitelist", "POST", parts[2].trim());
            } else if (parts.length >= 3 && parts[1].equalsIgnoreCase("remove")) {
                handleModify("whitelist", "DELETE", parts[2].trim());
            } else {
                handleWhitelistView();
            }
        } else if (action.equals("status")) {
            handleStatus();
        } else if (action.equals("help")) {
            printAdminHelp();
        } else {
            printAdminHelp();
        }
    }

    private static void handleWhitelistRaw(String raw) {
        String clean = raw.trim();
        if (clean.toLowerCase().startsWith("cape whitelist ")) clean = clean.substring(15).trim();
        else if (clean.toLowerCase().startsWith("ac whitelist ")) clean = clean.substring(13).trim();
        else if (clean.toLowerCase().startsWith("whitelist ")) clean = clean.substring(10).trim();
        else if (clean.equalsIgnoreCase("whitelist") || clean.equalsIgnoreCase("cape whitelist") || clean.equalsIgnoreCase("ac whitelist")) {
            handleWhitelistView();
            return;
        }

        String[] parts = clean.split("\\s+");
        if (parts.length == 1 && parts[0].equalsIgnoreCase("list")) {
            handleWhitelistView();
            return;
        }

        if (!AnimaCapesConfig.getInstance().isAdmin()) {
            printFeedback("§c[Animated Capes] Permission Denied: Only administrators can modify the whitelist. Whitelisted members can view with /whitelist.");
            return;
        }

        if (parts.length >= 2 && parts[0].equalsIgnoreCase("add")) {
            handleModify("whitelist", "POST", parts[1]);
        } else if (parts.length >= 2 && parts[0].equalsIgnoreCase("remove")) {
            handleModify("whitelist", "DELETE", parts[1]);
        } else {
            handleModify("whitelist", "POST", parts[0]);
        }
    }

    private static void handleUnwhitelistRaw(String raw) {
        if (!AnimaCapesConfig.getInstance().isAdmin()) {
            printFeedback("§c[Animated Capes] Permission Denied: Only administrators can modify the whitelist.");
            return;
        }
        String clean = raw.trim();
        if (clean.toLowerCase().startsWith("cape unwhitelist ")) clean = clean.substring(17).trim();
        else if (clean.toLowerCase().startsWith("ac unwhitelist ")) clean = clean.substring(15).trim();
        else if (clean.toLowerCase().startsWith("unwhitelist ")) clean = clean.substring(12).trim();

        if (!clean.isEmpty()) {
            handleModify("whitelist", "DELETE", clean);
        } else {
            printFeedback("§c[Animated Capes] Usage: /unwhitelist <player>");
        }
    }

    private static void registerBrigadierCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        // /auth <key> (Description: Authenticate yourself)
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("auth")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("key", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String key = StringArgumentType.getString(ctx, "key");
                        handleAuth(key);
                        return 1;
                    }))
        );

        // /friend <add|remove|list>
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("friend")
                .executes(ctx -> {
                    printFriendsList();
                    return 1;
                })
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                    .executes(ctx -> {
                        printFriendsList();
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            handleAddFriend(player);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            handleRemoveFriend(player);
                            return 1;
                        })))
        );

        // /cape
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("cape")
                .executes(ctx -> {
                    printHelp();
                    return 1;
                })
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("menu")
                    .executes(ctx -> {
                        openMenu();
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("help")
                    .executes(ctx -> {
                        printHelp();
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("lock")
                    .executes(ctx -> {
                        handleLock(true);
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unlock")
                    .executes(ctx -> {
                        handleLock(false);
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("friend")
                    .executes(ctx -> {
                        printFriendsList();
                        return 1;
                    })
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                        .executes(ctx -> {
                            printFriendsList();
                            return 1;
                        }))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                            .executes(ctx -> {
                                String player = StringArgumentType.getString(ctx, "player");
                                handleAddFriend(player);
                                return 1;
                            })))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                            .executes(ctx -> {
                                String player = StringArgumentType.getString(ctx, "player");
                                handleRemoveFriend(player);
                                return 1;
                            }))))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("auth")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "key");
                            handleAuth(key);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("wear")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            CapeShareService.wearPlayerCape(player, ClientCommandHandler::printFeedback);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("report")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
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
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            CapeShareService.blockPlayer(player, ClientCommandHandler::printFeedback);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unblock")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
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
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("whitelist")
                    .executes(ctx -> {
                        handleWhitelistView();
                        return 1;
                    }))
        );

        // /whitelist (Description: Add a player to the whitelist)
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("whitelist")
                .executes(ctx -> {
                    handleWhitelistView();
                    return 1;
                })
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                    .executes(ctx -> {
                        handleWhitelistView();
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                        .executes(ctx -> {
                            if (!AnimaCapesConfig.getInstance().isAdmin()) {
                                printFeedback("§c[Animated Capes] Permission Denied: Only administrators can modify the whitelist.");
                                return 1;
                            }
                            String player = StringArgumentType.getString(ctx, "player");
                            handleModify("whitelist", "POST", player);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                        .executes(ctx -> {
                            if (!AnimaCapesConfig.getInstance().isAdmin()) {
                                printFeedback("§c[Animated Capes] Permission Denied: Only administrators can modify the whitelist.");
                                return 1;
                            }
                            String player = StringArgumentType.getString(ctx, "player");
                            handleModify("whitelist", "DELETE", player);
                            return 1;
                        })))
        );

        // /unwhitelist (Description: Remove a player from the whitelist)
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("unwhitelist")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                    .executes(ctx -> {
                        if (!AnimaCapesConfig.getInstance().isAdmin()) {
                            printFeedback("§c[Animated Capes] Permission Denied: Only administrators can modify the whitelist.");
                            return 1;
                        }
                        String player = StringArgumentType.getString(ctx, "player");
                        handleModify("whitelist", "DELETE", player);
                        return 1;
                    }))
        );

        // /admin (Strictly for administrators)
        dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("admin")
                .executes(ctx -> {
                    if (AnimaCapesConfig.getInstance().isAdmin()) {
                        printAdminHelp();
                    } else {
                        printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required. Use /admin auth <key>.");
                    }
                    return 1;
                })
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("auth")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "key");
                            handleAuth(key);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("lookup")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                        .executes(ctx -> {
                            if (!AnimaCapesConfig.getInstance().isAdmin()) {
                                printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required. Use /admin auth <key>.");
                                return 1;
                            }
                            String player = StringArgumentType.getString(ctx, "player");
                            handleLookup(player);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("purge")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                        .executes(ctx -> {
                            if (!AnimaCapesConfig.getInstance().isAdmin()) {
                                printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required. Use /admin auth <key>.");
                                return 1;
                            }
                            String player = StringArgumentType.getString(ctx, "player");
                            handlePurge(player);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("ban")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                        .executes(ctx -> {
                            if (!AnimaCapesConfig.getInstance().isAdmin()) {
                                printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required. Use /admin auth <key>.");
                                return 1;
                            }
                            String player = StringArgumentType.getString(ctx, "player");
                            handleAdminBan(player, "Banned by administrator");
                            return 1;
                        })
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("reason", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                if (!AnimaCapesConfig.getInstance().isAdmin()) {
                                    printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required. Use /admin auth <key>.");
                                    return 1;
                                }
                                String player = StringArgumentType.getString(ctx, "player");
                                String reason = StringArgumentType.getString(ctx, "reason");
                                handleAdminBan(player, reason);
                                return 1;
                            }))))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unban")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                        .executes(ctx -> {
                            if (!AnimaCapesConfig.getInstance().isAdmin()) {
                                printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required. Use /admin auth <key>.");
                                return 1;
                            }
                            String player = StringArgumentType.getString(ctx, "player");
                            handleModify("ban", "DELETE", player);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("banlist")
                    .executes(ctx -> {
                        if (!AnimaCapesConfig.getInstance().isAdmin()) {
                            printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required to view the banlist.");
                            return 1;
                        }
                        handleList("ban");
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("whitelist")
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                            .executes(ctx -> {
                                if (!AnimaCapesConfig.getInstance().isAdmin()) {
                                    printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required.");
                                    return 1;
                                }
                                String player = StringArgumentType.getString(ctx, "player");
                                handleModify("whitelist", "POST", player);
                                return 1;
                            })))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("player", StringArgumentType.string())
                            .executes(ctx -> {
                                if (!AnimaCapesConfig.getInstance().isAdmin()) {
                                    printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required.");
                                    return 1;
                                }
                                String player = StringArgumentType.getString(ctx, "player");
                                handleModify("whitelist", "DELETE", player);
                                return 1;
                            }))))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                    .executes(ctx -> {
                        if (!AnimaCapesConfig.getInstance().isAdmin()) {
                            printFeedback("§c[Animated Capes] Permission Denied: Admin authentication required.");
                            return 1;
                        }
                        handleStatus();
                        return 1;
                    }))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("help")
                    .executes(ctx -> {
                        printAdminHelp();
                        return 1;
                    }))
        );
    }

    public static void handleAuth(String key) {
        if (key == null || key.trim().isEmpty()) {
            printFeedback("§c[Animated Capes] Usage: /auth <secret_key>");
            return;
        }

        final String cleanKey = key.trim();
        AnimaCapesConfig.getInstance().adminKey = cleanKey;
        AnimaCapesConfig.getInstance().save();

        EXECUTOR.submit(() -> {
            AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
            String backend = cfg.backendUrl;
            if (backend == null || backend.trim().isEmpty()) {
                printFeedback("§e[Animated Capes] Admin key saved locally. Backend URL is not configured.");
                return;
            }
            if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(backend + "/admin/whitelist"))
                        .timeout(Duration.ofSeconds(6))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .header("X-Admin-Secret", cleanKey)
                        .GET()
                        .build();

                HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    printFeedback("§a[Animated Capes] Admin authentication SUCCESSFUL! You now have full administrative privileges.");
                } else if (resp.statusCode() == 401) {
                    printFeedback("§c[Animated Capes] Admin key validation failed: 401 Unauthorized. Key saved locally.");
                } else {
                    printFeedback("§e[Animated Capes] Admin key saved. Server responded HTTP " + resp.statusCode());
                }
            } catch (Exception e) {
                printFeedback("§a[Animated Capes] Admin key configured and saved successfully! You now have owner access.");
            }
        });
    }

    public static void handleLookup(String target) {
        if (target == null || target.trim().isEmpty()) {
            printFeedback("§c[Animated Capes Admin] Usage: /admin lookup <player>");
            return;
        }
        EXECUTOR.submit(() -> {
            AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
            String backend = cfg.backendUrl;
            if (backend == null || backend.trim().isEmpty()) {
                printFeedback("§c[Animated Capes] Backend URL is not configured.");
                return;
            }
            if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

            try {
                String url = backend + "/admin/lookup?user=" + URLEncoder.encode(target.trim(), StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .header("X-Admin-Secret", cfg.adminKey.trim())
                        .GET()
                        .build();

                HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                    String name = obj.has("name") ? obj.get("name").getAsString() : target;
                    String uuid = obj.has("uuid") ? obj.get("uuid").getAsString() : "Unknown";
                    boolean banned = obj.has("banned") && obj.get("banned").getAsBoolean();
                    boolean whitelisted = obj.has("whitelisted") && obj.get("whitelisted").getAsBoolean();
                    boolean hasCape = obj.has("hasCape") && obj.get("hasCape").getAsBoolean();

                    printFeedback("§b[Animated Capes Admin] Player Lookup: §f" + name + " §7(" + uuid + ")");
                    printFeedback("  §f• Moderation Status: " + (banned ? "§c[BANNED]" : "§a[ACTIVE]"));
                    printFeedback("  §f• Whitelist Status: " + (whitelisted ? "§6[WHITELISTED]" : "§7[STANDARD]"));
                    if (hasCape && obj.has("cape")) {
                        JsonObject cape = obj.getAsJsonObject("cape");
                        String hash = cape.has("hash") ? cape.get("hash").getAsString() : "unknown";
                        String fn = cape.has("fileName") ? cape.get("fileName").getAsString() : "cape.webp";
                        String capeUrl = cape.has("url") ? cape.get("url").getAsString() : "";
                        boolean allowCopy = cape.has("allowCopy") && cape.get("allowCopy").getAsBoolean();
                        printFeedback("  §f• Active Cape: §e" + hash.substring(0, Math.min(12, hash.length())) + "... §7(" + fn + ")");
                        printFeedback("  §f• Copy Permission: " + (allowCopy ? "§aPublic" : "§cLocked (Friends Only)"));
                        printFeedback("  §f• Direct URL: §9" + capeUrl);
                    } else {
                        printFeedback("  §f• Active Cape: §7None");
                    }
                } else if (resp.statusCode() == 401) {
                    printFeedback("§c[Animated Capes Admin] Access Denied: Invalid admin key. Use /admin auth <key>.");
                } else {
                    printFeedback("§c[Animated Capes Admin] HTTP " + resp.statusCode() + ": " + resp.body());
                }
            } catch (Exception e) {
                printFeedback("§c[Animated Capes Admin] Lookup failed: " + e.getMessage());
            }
        });
    }

    public static void handlePurge(String target) {
        if (target == null || target.trim().isEmpty()) {
            printFeedback("§c[Animated Capes Admin] Usage: /admin purge <player>");
            return;
        }
        EXECUTOR.submit(() -> {
            AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
            String backend = cfg.backendUrl;
            if (backend == null || backend.trim().isEmpty()) {
                printFeedback("§c[Animated Capes] Backend URL is not configured.");
                return;
            }
            if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

            try {
                JsonObject body = new JsonObject();
                body.addProperty("user", target.trim());

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(backend + "/admin/purge"))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .header("Content-Type", "application/json")
                        .header("X-Admin-Secret", cfg.adminKey.trim())
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();

                HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    printFeedback("§a[Animated Capes Admin] Successfully purged active cape globally for §f" + target + "§a.");
                } else if (resp.statusCode() == 401) {
                    printFeedback("§c[Animated Capes Admin] Access Denied: Invalid admin key. Use /admin auth <key>.");
                } else {
                    printFeedback("§c[Animated Capes Admin] Failed to purge cape: HTTP " + resp.statusCode() + ": " + resp.body());
                }
            } catch (Exception e) {
                printFeedback("§c[Animated Capes Admin] Purge failed: " + e.getMessage());
            }
        });
    }

    public static void handleAdminBan(String target, String reason) {
        if (target == null || target.trim().isEmpty()) {
            printFeedback("§c[Animated Capes Admin] Usage: /admin ban <player> [reason]");
            return;
        }
        EXECUTOR.submit(() -> {
            AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
            String backend = cfg.backendUrl;
            if (backend == null || backend.trim().isEmpty()) {
                printFeedback("§c[Animated Capes] Backend URL is not configured.");
                return;
            }
            if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

            try {
                JsonObject body = new JsonObject();
                body.addProperty("user", target.trim());
                if (reason != null && !reason.trim().isEmpty()) {
                    body.addProperty("reason", reason.trim());
                }

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(backend + "/admin/ban"))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .header("Content-Type", "application/json")
                        .header("X-Admin-Secret", cfg.adminKey.trim())
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();

                HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    printFeedback("§c[Animated Capes Admin] Successfully banned §f" + target + "§c. (Cape preserved in database; use /admin purge " + target + " to delete globally).");
                } else if (resp.statusCode() == 401) {
                    printFeedback("§c[Animated Capes Admin] Access Denied: Invalid admin key. Use /admin auth <key>.");
                } else {
                    printFeedback("§c[Animated Capes Admin] Ban failed: HTTP " + resp.statusCode() + ": " + resp.body());
                }
            } catch (Exception e) {
                printFeedback("§c[Animated Capes Admin] Ban failed: " + e.getMessage());
            }
        });
    }

    public static void handleStatus() {
        EXECUTOR.submit(() -> {
            AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
            String backend = cfg.backendUrl;
            if (backend == null || backend.trim().isEmpty()) {
                printFeedback("§c[Animated Capes] Backend URL is not configured.");
                return;
            }
            if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(backend + "/"))
                        .timeout(Duration.ofSeconds(6))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .GET()
                        .build();

                HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    printFeedback("§a[Animated Capes Admin] Cloud Backend Online: §f" + backend);
                } else {
                    printFeedback("§e[Animated Capes Admin] Cloud Backend Status: HTTP " + resp.statusCode());
                }
            } catch (Exception e) {
                printFeedback("§c[Animated Capes Admin] Backend connection failed: " + e.getMessage());
            }
        });
    }

    public static void handleWhitelistView() {
        EXECUTOR.submit(() -> {
            AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
            String backend = cfg.backendUrl;
            if (backend == null || backend.trim().isEmpty()) {
                printFeedback("§c[Animated Capes] Backend URL is not configured.");
                return;
            }
            if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

            String requesterName = "Anonymous";
            String requesterUuid = "";
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getUser() != null) {
                    requesterName = mc.getUser().getName();
                    if (mc.getUser().getProfileId() != null) {
                        requesterUuid = mc.getUser().getProfileId().toString();
                    }
                }
            } catch (Throwable ignored) {}

            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(backend + "/whitelist"))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .header("X-Player-Name", requesterName)
                        .header("X-Player-UUID", requesterUuid)
                        .GET();

                if (cfg.isAdmin()) {
                    reqBuilder.header("X-Admin-Secret", cfg.adminKey.trim());
                }

                HttpResponse<String> resp = HTTP_CLIENT.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                    JsonArray list = obj.has("combinedList") ? obj.getAsJsonArray("combinedList") : new JsonArray();
                    printFeedback("§b[Animated Capes] Animated WebP Whitelist (" + list.size() + " entries):");
                    if (list.size() == 0) {
                        printFeedback("  §7(No players whitelisted yet)");
                    } else {
                        for (JsonElement el : list) {
                            printFeedback("  §f• " + el.getAsString());
                        }
                    }
                } else if (resp.statusCode() == 403) {
                    printFeedback("§c[Animated Capes] Access Denied: Only whitelisted members and the owner can view the whitelist.");
                } else {
                    printFeedback("§c[Animated Capes] HTTP " + resp.statusCode() + ": " + resp.body());
                }
            } catch (Exception e) {
                printFeedback("§c[Animated Capes] Failed to query whitelist: " + e.getMessage());
            }
        });
    }

    public static void handleModify(String type, String method, String target) {
        if (target == null || target.trim().isEmpty()) {
            printFeedback("§c[Animated Capes] Please specify a player username or UUID.");
            return;
        }

        final String cleanTarget = target.trim();
        EXECUTOR.submit(() -> {
            AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
            String backend = cfg.backendUrl;
            if (backend == null || backend.trim().isEmpty()) {
                printFeedback("§c[Animated Capes] Backend URL is not configured.");
                return;
            }
            if (backend.endsWith("/")) backend = backend.substring(0, backend.length() - 1);

            String adminKey = cfg.adminKey;
            if (adminKey == null || adminKey.trim().isEmpty()) {
                printFeedback("§c[Animated Capes] Action rejected: Admin authentication required. Use /admin auth <key>.");
                return;
            }

            try {
                String url = backend + "/admin/" + type;
                JsonObject body = new JsonObject();
                body.addProperty("user", cleanTarget);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "AnimatedCapes-Mod/1.0.0")
                        .header("Content-Type", "application/json")
                        .header("X-Admin-Secret", adminKey.trim());

                if ("POST".equalsIgnoreCase(method)) {
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    reqBuilder.method("DELETE", HttpRequest.BodyPublishers.ofString(body.toString()));
                }

                HttpResponse<String> resp = HTTP_CLIENT.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonObject resObj = JsonParser.parseString(resp.body()).getAsJsonObject();
                    String name = resObj.has("name") ? resObj.get("name").getAsString() : cleanTarget;
                    if ("whitelist".equals(type)) {
                        if ("POST".equalsIgnoreCase(method)) {
                            printFeedback("§a[Animated Capes Admin] Whitelisted §f" + name + "§a for Animated WebP uploads!");
                        } else {
                            printFeedback("§e[Animated Capes Admin] Removed §f" + name + "§e from Animated WebP whitelist.");
                        }
                    } else if ("ban".equals(type)) {
                        if ("POST".equalsIgnoreCase(method)) {
                            printFeedback("§c[Animated Capes Admin] Successfully banned §f" + name + "§c. (Cape preserved in database; use /admin purge " + name + " to delete globally).");
                        } else {
                            printFeedback("§a[Animated Capes Admin] Successfully unbanned §f" + name + "§a.");
                        }
                    }
                } else if (resp.statusCode() == 401) {
                    printFeedback("§c[Animated Capes Admin] Access Denied: Invalid admin key. Use /admin auth <key>.");
                } else {
                    printFeedback("§c[Animated Capes Admin] Failed (" + resp.statusCode() + "): " + resp.body());
                }
            } catch (Exception e) {
                printFeedback("§c[Animated Capes Admin] Request failed: " + e.getMessage());
            }
        });
    }

    public static void handleList(String endpointType) {
        EXECUTOR.submit(() -> {
            AnimaCapesConfig cfg = AnimaCapesConfig.getInstance();
            String backend = cfg.backendUrl;
            if (backend == null || backend.trim().isEmpty()) {
                printFeedback("§c[Animated Capes] Backend URL is not configured.");
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
                        printFeedback("§c[Animated Capes Admin] Global Banlist (" + list.size() + " entries):");
                        if (list.size() == 0) {
                            printFeedback("  §7(No players banned)");
                        } else {
                            for (JsonElement el : list) {
                                printFeedback("  §f• " + el.getAsString());
                            }
                        }
                    }
                } else if (resp.statusCode() == 401) {
                    printFeedback("§c[Animated Capes Admin] Access Denied: Invalid admin key. Use /admin auth <key>.");
                } else {
                    printFeedback("§c[Animated Capes Admin] HTTP " + resp.statusCode() + ": " + resp.body());
                }
            } catch (Exception e) {
                printFeedback("§c[Animated Capes Admin] Failed to query list: " + e.getMessage());
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
        printFeedback("§f/cape menu §7- Open the Animated Capes Manager & Gallery GUI");
        printFeedback("§f/cape wear <player> §7- Search & equip another player's active cape");
        printFeedback("§f/cape lock §7- Lock your cape from being copied (default)");
        printFeedback("§f/cape unlock §7- Allow any player to copy your cape");
        printFeedback("§f/friend add <player> §7- Add a friend so they can automatically copy your cape");
        printFeedback("§f/friend remove <player> §7- Remove a player from your friends list");
        printFeedback("§f/friend list §7- View your friends list");
        printFeedback("§f/cape report <player> [reason] §7- Report an inappropriate cape to moderation");
        printFeedback("§f/cape block <player> §7- Block a player so you never see their cape");
        printFeedback("§f/cape unblock <player> §7- Unblock a player");
        printFeedback("§f/cape blocklist §7- View your blocked players list");
        printFeedback("§f/whitelist §7- Add a player to the whitelist");
        printFeedback("§f/unwhitelist <player> §7- Remove a player from the whitelist");
        printFeedback("§f/auth <key> §7- Authenticate yourself");
        if (AnimaCapesConfig.getInstance().isAdmin()) {
            printFeedback("§cType /admin help to view administrator commands.");
        }
    }

    public static void printAdminHelp() {
        printFeedback("§c=== Animated Capes Admin Commands ===");
        printFeedback("§f/admin auth <key> §7- Authenticate yourself");
        printFeedback("§f/admin lookup <player> §7- Look up player cape metadata & moderation status");
        printFeedback("§f/admin purge <player> §7- Purge a player's cape globally from cloud");
        printFeedback("§f/admin ban <player> [reason] §7- Ban a player without purging cape");
        printFeedback("§f/admin unban <player> §7- Unban a player");
        printFeedback("§f/admin banlist §7- View banned players list");
        printFeedback("§f/admin whitelist add <player> §7- Add a player to the whitelist");
        printFeedback("§f/admin whitelist remove <player> §7- Remove a player from the whitelist");
        printFeedback("§f/admin status §7- View backend cloud status & webhook configuration");
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
