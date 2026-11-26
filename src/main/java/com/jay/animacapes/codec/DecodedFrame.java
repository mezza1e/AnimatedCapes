package com.jay.animacapes.codec;

import com.mojang.blaze3d.platform.NativeImage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public class DecodedFrame {
    public static final int SCALE = 32;
    public static final int TARGET_W = 64 * SCALE; // 2048
    public static final int TARGET_H = 32 * SCALE; // 1024 (2:1 aspect ratio)

    public static final int FACE_W = 10 * SCALE;  // 320
    public static final int FACE_H = 16 * SCALE;  // 512

    public static final int BACK_X = 1 * SCALE;   // 32
    public static final int BACK_Y = 1 * SCALE;   // 32

    public static final int FRONT_X = 12 * SCALE; // 384
    public static final int FRONT_Y = 1 * SCALE;  // 32

    private final int width;
    private final int height;
    private final int[] pixelsArgb;
    private final int durationMs;

    private volatile int[] cachedSlotPixels;
    private volatile String cachedFitMode;

    public DecodedFrame(int width, int height, int[] pixelsArgb, int durationMs) {
        this.width = width;
        this.height = height;
        this.pixelsArgb = pixelsArgb;
        this.durationMs = Math.max(10, durationMs);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int[] getPixelsArgb() {
        return pixelsArgb;
    }

    public int getDurationMs() {
        return durationMs;
    }

    /**
     * Pre-rasterizes this frame into a full-bleed 320x512 slot using high quality
     * bicubic interpolation, trimming transparent sticker margins and flattening alpha.
     */
    public synchronized int[] getOrRasterizeSlotPixels(String fitMode) {
        String effectiveFit = (fitMode != null) ? fitMode : "COVER";
        if (cachedSlotPixels != null && effectiveFit.equalsIgnoreCase(cachedFitMode)) {
            return cachedSlotPixels;
        }

        // 1. Calculate active bounding box of non-transparent content
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y++) {
            int rowOff = y * width;
            for (int x = 0; x < width; x++) {
                int a = (pixelsArgb[rowOff + x] >>> 24) & 0xFF;
                if (a > 16) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        int contentX, contentY, contentW, contentH;
        if (maxX >= minX && maxY >= minY) {
            contentX = minX;
            contentY = minY;
            contentW = maxX - minX + 1;
            contentH = maxY - minY + 1;
        } else {
            contentX = 0;
            contentY = 0;
            contentW = width;
            contentH = height;
        }

        // 2. Extract cropped content into BufferedImage
        BufferedImage srcImg = new BufferedImage(contentW, contentH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < contentH; y++) {
            int srcRow = (contentY + y) * width + contentX;
            for (int x = 0; x < contentW; x++) {
                srcImg.setRGB(x, y, pixelsArgb[srcRow + x]);
            }
        }

        // 3. Scale and fit to field the entire 320x512 canvas
        BufferedImage slotImg = new BufferedImage(FACE_W, FACE_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = slotImg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Clean white background so sticker artwork pops without black borders
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, FACE_W, FACE_H);

        double scale;
        if ("CONTAIN".equalsIgnoreCase(fitMode)) {
            scale = Math.min((double) FACE_W / contentW, (double) FACE_H / contentH);
        } else if ("STRETCH".equalsIgnoreCase(effectiveFit)) {
            g.drawImage(srcImg, 0, 0, FACE_W, FACE_H, null);
            g.dispose();
            cachedSlotPixels = finalizeSlot(slotImg);
            cachedFitMode = effectiveFit;
            return cachedSlotPixels;
        } else {
            // "COVER" (default) - Fields the entire canvas edge-to-edge
            scale = Math.max((double) FACE_W / contentW, (double) FACE_H / contentH);
        }

        int drawW = (int) Math.ceil(contentW * scale);
        int drawH = (int) Math.ceil(contentH * scale);
        int drawX = (FACE_W - drawW) / 2;
        int drawY = (FACE_H - drawH) / 2;

        g.drawImage(srcImg, drawX, drawY, drawW, drawH, null);
        g.dispose();

        cachedSlotPixels = finalizeSlot(slotImg);
        cachedFitMode = effectiveFit;
        return cachedSlotPixels;
    }

    private static int[] finalizeSlot(BufferedImage img) {
        int[] raw = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int[] result = new int[FACE_W * FACE_H];
        for (int i = 0; i < result.length; i++) {
            int argb = raw[i];
            int a = (argb >>> 24) & 0xFF;
            if (a < 255) {
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                r = (r * a + 255 * (255 - a)) / 255;
                g = (g * a + 255 * (255 - a)) / 255;
                b = (b * a + 255 * (255 - a)) / 255;
                argb = 0xFF000000 | (r << 16) | (g << 8) | b;
            } else {
                argb |= 0xFF000000;
            }
            result[i] = argb;
        }
        return result;
    }

    /**
     * Stamps this frame's 320x512 slot onto an existing 2048x1024 NativeImage.
     */
    public void stampOntoCapeImage(NativeImage cape, String fitMode) {
        int[] slot = getOrRasterizeSlotPixels(fitMode);

        // Back Face (UV: 1, 1, 10, 16) -> (32, 32, 320, 512)
        stampRect(cape, slot, BACK_X, BACK_Y, FACE_W, FACE_H);

        // Front Face (UV: 12, 1, 10, 16) -> (384, 32, 320, 512)
        stampRect(cape, slot, FRONT_X, FRONT_Y, FACE_W, FACE_H);

        // Top edge: (1, 0, 10, 1) -> (32, 0, 320, 32)
        stampEdgeHorizontal(cape, slot, 0, BACK_X, 0, FACE_W, SCALE);

        // Bottom edge: (11, 0, 10, 1) -> (352, 0, 320, 32)
        stampEdgeHorizontal(cape, slot, FACE_H - 1, 11 * SCALE, 0, FACE_W, SCALE);

        // Right side edge: (0, 1, 1, 16) -> (0, 32, 32, 512)
        stampEdgeVertical(cape, slot, 0, 0, BACK_Y, SCALE, FACE_H);

        // Left side edge: (11, 1, 1, 16) -> (352, 32, 32, 512)
        stampEdgeVertical(cape, slot, FACE_W - 1, 11 * SCALE, BACK_Y, SCALE, FACE_H);
    }

    /**
     * Converts this frame into a complete 2048x1024 NativeImage.
     */
    public NativeImage toCapeNativeImage(String fitMode) {
        NativeImage cape = new NativeImage(NativeImage.Format.RGBA, TARGET_W, TARGET_H, true);
        cape.fillRect(0, 0, TARGET_W, TARGET_H, 0xFFFFFFFF);
        stampOntoCapeImage(cape, fitMode);
        return cape;
    }

    private static void stampRect(NativeImage dest, int[] src, int dstX, int dstY, int w, int h) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                dest.setPixel(dstX + x, dstY + y, src[row + x]);
            }
        }
    }

    private static void stampEdgeHorizontal(NativeImage dest, int[] src, int srcY, int dstX, int dstY, int w, int h) {
        int srcRow = srcY * w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dest.setPixel(dstX + x, dstY + y, src[srcRow + x]);
            }
        }
    }

    private static void stampEdgeVertical(NativeImage dest, int[] src, int srcX, int dstX, int dstY, int w, int h) {
        for (int y = 0; y < h; y++) {
            int color = src[y * FACE_W + srcX];
            for (int x = 0; x < w; x++) {
                dest.setPixel(dstX + x, dstY + y, color);
            }
        }
    }
}
