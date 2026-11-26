package com.jay.animacapes.codec;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GifDecoder {
    private static final int DISPOSAL_UNSPECIFIED = 0;
    private static final int DISPOSAL_NONE = 1;
    private static final int DISPOSAL_RESTORE_BG = 2;
    private static final int DISPOSAL_RESTORE_PREV = 3;

    private static final int MAX_STACK_SIZE = 4096;

    public static DecodedAnimation decode(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return decode(in);
        }
    }

    public static DecodedAnimation decode(byte[] data) throws IOException {
        return decode(new ByteArrayInputStream(data));
    }

    public static DecodedAnimation decode(InputStream in) throws IOException {
        // Read signature
        String sig = readString(in, 6);
        if (!sig.startsWith("GIF")) {
            throw new IOException("Not a valid GIF file: " + sig);
        }

        int width = readShort(in);
        int height = readShort(in);
        int packed = in.read();
        boolean gctFlag = (packed & 0x80) != 0;
        int gctSize = 2 << (packed & 7);
        int bgIndex = in.read();
        in.read(); // pixel aspect ratio

        int[] gct = null;
        if (gctFlag) {
            gct = readColorTable(in, gctSize);
        }

        List<DecodedFrame> frames = new ArrayList<>();
        int[] currentCanvas = new int[width * height];
        int[] previousCanvas = new int[width * height];

        // Frame state
        int delayHundredths = 10;
        int disposalMethod = DISPOSAL_UNSPECIFIED;
        boolean transparency = false;
        int transIndex = 0;
        int loopCount = 0;

        int block;
        while ((block = in.read()) != -1) {
            if (block == 0x3B) { // Trailer (end of GIF)
                break;
            }

            if (block == 0x21) { // Extension
                int extCode = in.read();
                if (extCode == 0xF9) { // Graphic Control Extension
                    in.read(); // block size (4)
                    int extPacked = in.read();
                    disposalMethod = (extPacked & 0x1C) >> 2;
                    transparency = (extPacked & 1) != 0;
                    delayHundredths = readShort(in);
                    transIndex = in.read();
                    in.read(); // block terminator (0)
                } else if (extCode == 0xFF) { // Application Extension (Netscape loop count)
                    int blockSize = in.read();
                    String app = readString(in, blockSize);
                    if ("NETSCAPE2.0".equals(app) || "ANIMEXTS1.0".equals(app)) {
                        int subBlock = in.read();
                        if (subBlock == 3) {
                            in.read(); // 1
                            loopCount = readShort(in);
                            in.read(); // terminator
                        } else {
                            skipSubBlocks(in);
                        }
                    } else {
                        skipSubBlocks(in);
                    }
                } else {
                    skipSubBlocks(in);
                }
            } else if (block == 0x2C) { // Image Descriptor
                int ix = readShort(in);
                int iy = readShort(in);
                int iw = readShort(in);
                int ih = readShort(in);
                int ipacked = in.read();
                boolean lctFlag = (ipacked & 0x80) != 0;
                boolean interlace = (ipacked & 0x40) != 0;
                int lctSize = 2 << (ipacked & 7);

                int[] act = lctFlag ? readColorTable(in, lctSize) : gct;
                if (act == null) {
                    act = new int[256];
                }

                // Backup canvas before rendering if disposal == restore previous
                if (disposalMethod == DISPOSAL_RESTORE_PREV) {
                    System.arraycopy(currentCanvas, 0, previousCanvas, 0, currentCanvas.length);
                }

                // Decode LZW pixels
                byte[] pixels = decodeLZW(in, iw, ih);

                // Paint frame pixels onto canvas
                int line = 0;
                int pass = 1;
                int inc = 8;

                for (int y = 0; y < ih; y++) {
                    int dy = y;
                    if (interlace) {
                        if (line >= ih) {
                            pass++;
                            switch (pass) {
                                case 2 -> { line = 4; inc = 8; }
                                case 3 -> { line = 2; inc = 4; }
                                case 4 -> { line = 1; inc = 2; }
                            }
                        }
                        dy = line;
                        line += inc;
                    }

                    int canvasY = iy + dy;
                    if (canvasY < 0 || canvasY >= height) continue;

                    for (int x = 0; x < iw; x++) {
                        int canvasX = ix + x;
                        if (canvasX < 0 || canvasX >= width) continue;

                        int colorIndex = pixels[y * iw + x] & 0xFF;
                        if (!transparency || colorIndex != transIndex) {
                            int rgb = (colorIndex < act.length) ? act[colorIndex] : 0;
                            currentCanvas[canvasY * width + canvasX] = 0xFF000000 | rgb;
                        }
                    }
                }

                // Duration in milliseconds: <= 10ms or 0ms defaults to 100ms per browser/GIF standards
                int durationMs = delayHundredths * 10;
                if (durationMs <= 10) {
                    durationMs = 100;
                }

                // Export current canvas snapshot
                int[] frameCopy = Arrays.copyOf(currentCanvas, currentCanvas.length);
                frames.add(new DecodedFrame(width, height, frameCopy, durationMs));

                // Apply disposal method for next frame
                if (disposalMethod == DISPOSAL_RESTORE_BG) {
                    for (int y = 0; y < ih; y++) {
                        int cy = iy + y;
                        if (cy < 0 || cy >= height) continue;
                        for (int x = 0; x < iw; x++) {
                            int cx = ix + x;
                            if (cx < 0 || cx >= width) continue;
                            currentCanvas[cy * width + cx] = 0; // Transparent
                        }
                    }
                } else if (disposalMethod == DISPOSAL_RESTORE_PREV) {
                    System.arraycopy(previousCanvas, 0, currentCanvas, 0, currentCanvas.length);
                }

                // Reset per-frame flags
                disposalMethod = DISPOSAL_UNSPECIFIED;
                transparency = false;
                delayHundredths = 10;
            }
        }

        if (frames.isEmpty()) {
            throw new IOException("GIF contained no readable frames");
        }

        return new DecodedAnimation(frames, width, height, loopCount);
    }

    private static byte[] decodeLZW(InputStream in, int width, int height) throws IOException {
        int nullCode = -1;
        int npix = width * height;
        byte[] pixels = new byte[npix];

        int minCodeSize = in.read();
        int clearCode = 1 << minCodeSize;
        int eoiCode = clearCode + 1;
        int available = clearCode + 2;
        int oldCode = nullCode;
        int codeSize = minCodeSize + 1;
        int codeMask = (1 << codeSize) - 1;

        int[] prefix = new int[MAX_STACK_SIZE];
        byte[] suffix = new byte[MAX_STACK_SIZE];
        byte[] pixelStack = new byte[MAX_STACK_SIZE + 1];

        for (int i = 0; i < clearCode; i++) {
            prefix[i] = 0;
            suffix[i] = (byte) i;
        }

        // State variables for bit reading
        int datum = 0;
        int bits = 0;
        int count = 0;
        int bi = 0;
        int top = 0;
        byte[] block = new byte[256];
        int first = 0;
        int pi = 0;

        while (pi < npix) {
            if (top == 0) {
                if (bits < codeSize) {
                    if (count == 0) {
                        count = in.read();
                        if (count <= 0) break;
                        readFully(in, block, count);
                        bi = 0;
                    }
                    datum += (block[bi++] & 0xFF) << bits;
                    bits += 8;
                    count--;
                    continue;
                }

                int code = datum & codeMask;
                datum >>= codeSize;
                bits -= codeSize;

                if (code > available || code == eoiCode) {
                    break;
                }
                if (code == clearCode) {
                    codeSize = minCodeSize + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = clearCode + 2;
                    oldCode = nullCode;
                    continue;
                }
                if (oldCode == nullCode) {
                    pixelStack[top++] = suffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }

                int inCode = code;
                if (code == available) {
                    pixelStack[top++] = (byte) first;
                    code = oldCode;
                }
                while (code > clearCode) {
                    pixelStack[top++] = suffix[code];
                    code = prefix[code];
                }
                first = suffix[code] & 0xFF;
                pixelStack[top++] = (byte) first;

                if (available < MAX_STACK_SIZE) {
                    prefix[available] = oldCode;
                    suffix[available] = (byte) first;
                    available++;
                    if ((available & codeMask) == 0 && available < MAX_STACK_SIZE) {
                        codeSize++;
                        codeMask += available;
                    }
                }
                oldCode = inCode;
            }

            top--;
            pixels[pi++] = pixelStack[top];
        }

        // Fill remaining pixels with 0 if truncated
        while (pi < npix) {
            pixels[pi++] = 0;
        }

        skipSubBlocks(in);
        return pixels;
    }

    private static int[] readColorTable(InputStream in, int nColors) throws IOException {
        int nBytes = 3 * nColors;
        byte[] buf = new byte[nBytes];
        readFully(in, buf, nBytes);
        int[] tab = new int[nColors];
        int i = 0;
        int j = 0;
        while (i < nColors) {
            int r = buf[j++] & 0xFF;
            int g = buf[j++] & 0xFF;
            int b = buf[j++] & 0xFF;
            tab[i++] = (r << 16) | (g << 8) | b;
        }
        return tab;
    }

    private static void skipSubBlocks(InputStream in) throws IOException {
        int len;
        while ((len = in.read()) > 0) {
            long skipped = in.skip(len);
            while (skipped < len) {
                long s = in.skip(len - skipped);
                if (s <= 0) {
                    in.read();
                    skipped++;
                } else {
                    skipped += s;
                }
            }
        }
    }

    private static void readFully(InputStream in, byte[] b, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int count = in.read(b, total, len - total);
            if (count < 0) break;
            total += count;
        }
    }

    private static short readShort(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        return (short) (b1 | (b2 << 8));
    }

    private static String readString(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        readFully(in, b, len);
        return new String(b);
    }
}
