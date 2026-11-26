package com.jay.animacapes.gui;

import com.jay.animacapes.config.AnimaCapesConfig;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class GalleryFolder {
    public static final String FOLDER_NAME = "animacapes";
    public static final int MAX_GALLERY_CAPES = 30;
    private static final java.util.Map<String, Boolean> APNG_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isBypassLimit() {
        return AnimaCapesConfig.getInstance().isAdmin()
                || com.jay.animacapes.cloud.CloudCapeSync.getInstance().isWhitelistedAccount();
    }

    public static File getFolder() {
        File baseDir = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                baseDir = mc.gameDirectory;
            }
        } catch (Throwable ignored) {}
        if (baseDir == null) {
            baseDir = new File(".");
        }
        File folder = new File(baseDir, FOLDER_NAME);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    public static boolean isSupportedFile(File f) {
        if (f == null || !f.isFile()) return false;
        String name = f.getName().toLowerCase(Locale.ROOT);
        if (!com.jay.animacapes.codec.AnimationDecoder.isSupportedFormat(name)) return false;
        if (name.endsWith(".png")) {
            String cacheKey = f.getAbsolutePath() + ":" + f.lastModified() + ":" + f.length();
            Boolean cached = APNG_CACHE.get(cacheKey);
            if (cached == null) {
                cached = com.jay.animacapes.codec.AnimationDecoder.isApng(f);
                APNG_CACHE.put(cacheKey, cached);
            }
            if (cached) return false;
        }
        return f.length() <= com.jay.animacapes.codec.AnimationDecoder.getMaxAllowedBytes(name);
    }

    public static List<File> listCapeFiles() {
        File folder = getFolder();

        // If config has an active cape file outside the folder, auto-copy it in so it appears in the gallery
        try {
            String activePath = AnimaCapesConfig.getInstance().capeFilePath;
            if (activePath != null && !activePath.trim().isEmpty()) {
                File activeFile = new File(activePath);
                if (activeFile.exists() && activeFile.isFile() && isSupportedFile(activeFile)) {
                    copyToGallery(activeFile);
                }
            }
        } catch (Throwable ignored) {}

        File[] files = folder.listFiles();
        List<File> list = new ArrayList<>();
        if (files != null) {
            // Deduplicate files sharing the same stem (e.g. 6.webp vs 6.gif)
            // Priority: .webp > .gif > .png > .jpg > .jpeg
            java.util.Map<String, File> stemMap = new java.util.LinkedHashMap<>();
            List<File> toClean = new ArrayList<>();
            for (File f : files) {
                if (!isSupportedFile(f)) continue;
                String fname = f.getName().toLowerCase(Locale.ROOT);
                int dot = fname.lastIndexOf('.');
                String stem = (dot > 0) ? fname.substring(0, dot) : fname;
                String ext = (dot > 0) ? fname.substring(dot) : "";

                File existing = stemMap.get(stem);
                if (existing == null) {
                    stemMap.put(stem, f);
                } else {
                    String existingName = existing.getName().toLowerCase(Locale.ROOT);
                    boolean replace = false;
                    if (ext.equals(".webp")) {
                        replace = true;
                    } else if (ext.equals(".gif") && !existingName.endsWith(".webp")) {
                        replace = true;
                    }

                    if (replace) {
                        stemMap.put(stem, f);
                        toClean.add(existing);
                    } else {
                        toClean.add(f);
                    }
                }
            }

            // Clean up redundant duplicate files from disk
            for (File dup : toClean) {
                try {
                    String active = AnimaCapesConfig.getInstance().capeFilePath;
                    if (active != null && active.equalsIgnoreCase(dup.getAbsolutePath())) {
                        String stem = dup.getName().substring(0, dup.getName().lastIndexOf('.')).toLowerCase(Locale.ROOT);
                        File replacement = stemMap.get(stem);
                        if (replacement != null) {
                            AnimaCapesConfig.getInstance().capeFilePath = replacement.getAbsolutePath();
                            AnimaCapesConfig.getInstance().save();
                        }
                    }
                    dup.delete();
                } catch (Throwable ignored) {}
            }

            list.addAll(stemMap.values());
        }
        Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    public static File copyToGallery(File source) {
        if (source == null || !source.exists() || !source.isFile()) return null;
        if (source.length() > com.jay.animacapes.codec.AnimationDecoder.getMaxAllowedBytes(source.getName())) {
            System.err.println("[AnimatedCapes] File exceeds allowed size limit.");
            return null;
        }
        File galleryDir = getFolder();
        File dest = new File(galleryDir, source.getName());

        if (source.getParentFile() != null && source.getParentFile().getAbsolutePath().equalsIgnoreCase(galleryDir.getAbsolutePath())) {
            return source;
        }

        // Hard limit: Max 30 capes allowed to prevent disk spam (Whitelisted/Admin bypasses)
        List<File> currentFiles = listCapeFiles();
        if (!isBypassLimit() && currentFiles.size() >= MAX_GALLERY_CAPES && !dest.exists()) {
            System.err.println("[AnimatedCapes] Upload limit reached!");
            return null;
        }

        try {
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (IOException e) {
            System.err.println("[AnimatedCapes] Failed to copy file to gallery: " + e.getMessage());
            return source;
        }
    }

    public static void openFolderInExplorer() {
        File folder = getFolder();
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", folder.getAbsolutePath()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", folder.getAbsolutePath()).start();
            } else if (os.contains("nix") || os.contains("nux")) {
                new ProcessBuilder("xdg-open", folder.getAbsolutePath()).start();
            } else if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(folder);
            }
        } catch (Exception e) {
            System.err.println("[AnimatedCapes] Failed to open folder in explorer: " + e.getMessage());
        }
    }
}
