package com.jay.animacapes.cloud;

import com.jay.animacapes.codec.DecodedAnimation;
import com.jay.animacapes.codec.DecodedFrame;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.jay.animacapes.engine.AnimationClock;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.List;

public class RemotePlayerCapeInstance {
    private final String hash;
    private final DecodedAnimation animation;
    private final Identifier textureId;
    private DynamicTexture runtimeTexture;
    private int lastUploadedFrameIdx = -1;
    private long lastRenderTimeMs;

    public RemotePlayerCapeInstance(String hash, DecodedAnimation animation) {
        this.hash = hash;
        this.animation = animation;
        String safeHash = hash.substring(0, Math.min(16, hash.length())).toLowerCase().replaceAll("[^a-z0-9]", "_");
        this.textureId = Identifier.fromNamespaceAndPath("animacapes", "textures/remote/cape_" + safeHash + ".png");
        this.lastRenderTimeMs = System.currentTimeMillis();
        ensureRuntimeTexture();
    }

    private void ensureRuntimeTexture() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getTextureManager() != null) {
            if (runtimeTexture == null) {
                NativeImage base = new NativeImage(NativeImage.Format.RGBA, DecodedFrame.TARGET_W, DecodedFrame.TARGET_H, true);
                base.fillRect(0, 0, DecodedFrame.TARGET_W, DecodedFrame.TARGET_H, 0xFFFFFFFF);
                runtimeTexture = new DynamicTexture(() -> "animacapes_remote_" + hash, base);
                mc.getTextureManager().register(textureId, runtimeTexture);
            } else if (mc.getTextureManager().getTexture(textureId) == null) {
                mc.getTextureManager().register(textureId, runtimeTexture);
            }
        }
    }

    public synchronized Identifier getActiveCapeIdentifier() {
        if (animation == null || animation.getFrameCount() == 0) return null;
        ensureRuntimeTexture();
        if (runtimeTexture == null) return null;

        this.lastRenderTimeMs = System.currentTimeMillis();

        long timeMs = AnimationClock.getTimeMs();
        int frameIdx = animation.getFrameIndexAt(timeMs);

        if (frameIdx != lastUploadedFrameIdx) {
            lastUploadedFrameIdx = frameIdx;
            List<DecodedFrame> frames = animation.getFrames();
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

        return textureId;
    }

    public long getLastRenderTimeMs() {
        return lastRenderTimeMs;
    }

    public String getHash() {
        return hash;
    }

    public synchronized void close() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getTextureManager() != null && textureId != null) {
            try {
                mc.getTextureManager().release(textureId);
            } catch (Exception ignored) {}
        }
        if (runtimeTexture != null) {
            try {
                runtimeTexture.close();
            } catch (Exception ignored) {}
            runtimeTexture = null;
        }
    }
}
