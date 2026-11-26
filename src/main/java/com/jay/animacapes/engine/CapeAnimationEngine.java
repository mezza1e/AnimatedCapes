package com.jay.animacapes.engine;

import com.jay.animacapes.codec.AnimationDecoder;
import com.jay.animacapes.codec.DecodedAnimation;
import com.jay.animacapes.codec.DecodedFrame;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class CapeAnimationEngine {
    private static final CapeAnimationEngine INSTANCE = new CapeAnimationEngine();

    public static final Identifier RUNTIME_CAPE_ID = Identifier.fromNamespaceAndPath(
        "animacapes",
        "textures/entity/cape_runtime.png"
    );

    private volatile DecodedAnimation currentAnimation;
    private DynamicTexture runtimeTexture;
    private int lastUploadedFrameIdx = -1;

    private volatile boolean isLoaded = false;
    private volatile String currentFilePath = "";
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private volatile String lastErrorMessage = "";
    private long animationStartTime = 0;

    public static CapeAnimationEngine getInstance() {
        return INSTANCE;
    }

    public synchronized boolean hasCape() {
        return AnimaCapesConfig.getInstance().enabled && isLoaded && currentAnimation != null && currentAnimation.getFrameCount() > 0;
    }

    private void ensureRuntimeTexture() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getTextureManager() != null) {
            if (runtimeTexture == null) {
                NativeImage base = new NativeImage(NativeImage.Format.RGBA, DecodedFrame.TARGET_W, DecodedFrame.TARGET_H, true);
                base.fillRect(0, 0, DecodedFrame.TARGET_W, DecodedFrame.TARGET_H, 0xFFFFFFFF);
                runtimeTexture = new DynamicTexture(() -> "animacapes_runtime", base);
                mc.getTextureManager().register(RUNTIME_CAPE_ID, runtimeTexture);
                System.out.println("[AnimaCapes] Allocated 2048x1024 HD Cape DynamicTexture");
            } else if (mc.getTextureManager().getTexture(RUNTIME_CAPE_ID) == null) {
                mc.getTextureManager().register(RUNTIME_CAPE_ID, runtimeTexture);
            }
        }
    }

    public synchronized Identifier getActiveCapeIdentifier() {
        if (!hasCape()) return null;

        DecodedAnimation anim = this.currentAnimation;
        if (anim == null || anim.getFrameCount() == 0) return null;

        ensureRuntimeTexture();
        if (runtimeTexture == null) return null;

        float speed = AnimaCapesConfig.getInstance().getAnimationSpeed();
        long elapsed = (long) (Math.max(0, AnimationClock.getTimeMs() - animationStartTime) * speed);
        int frameIdx = anim.getFrameIndexAt(elapsed);

        if (frameIdx != lastUploadedFrameIdx) {
            lastUploadedFrameIdx = frameIdx;
            List<DecodedFrame> frames = anim.getFrames();
            if (frameIdx >= 0 && frameIdx < frames.size()) {
                DecodedFrame frame = frames.get(frameIdx);
                NativeImage pixels = runtimeTexture.getPixels();
                if (pixels != null) {
                    String fitMode = AnimaCapesConfig.getInstance().fitMode;
                    frame.stampOntoCapeImage(pixels, fitMode);
                    runtimeTexture.upload();
                }
            }
        }

        return RUNTIME_CAPE_ID;
    }

    public int getFrameCount() {
        DecodedAnimation anim = this.currentAnimation;
        return (anim != null) ? anim.getFrameCount() : 0;
    }

    public long getTotalDurationMs() {
        DecodedAnimation anim = this.currentAnimation;
        return (anim != null) ? anim.getTotalDurationMs() : 0;
    }

    public boolean isLoading() {
        return isLoading.get();
    }

    public String getCurrentFilePath() {
        return currentFilePath;
    }

    public synchronized void loadDirect(File file) {
        if (file == null || !file.exists()) {
            clearCape();
            return;
        }
        try {
            DecodedAnimation anim = AnimationDecoder.decode(file);
            applyDecodedAnimation(anim);
            this.currentFilePath = file.getAbsolutePath();
            this.animationStartTime = AnimationClock.getTimeMs();
            this.isLoaded = true;
            System.out.println("[AnimaCapes] Directly loaded " + anim.getFrameCount() + " frames (" + file.getName() + ")");
        } catch (Exception e) {
            System.err.println("[AnimaCapes] Direct load failed: " + e.getMessage());
        }
    }

    public void loadFromFile(File file) {
        if (file == null || !file.exists()) {
            clearCape();
            return;
        }

        if (isLoading.getAndSet(true)) {
            return;
        }

        currentFilePath = file.getAbsolutePath();
        animationStartTime = AnimationClock.getTimeMs();

        Thread loaderThread = new Thread(() -> {
            try {
                System.out.println("[AnimaCapes] Decoding animation from: " + file.getName());
                DecodedAnimation anim = AnimationDecoder.decode(file);
                if (anim != null) {
                    String fitMode = AnimaCapesConfig.getInstance().fitMode;
                    anim.preRasterizeAllFrames(fitMode);
                }

                Minecraft.getInstance().execute(() -> {
                    try {
                        applyDecodedAnimation(anim);
                        lastErrorMessage = "";
                        System.out.println("[AnimaCapes] Loaded " + anim.getFrameCount() + " frames successfully (" + anim.getTotalDurationMs() + "ms loop)");
                    } catch (Exception e) {
                        lastErrorMessage = (e.getMessage() != null) ? e.getMessage() : "Failed to apply textures";
                        System.err.println("[AnimaCapes] Failed to apply textures: " + lastErrorMessage);
                    } finally {
                        isLoading.set(false);
                    }
                });
            } catch (Exception e) {
                lastErrorMessage = (e.getMessage() != null) ? e.getMessage() : "Failed to decode cape";
                System.err.println("[AnimaCapes] Failed to decode cape file: " + lastErrorMessage);
                isLoading.set(false);
            }
        }, "AnimaCapes-Decoder");
        loaderThread.setDaemon(true);
        loaderThread.start();
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    private synchronized void applyDecodedAnimation(DecodedAnimation anim) {
        this.currentAnimation = anim;
        this.lastUploadedFrameIdx = -1;
        this.animationStartTime = AnimationClock.getTimeMs();
        this.isLoaded = true;

        ensureRuntimeTexture();
        if (runtimeTexture != null && anim.getFrameCount() > 0) {
            String fitMode = AnimaCapesConfig.getInstance().fitMode;
            DecodedFrame firstFrame = anim.getFrames().get(0);
            NativeImage pixels = runtimeTexture.getPixels();
            if (pixels != null) {
                firstFrame.stampOntoCapeImage(pixels, fitMode);
                runtimeTexture.upload();
                lastUploadedFrameIdx = 0;
            }
        }

        try {
            if (currentFilePath != null && !currentFilePath.isEmpty()) {
                com.jay.animacapes.cloud.CloudCapeSync.getInstance().syncCurrentCapeAsync(new File(currentFilePath));
            }
        } catch (Throwable ignored) {}
    }

    public synchronized void reload() {
        String path = AnimaCapesConfig.getInstance().capeFilePath;
        if (path != null && !path.trim().isEmpty()) {
            File f = new File(path);
            if (f.exists()) {
                loadFromFile(f);
            }
        }
    }

    public synchronized void clearCape() {
        lastUploadedFrameIdx = -1;
        currentAnimation = null;
        isLoaded = false;
        currentFilePath = "";
        try {
            com.jay.animacapes.cloud.CloudCapeSync.getInstance().deleteCurrentCapeAsync();
        } catch (Throwable ignored) {}
        if (runtimeTexture != null) {
            NativeImage pixels = runtimeTexture.getPixels();
            if (pixels != null) {
                pixels.fillRect(0, 0, DecodedFrame.TARGET_W, DecodedFrame.TARGET_H, 0xFFFFFFFF);
                runtimeTexture.upload();
            }
        }
    }
}
