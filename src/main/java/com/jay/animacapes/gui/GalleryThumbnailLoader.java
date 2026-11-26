package com.jay.animacapes.gui;

import com.jay.animacapes.codec.AnimationDecoder;
import com.jay.animacapes.codec.DecodedAnimation;
import com.jay.animacapes.codec.DecodedFrame;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.jay.animacapes.engine.AnimationClock;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryThumbnailLoader {

    public static class AnimatedThumbnail {
        public final Identifier id;
        public final DynamicTexture texture;
        public final DecodedAnimation animation;
        public final NativeImage nativeImage;
        public int lastFrameIdx = -1;

        public AnimatedThumbnail(Identifier id, DynamicTexture texture, DecodedAnimation animation, NativeImage nativeImage) {
            this.id = id;
            this.texture = texture;
            this.animation = animation;
            this.nativeImage = nativeImage;
        }
    }

    private static final Map<String, AnimatedThumbnail> THUMB_ENTRIES = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "AnimaCapes-ThumbnailLoader");
        t.setDaemon(true);
        return t;
    });

    public static final int THUMB_W = 70;
    public static final int THUMB_H = 112;

    public static Identifier getThumbnail(File file) {
        return getThumbnail(file, AnimaCapesConfig.getInstance().getAnimationSpeed());
    }

    public static Identifier getThumbnail(File file, float speed) {
        if (file == null || !file.exists()) return null;
        String absPath = file.getAbsolutePath();

        AnimatedThumbnail entry = THUMB_ENTRIES.get(absPath);
        if (entry != null) {
            DecodedAnimation anim = entry.animation;
            if (anim != null && anim.getFrameCount() > 1) {
                long elapsed = (long) (AnimationClock.getTimeMs() * speed);
                int frameIdx = anim.getFrameIndexAt(elapsed);

                if (frameIdx != entry.lastFrameIdx) {
                    entry.lastFrameIdx = frameIdx;
                    DecodedFrame frame = anim.getFrames().get(frameIdx);
                    updateThumbnailPixels(entry.nativeImage, frame);
                    entry.texture.upload();
                }
            }
            return entry.id;
        }

        if (PENDING.add(absPath)) {
            WORKER.submit(() -> {
                try {
                    DecodedAnimation anim = AnimationDecoder.decode(file);
                    if (anim == null || anim.getFrames().isEmpty()) {
                        PENDING.remove(absPath);
                        return;
                    }

                    String fitMode = AnimaCapesConfig.getInstance().fitMode;
                    anim.preRasterizeAllFrames(fitMode);

                    DecodedFrame firstFrame = anim.getFrames().get(0);
                    NativeImage img = new NativeImage(NativeImage.Format.RGBA, THUMB_W, THUMB_H, true);
                    updateThumbnailPixels(img, firstFrame);

                    String sanitized = file.getName().toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
                    Identifier id = Identifier.fromNamespaceAndPath(
                        "animacapes",
                        "textures/thumbs/" + sanitized + "_" + Math.abs(absPath.hashCode())
                    );

                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) {
                        mc.execute(() -> {
                            try {
                                DynamicTexture tex = new DynamicTexture(() -> "animacapes_thumb_" + sanitized, img);
                                mc.getTextureManager().register(id, tex);
                                AnimatedThumbnail newEntry = new AnimatedThumbnail(id, tex, anim, img);
                                newEntry.lastFrameIdx = 0;
                                THUMB_ENTRIES.put(absPath, newEntry);
                            } catch (Exception e) {
                                System.err.println("[AnimaCapes] Error registering thumbnail texture: " + e.getMessage());
                            } finally {
                                PENDING.remove(absPath);
                            }
                        });
                    } else {
                        PENDING.remove(absPath);
                    }
                } catch (Throwable t) {
                    PENDING.remove(absPath);
                    System.err.println("[AnimaCapes] Thumbnail decode failed for " + file.getName() + ": " + t.getMessage());
                }
            });
        }

        return null;
    }

    private static void updateThumbnailPixels(NativeImage dest, DecodedFrame frame) {
        String fitMode = AnimaCapesConfig.getInstance().fitMode;
        int[] slot = frame.getOrRasterizeSlotPixels(fitMode);
        if (slot == null || slot.length == 0) return;

        for (int y = 0; y < THUMB_H; y++) {
            int srcY = (y * 512) / THUMB_H;
            int rowOff = srcY * 320;
            for (int x = 0; x < THUMB_W; x++) {
                int srcX = (x * 320) / THUMB_W;
                dest.setPixel(x, y, slot[rowOff + srcX]);
            }
        }
    }

    public static void removeThumbnail(File file) {
        if (file == null) return;
        String absPath = file.getAbsolutePath();
        AnimatedThumbnail entry = THUMB_ENTRIES.remove(absPath);
        PENDING.remove(absPath);
        if (entry != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getTextureManager() != null) {
                if (entry.id != null) {
                    try {
                        mc.getTextureManager().release(entry.id);
                    } catch (Exception ignored) {}
                }
                if (entry.texture != null) {
                    try {
                        entry.texture.close();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    public static void clearCache() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getTextureManager() != null) {
            for (AnimatedThumbnail entry : THUMB_ENTRIES.values()) {
                if (entry != null && entry.id != null) {
                    try {
                        mc.getTextureManager().release(entry.id);
                    } catch (Exception ignored) {}
                }
                if (entry != null && entry.texture != null) {
                    try {
                        entry.texture.close();
                    } catch (Exception ignored) {}
                }
            }
        }
        THUMB_ENTRIES.clear();
        PENDING.clear();
    }
}
