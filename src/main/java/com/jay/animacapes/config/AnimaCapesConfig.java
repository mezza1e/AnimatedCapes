package com.jay.animacapes.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AnimaCapesConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AnimaCapesConfig INSTANCE;

    public boolean enabled = true;
    public boolean hideVanillaCape = true;
    public boolean showOtherPlayerCapes = true;
    public String capeFilePath = "";
    public String fitMode = "COVER"; // COVER, CONTAIN, STRETCH
    public int fpsLimit = 0; // 0 = native file fps
    public boolean syncReplay = false;
    public boolean cloudSyncEnabled = true;
    public String backendUrl = "https://animated-capes.officerpot10.workers.dev";
    public String adminKey = "";
    public float animationSpeed = 1.0f; // 0.25f to 3.0f, default 1.0f
    public List<String> blockedUsers = new ArrayList<>();
    public boolean allowCapeCopy = false; // Locked by default (players cannot copy other players' capes)
    public List<String> friends = new ArrayList<>();
    public List<String> copyBlockedUsers = new ArrayList<>();

    public boolean isAdmin() {
        return adminKey != null && !adminKey.trim().isEmpty();
    }

    public boolean isFriend(String nameOrUuid) {
        if (nameOrUuid == null || friends == null || friends.isEmpty()) return false;
        String clean = nameOrUuid.trim().toLowerCase();
        for (String f : friends) {
            if (f != null && f.trim().equalsIgnoreCase(clean)) {
                return true;
            }
        }
        return false;
    }

    public boolean addFriend(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.trim().isEmpty()) return false;
        if (friends == null) friends = new ArrayList<>();
        String clean = nameOrUuid.trim();
        if (!isFriend(clean)) {
            friends.add(clean);
            save();
            return true;
        }
        return false;
    }

    public boolean removeFriend(String nameOrUuid) {
        if (nameOrUuid == null || friends == null || friends.isEmpty()) return false;
        String clean = nameOrUuid.trim().toLowerCase();
        boolean removed = friends.removeIf(f -> f != null && f.trim().equalsIgnoreCase(clean));
        if (removed) {
            save();
        }
        return removed;
    }

    public List<String> getFriends() {
        if (friends == null) friends = new ArrayList<>();
        return new ArrayList<>(friends);
    }

    public boolean isCopyBlocked(String nameOrUuid) {
        if (nameOrUuid == null || copyBlockedUsers == null || copyBlockedUsers.isEmpty()) return false;
        String clean = nameOrUuid.trim().toLowerCase();
        for (String b : copyBlockedUsers) {
            if (b != null && b.trim().equalsIgnoreCase(clean)) {
                return true;
            }
        }
        return false;
    }

    public boolean blockCopyUser(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.trim().isEmpty()) return false;
        if (copyBlockedUsers == null) copyBlockedUsers = new ArrayList<>();
        String clean = nameOrUuid.trim();
        if (!isCopyBlocked(clean)) {
            copyBlockedUsers.add(clean);
            save();
            return true;
        }
        return false;
    }

    public boolean unblockCopyUser(String nameOrUuid) {
        if (nameOrUuid == null || copyBlockedUsers == null || copyBlockedUsers.isEmpty()) return false;
        String clean = nameOrUuid.trim().toLowerCase();
        boolean removed = copyBlockedUsers.removeIf(b -> b != null && b.trim().equalsIgnoreCase(clean));
        if (removed) {
            save();
        }
        return removed;
    }

    public List<String> getCopyBlockedUsers() {
        if (copyBlockedUsers == null) copyBlockedUsers = new ArrayList<>();
        return new ArrayList<>(copyBlockedUsers);
    }

    public List<String> getEffectiveCopyBlockedUsers() {
        Set<String> set = new LinkedHashSet<>();
        if (copyBlockedUsers != null) {
            for (String s : copyBlockedUsers) {
                if (s != null && !s.trim().isEmpty()) set.add(s.trim());
            }
        }
        if (blockedUsers != null) {
            for (String s : blockedUsers) {
                if (s != null && !s.trim().isEmpty()) set.add(s.trim());
            }
        }
        return new ArrayList<>(set);
    }

    public boolean isBlocked(String uuidOrName) {
        if (uuidOrName == null || blockedUsers == null || blockedUsers.isEmpty()) return false;
        String clean = uuidOrName.trim().toLowerCase();
        for (String b : blockedUsers) {
            if (b != null && b.trim().equalsIgnoreCase(clean)) {
                return true;
            }
        }
        return false;
    }

    public boolean isBlocked(java.util.UUID uuid, String name) {
        if (blockedUsers == null || blockedUsers.isEmpty()) return false;
        if (uuid != null && isBlocked(uuid.toString())) return true;
        if (name != null && isBlocked(name)) return true;
        return false;
    }

    public boolean blockUser(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.trim().isEmpty()) return false;
        if (blockedUsers == null) blockedUsers = new ArrayList<>();
        String clean = nameOrUuid.trim();
        if (!isBlocked(clean)) {
            blockedUsers.add(clean);
            save();
            return true;
        }
        return false;
    }

    public boolean unblockUser(String nameOrUuid) {
        if (nameOrUuid == null || blockedUsers == null || blockedUsers.isEmpty()) return false;
        String clean = nameOrUuid.trim().toLowerCase();
        boolean removed = blockedUsers.removeIf(b -> b != null && b.trim().equalsIgnoreCase(clean));
        if (removed) {
            save();
        }
        return removed;
    }

    public List<String> getBlockedUsers() {
        if (blockedUsers == null) blockedUsers = new ArrayList<>();
        return new ArrayList<>(blockedUsers);
    }

    public float getAnimationSpeed() {
        if (animationSpeed <= 0.05f || animationSpeed > 10.0f) {
            return 1.0f;
        }
        return animationSpeed;
    }

    private static File getConfigFile() {
        File gameDir = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                gameDir = mc.gameDirectory;
            }
        } catch (Throwable ignored) {}
        if (gameDir == null) {
            gameDir = new File(".");
        }
        return new File(gameDir, "config/animacapes.json");
    }

    public static AnimaCapesConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static AnimaCapesConfig load() {
        File configFile = getConfigFile();
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                AnimaCapesConfig config = GSON.fromJson(reader, AnimaCapesConfig.class);
                if (config != null) {
                    if (config.blockedUsers == null) config.blockedUsers = new ArrayList<>();
                    if (config.friends == null) config.friends = new ArrayList<>();
                    if (config.copyBlockedUsers == null) config.copyBlockedUsers = new ArrayList<>();
                    if (config.backendUrl == null || config.backendUrl.trim().isEmpty() || config.backendUrl.contains("localhost") || config.backendUrl.contains("fly.dev")) {
                        config.backendUrl = "https://animated-capes.officerpot10.workers.dev";
                        config.save();
                    }
                    return config;
                }
            } catch (Exception e) {
                System.err.println("[AnimaCapes] Failed to load config: " + e.getMessage());
            }
        }
        AnimaCapesConfig config = new AnimaCapesConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            File configFile = getConfigFile();
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(configFile)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("[AnimaCapes] Failed to save config: " + e.getMessage());
        }
    }
}
