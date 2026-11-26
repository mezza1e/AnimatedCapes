package com.jay.animacapes.codec;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * High-performance animation decoder with strict format whitelisting.
 * Supported formats:
 *   - .webp (up to 5MB for non-whitelisted players, 20MB for whitelisted/admin accounts)
 *   - .gif  (up to 10MB, animated, auto-optimized to WebP)
 *   - .png, .jpg, .jpeg (up to 5MB, static only, APNG strictly rejected, losslessly optimized to WebP)
 */
public class AnimationDecoder {
    public static final long MAX_GIF_BYTES         = 10L * 1024L * 1024L;  // 10 MB
    public static final long MAX_WEBP_BYTES        = 20L * 1024L * 1024L;  // 20 MB (Whitelisted / Admin / Remote ceiling)
    public static final long MAX_WEBP_PUBLIC_BYTES = 5L * 1024L * 1024L;   // 5 MB  (Non-whitelisted players)
    public static final long MAX_STATIC_BYTES      = 5L * 1024L * 1024L;   // 5 MB  (PNG, JPG, JPEG)

    public static boolean isSupportedFormat(String fileName) {
        if (fileName == null) return false;
        String name = fileName.toLowerCase();
        return name.endsWith(".webp") || name.endsWith(".gif") ||
               name.endsWith(".png")  || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    public static long getMaxAllowedBytes(String fileName) {
        return getMaxAllowedBytes(fileName, com.jay.animacapes.gui.GalleryFolder.isBypassLimit());
    }

    public static long getMaxAllowedBytes(String fileName, boolean isWhitelisted) {
        if (fileName == null) return 0L;
        String name = fileName.toLowerCase();
        if (name.endsWith(".webp")) return isWhitelisted ? MAX_WEBP_BYTES : MAX_WEBP_PUBLIC_BYTES;
        if (name.endsWith(".gif")) return MAX_GIF_BYTES;
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) return MAX_STATIC_BYTES;
        return 0L;
    }

    /**
     * Inspects a PNG file to determine if it contains animated PNG (APNG) chunks.
     * APNG files contain 'acTL' (Animation Control) and 'fcTL' (Frame Control) chunks.
     */
    public static boolean isApng(File file) {
        if (file == null || !file.exists() || file.length() < 8) return false;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[8];
            int read = fis.read(header);
            if (read < 8) return false;
            // Verify PNG magic bytes: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
            if ((header[0] & 0xFF) != 0x89 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G' ||
                header[4] != '\r' || header[5] != '\n' || header[6] != 0x1A || header[7] != '\n') {
                return false;
            }

            byte[] chunkHeader = new byte[8]; // 4 bytes length, 4 bytes FourCC
            while (fis.read(chunkHeader) == 8) {
                int length = ((chunkHeader[0] & 0xFF) << 24) |
                             ((chunkHeader[1] & 0xFF) << 16) |
                             ((chunkHeader[2] & 0xFF) << 8)  |
                             (chunkHeader[3] & 0xFF);
                String tag = new String(chunkHeader, 4, 4);

                if ("acTL".equals(tag) || "fcTL".equals(tag)) {
                    return true; // Found APNG animation control chunk!
                }
                if ("IEND".equals(tag)) {
                    break;
                }

                if (length < 0) break;
                // Skip payload + 4 bytes CRC
                long toSkip = length + 4L;
                long skipped = fis.skip(toSkip);
                while (skipped < toSkip) {
                    long s = fis.skip(toSkip - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void validateFileSize(File file) throws IOException {
        validateFileSize(file, false);
    }

    public static void validateFileSize(File file, boolean allowWhitelistedMax) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            throw new IOException("File does not exist");
        }
        String name = file.getName().toLowerCase();
        if (!isSupportedFormat(name)) {
            throw new IOException("Unsupported format: Only .webp, .gif, .png, .jpg, and .jpeg files are supported.");
        }

        long max = getMaxAllowedBytes(name, allowWhitelistedMax || com.jay.animacapes.gui.GalleryFolder.isBypassLimit());
        long size = file.length();
        if (size > max) {
            long maxMb = max / (1024 * 1024);
            long fileMb = (size + 1024 * 1024 - 1) / (1024 * 1024);
            throw new IOException("File too large (" + fileMb + "MB). Max limit is " + maxMb + "MB for this format.");
        }
    }

    public static DecodedAnimation decode(File file) throws IOException {
        return decode(file, false);
    }

    public static DecodedAnimation decodeRemote(File file) throws IOException {
        return decode(file, true);
    }

    public static DecodedAnimation decode(File file, boolean allowWhitelistedMax) throws IOException {
        validateFileSize(file, allowWhitelistedMax);

        String name = file.getName().toLowerCase();
        if (name.endsWith(".webp")) {
            // Strict WebP container and geometry whitelist validation
            WebpStrictValidator.validate(file);
            return NativeCodecBridge.decodeWebp(file);
        } else if (name.endsWith(".gif")) {
            return GifDecoder.decode(file);
        } else if (name.endsWith(".png")) {
            if (isApng(file)) {
                throw new IOException("Security rejection: Animated PNG (APNG) is not permitted. Only static PNG files are allowed.");
            }
            return NativeCodecBridge.decodeStaticFallback(file);
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return NativeCodecBridge.decodeStaticFallback(file);
        } else {
            throw new IOException("Unsupported format: Only .webp, .gif, and static .png/.jpg/.jpeg files are allowed.");
        }
    }
}
