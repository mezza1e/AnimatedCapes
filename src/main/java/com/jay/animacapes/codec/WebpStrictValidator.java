package com.jay.animacapes.codec;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

/**
 * Strict Security & Geometry Whitelist for WebP Files.
 * Ensures only authentic, compliant, safe Animated/Static WebP files are loaded.
 */
public class WebpStrictValidator {

    // Whitelist of allowed WebP chunk FourCC identifiers
    private static final Set<String> ALLOWED_CHUNKS = Set.of(
        "VP8X", // Extended features (animation, alpha, canvas size)
        "ANIM", // Animation control (loop count, background)
        "ANMF", // Animation frame
        "VP8L", // Lossless image data
        "VP8 ", // Lossy image data
        "ALPH", // Alpha channel data
        "ICCP"  // Color management profile
    );

    // Dimension bounds for Minecraft capes
    public static final int MIN_WIDTH = 16;
    public static final int MAX_WIDTH = 2048;
    public static final int MIN_HEIGHT = 16;
    public static final int MAX_HEIGHT = 2048;

    public static final int MAX_FRAMES = 600;

    /**
     * Validates that the file strictly conforms to the WebP specification
     * and passes the Minecraft Cape whitelist.
     */
    public static void validate(File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            throw new IOException("WebP file does not exist");
        }

        long fileLen = file.length();
        if (fileLen < 12) {
            throw new IOException("WebP file is truncated (under 12 bytes)");
        }
        if (fileLen > AnimationDecoder.MAX_WEBP_BYTES) {
            throw new IOException("WebP exceeds 20MB whitelist limit (" + (fileLen / (1024 * 1024)) + "MB)");
        }

        byte[] header = new byte[12];
        try (FileInputStream fis = new FileInputStream(file)) {
            int read = fis.read(header);
            if (read < 12) throw new IOException("Could not read WebP header");
        }

        // 1. Strict Magic Bytes Whitelist: 'RIFF' .... 'WEBP'
        if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
            throw new IOException("Security rejection: Not a valid RIFF container");
        }
        if (header[8] != 'W' || header[9] != 'E' || header[10] != 'B' || header[11] != 'P') {
            throw new IOException("Security rejection: Header is not WEBP");
        }

        // Read complete file for chunk & dimension whitelist verification
        byte[] bytes = Files.readAllBytes(file.toPath());
        int pos = 12;
        int frameCount = 0;
        int canvasWidth = 0;
        int canvasHeight = 0;
        boolean hasVp8x = false;

        while (pos + 8 <= bytes.length) {
            String tag = new String(bytes, pos, 4);
            int chunkSize = (bytes[pos + 4] & 0xFF) |
                            ((bytes[pos + 5] & 0xFF) << 8) |
                            ((bytes[pos + 6] & 0xFF) << 16) |
                            ((bytes[pos + 7] & 0xFF) << 24);

            if (chunkSize < 0 || pos + 8 + chunkSize > bytes.length + 1) {
                throw new IOException("Security rejection: Corrupted or malicious chunk size in WebP");
            }

            // 2. Strict Chunk Whitelist
            if (!ALLOWED_CHUNKS.contains(tag)) {
                throw new IOException("Security rejection: Non-whitelisted WebP chunk '" + tag + "' detected");
            }

            int payloadStart = pos + 8;

            if ("VP8X".equals(tag)) {
                hasVp8x = true;
                if (chunkSize >= 10) {
                    canvasWidth = 1 + ((bytes[payloadStart + 4] & 0xFF) |
                                      ((bytes[payloadStart + 5] & 0xFF) << 8) |
                                      ((bytes[payloadStart + 6] & 0xFF) << 16));
                    canvasHeight = 1 + ((bytes[payloadStart + 7] & 0xFF) |
                                       ((bytes[payloadStart + 8] & 0xFF) << 8) |
                                       ((bytes[payloadStart + 9] & 0xFF) << 16));
                }
            } else if ("ANMF".equals(tag)) {
                frameCount++;
                if (frameCount > MAX_FRAMES) {
                    throw new IOException("Security rejection: Frame count (" + frameCount + ") exceeds whitelist limit of " + MAX_FRAMES);
                }
            } else if (!hasVp8x && "VP8L".equals(tag) && chunkSize >= 5) {
                // Static lossless WebP dimensions
                int b1 = bytes[payloadStart + 1] & 0xFF;
                int b2 = bytes[payloadStart + 2] & 0xFF;
                int b3 = bytes[payloadStart + 3] & 0xFF;
                int b4 = bytes[payloadStart + 4] & 0xFF;
                canvasWidth = 1 + (((b2 & 0x3F) << 8) | b1);
                canvasHeight = 1 + (((b4 & 0x0F) << 10) | (b3 << 2) | ((b2 & 0xC0) >> 6));
            }

            int pad = chunkSize % 2;
            pos += 8 + chunkSize + pad;
        }

        // 3. Strict Dimension Whitelist
        if (canvasWidth > 0 && canvasHeight > 0) {
            if (canvasWidth < MIN_WIDTH || canvasWidth > MAX_WIDTH ||
                canvasHeight < MIN_HEIGHT || canvasHeight > MAX_HEIGHT) {
                throw new IOException("Dimension rejection: WebP size (" + canvasWidth + "x" + canvasHeight +
                                      ") is outside allowed bounds (" + MIN_WIDTH + "x" + MIN_HEIGHT + " to " +
                                      MAX_WIDTH + "x" + MAX_HEIGHT + ")");
            }
        }
    }
}
