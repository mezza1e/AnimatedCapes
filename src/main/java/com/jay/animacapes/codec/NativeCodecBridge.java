package com.jay.animacapes.codec;

import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NativeCodecBridge {
    private static boolean nativesLoaded = false;
    private static MethodHandle fnWebpGetInfo;
    private static MethodHandle fnWebpDecodeRGBA;
    private static MethodHandle fnWebpEncodeLosslessRGBA;
    private static MethodHandle fnWebpFree;

    private static final String SHARPYUV_NAME = "libsharpyuv-25eb0c4836aeeccd9b98aeb9ee77a148.dll";
    private static final String WEBP_NAME = "libwebp-e5d4f9543eae6d381f6212f006b75bdc.dll";
    private static final String WEBPMUX_NAME = "libwebpmux-c7c66c64054d42d6a25d9c92afeb2d3f.dll";
    private static final String DAV1D_NAME = "libdav1d-d92ea30c3d9c9934f860c34d1ba70766.dll";

    public static synchronized void init() {
        if (nativesLoaded) return;
        try {
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

            File baseDir = new File(gameDir, "animacapes/natives");
            if (!baseDir.exists()) {
                baseDir.mkdirs();
            }

            // Extract in dependency order
            File sharpyuvDll = extractNative(SHARPYUV_NAME, baseDir);
            File webpDll = extractNative(WEBP_NAME, baseDir);
            File webpmuxDll = extractNative(WEBPMUX_NAME, baseDir);
            File dav1dDll = extractNative(DAV1D_NAME, baseDir);

            // Load in dependency order
            if (sharpyuvDll != null && sharpyuvDll.exists()) {
                System.load(sharpyuvDll.getAbsolutePath());
            }
            if (webpDll != null && webpDll.exists()) {
                System.load(webpDll.getAbsolutePath());
            }
            if (webpmuxDll != null && webpmuxDll.exists()) {
                System.load(webpmuxDll.getAbsolutePath());
            }
            if (dav1dDll != null && dav1dDll.exists()) {
                System.load(dav1dDll.getAbsolutePath());
            }

            // Bind libwebp functions via Java 25 Foreign Function & Memory API
            if (webpDll != null && webpDll.exists()) {
                SymbolLookup lookup = SymbolLookup.libraryLookup(webpDll.toPath(), Arena.global());
                Linker linker = Linker.nativeLinker();

                // int WebPGetInfo(const uint8_t* data, size_t data_size, int* width, int* height)
                lookup.find("WebPGetInfo").ifPresent(sym ->
                    fnWebpGetInfo = linker.downcallHandle(sym, FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS
                    ))
                );

                // uint8_t* WebPDecodeRGBA(const uint8_t* data, size_t data_size, int* width, int* height)
                lookup.find("WebPDecodeRGBA").ifPresent(sym ->
                    fnWebpDecodeRGBA = linker.downcallHandle(sym, FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS
                    ))
                );

                // void WebPFree(void* ptr)
                lookup.find("WebPFree").ifPresent(sym ->
                    fnWebpFree = linker.downcallHandle(sym, FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS
                    ))
                );

                // size_t WebPEncodeLosslessRGBA(const uint8_t* rgba, int width, int height, int stride, uint8_t** output)
                lookup.find("WebPEncodeLosslessRGBA").ifPresent(sym ->
                    fnWebpEncodeLosslessRGBA = linker.downcallHandle(sym, FunctionDescriptor.of(
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS
                    ))
                );
            }

            nativesLoaded = true;
            System.out.println("[AnimaCapes] Native codecs initialized successfully.");
        } catch (Throwable t) {
            System.err.println("[AnimaCapes] Native codecs initialization note: " + t.getMessage());
        }
    }

    private static File extractNative(String name, File targetDir) {
        String resourcePath = "/assets/animacapes/natives/windows-x64/" + name;
        File targetFile = new File(targetDir, name);
        try (InputStream in = NativeCodecBridge.class.getResourceAsStream(resourcePath)) {
            if (in == null) return targetFile;
            try (FileOutputStream out = new FileOutputStream(targetFile)) {
                in.transferTo(out);
            }
            return targetFile;
        } catch (Exception e) {
            return targetFile;
        }
    }

    public static boolean isNativeWebpAvailable() {
        return fnWebpDecodeRGBA != null;
    }

    public static byte[] encodeLosslessFrame(byte[] rgbaBytes, int width, int height) {
        init();
        if (fnWebpEncodeLosslessRGBA == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rgbaSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, rgbaBytes);
            MemorySegment outPtrSeg = arena.allocate(ValueLayout.ADDRESS);
            long size = (long) fnWebpEncodeLosslessRGBA.invokeExact(rgbaSeg, width, height, width * 4, outPtrSeg);
            if (size <= 0) return null;
            MemorySegment outSeg = outPtrSeg.get(ValueLayout.ADDRESS, 0).reinterpret(size);
            byte[] encoded = outSeg.toArray(ValueLayout.JAVA_BYTE);
            if (fnWebpFree != null) {
                fnWebpFree.invokeExact(outSeg);
            }
            if (encoded.length >= 20 && encoded[12] == 'V' && encoded[13] == 'P' && encoded[14] == '8' && encoded[15] == 'L') {
                int vp8lPayloadSize = (encoded[16] & 0xFF) | ((encoded[17] & 0xFF) << 8) | ((encoded[18] & 0xFF) << 16) | ((encoded[19] & 0xFF) << 24);
                int totalChunkLen = 8 + vp8lPayloadSize + (vp8lPayloadSize % 2);
                byte[] vp8l = new byte[totalChunkLen];
                System.arraycopy(encoded, 12, vp8l, 0, Math.min(totalChunkLen, encoded.length - 12));
                return vp8l;
            }
            return encoded;
        } catch (Throwable t) {
            return null;
        }
    }

    public static byte[] encodeCompleteLosslessWebp(byte[] rgbaBytes, int width, int height) {
        init();
        if (fnWebpEncodeLosslessRGBA == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rgbaSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, rgbaBytes);
            MemorySegment outPtrSeg = arena.allocate(ValueLayout.ADDRESS);
            long size = (long) fnWebpEncodeLosslessRGBA.invokeExact(rgbaSeg, width, height, width * 4, outPtrSeg);
            if (size <= 0) return null;
            MemorySegment outSeg = outPtrSeg.get(ValueLayout.ADDRESS, 0).reinterpret(size);
            byte[] encoded = outSeg.toArray(ValueLayout.JAVA_BYTE);
            if (fnWebpFree != null) {
                fnWebpFree.invokeExact(outSeg);
            }
            return encoded;
        } catch (Throwable t) {
            return null;
        }
    }

    public static DecodedAnimation decodeWebp(File file) throws IOException {
        init();
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        if (fileBytes.length < 12) {
            throw new IOException("Invalid WebP file: truncated header");
        }

        // Verify RIFF & WEBP signature
        if (fileBytes[0] != 'R' || fileBytes[1] != 'I' || fileBytes[2] != 'F' || fileBytes[3] != 'F' ||
            fileBytes[8] != 'W' || fileBytes[9] != 'E' || fileBytes[10] != 'B' || fileBytes[11] != 'P') {
            throw new IOException("Not a valid RIFF/WEBP file");
        }

        if (isNativeWebpAvailable()) {
            try {
                // Check if file is Animated WebP (contains ANIM / ANMF chunks)
                List<DecodedFrame> frames = new ArrayList<>();
                int canvasW = 0;
                int canvasH = 0;
                int loopCount = 0;
                int[] canvasPixels = null;

                int pos = 12;
                while (pos + 8 <= fileBytes.length) {
                    String tag = new String(fileBytes, pos, 4);
                    int chunkSize = (fileBytes[pos + 4] & 0xFF) |
                                    ((fileBytes[pos + 5] & 0xFF) << 8) |
                                    ((fileBytes[pos + 6] & 0xFF) << 16) |
                                    ((fileBytes[pos + 7] & 0xFF) << 24);
                    int payloadStart = pos + 8;
                    int pad = chunkSize % 2;
                    pos += 8 + chunkSize + pad;

                    if ("VP8X".equals(tag) && chunkSize >= 10) {
                        canvasW = 1 + ((fileBytes[payloadStart + 4] & 0xFF) |
                                      ((fileBytes[payloadStart + 5] & 0xFF) << 8) |
                                      ((fileBytes[payloadStart + 6] & 0xFF) << 16));
                        canvasH = 1 + ((fileBytes[payloadStart + 7] & 0xFF) |
                                      ((fileBytes[payloadStart + 8] & 0xFF) << 8) |
                                      ((fileBytes[payloadStart + 9] & 0xFF) << 16));
                    } else if ("ANIM".equals(tag) && chunkSize >= 6) {
                        loopCount = (fileBytes[payloadStart + 4] & 0xFF) |
                                    ((fileBytes[payloadStart + 5] & 0xFF) << 8);
                    } else if ("ANMF".equals(tag) && chunkSize >= 16) {
                        int frameX = ((fileBytes[payloadStart] & 0xFF) |
                                      ((fileBytes[payloadStart + 1] & 0xFF) << 8) |
                                      ((fileBytes[payloadStart + 2] & 0xFF) << 16)) * 2;
                        int frameY = ((fileBytes[payloadStart + 3] & 0xFF) |
                                      ((fileBytes[payloadStart + 4] & 0xFF) << 8) |
                                      ((fileBytes[payloadStart + 5] & 0xFF) << 16)) * 2;
                        int frameW = 1 + ((fileBytes[payloadStart + 6] & 0xFF) |
                                          ((fileBytes[payloadStart + 7] & 0xFF) << 8) |
                                          ((fileBytes[payloadStart + 8] & 0xFF) << 16));
                        int frameH = 1 + ((fileBytes[payloadStart + 9] & 0xFF) |
                                          ((fileBytes[payloadStart + 10] & 0xFF) << 8) |
                                          ((fileBytes[payloadStart + 11] & 0xFF) << 16));
                        int duration = (fileBytes[payloadStart + 12] & 0xFF) |
                                       ((fileBytes[payloadStart + 13] & 0xFF) << 8) |
                                       ((fileBytes[payloadStart + 14] & 0xFF) << 16);
                        int flags = fileBytes[payloadStart + 15] & 0xFF;
                        if (duration <= 0) duration = 33;

                        int subPayloadSize = chunkSize - 16;
                        byte[] subData = new byte[subPayloadSize];
                        System.arraycopy(fileBytes, payloadStart + 16, subData, 0, subPayloadSize);

                        boolean hasAlpha = subPayloadSize >= 4 &&
                            subData[0] == 'A' && subData[1] == 'L' && subData[2] == 'P' && subData[3] == 'H';

                        // Wrap sub-frame in valid VP8X mini-RIFF container
                        byte[] vp8xChunk = new byte[18];
                        vp8xChunk[0] = 'V'; vp8xChunk[1] = 'P'; vp8xChunk[2] = '8'; vp8xChunk[3] = 'X';
                        vp8xChunk[4] = 10; vp8xChunk[5] = 0; vp8xChunk[6] = 0; vp8xChunk[7] = 0;
                        vp8xChunk[8] = (byte)(hasAlpha ? 0x10 : 0x00);
                        vp8xChunk[9] = 0; vp8xChunk[10] = 0; vp8xChunk[11] = 0;
                        int wMinusOne = frameW - 1;
                        vp8xChunk[12] = (byte)(wMinusOne & 0xFF);
                        vp8xChunk[13] = (byte)((wMinusOne >> 8) & 0xFF);
                        vp8xChunk[14] = (byte)((wMinusOne >> 16) & 0xFF);
                        int hMinusOne = frameH - 1;
                        vp8xChunk[15] = (byte)(hMinusOne & 0xFF);
                        vp8xChunk[16] = (byte)((hMinusOne >> 8) & 0xFF);
                        vp8xChunk[17] = (byte)((hMinusOne >> 16) & 0xFF);

                        int totalRiffSize = 4 + 18 + subPayloadSize;
                        byte[] subRiff = new byte[8 + totalRiffSize];
                        subRiff[0] = 'R'; subRiff[1] = 'I'; subRiff[2] = 'F'; subRiff[3] = 'F';
                        subRiff[4] = (byte) (totalRiffSize & 0xFF);
                        subRiff[5] = (byte) ((totalRiffSize >> 8) & 0xFF);
                        subRiff[6] = (byte) ((totalRiffSize >> 16) & 0xFF);
                        subRiff[7] = (byte) ((totalRiffSize >> 24) & 0xFF);
                        subRiff[8] = 'W'; subRiff[9] = 'E'; subRiff[10] = 'B'; subRiff[11] = 'P';
                        System.arraycopy(vp8xChunk, 0, subRiff, 12, 18);
                        System.arraycopy(subData, 0, subRiff, 30, subPayloadSize);

                        int[] framePixels = decodeRgbaNative(subRiff, frameW, frameH);
                        if (framePixels != null) {
                            if (canvasW <= 0) canvasW = frameX + frameW;
                            if (canvasH <= 0) canvasH = frameY + frameH;
                            
                            // Composite into canvas
                            // Flags: bit 1 (0x02) = do not blend (overwrite)
                            //        bit 0 (0x01) = dispose to background
                            boolean noBlend = (flags & 2) != 0;
                            boolean disposeToBg = (flags & 1) != 0;
                            
                            // Allocate canvas if needed
                            if (canvasPixels == null || canvasPixels.length != canvasW * canvasH) {
                                canvasPixels = new int[canvasW * canvasH];
                            }
                            
                            for (int row = 0; row < frameH; row++) {
                                int cy = frameY + row;
                                if (cy < 0 || cy >= canvasH) continue;
                                for (int col = 0; col < frameW; col++) {
                                    int cx = frameX + col;
                                    if (cx < 0 || cx >= canvasW) continue;
                                    int srcPixel = framePixels[row * frameW + col];
                                    int cIdx = cy * canvasW + cx;
                                    if (noBlend) {
                                        canvasPixels[cIdx] = srcPixel;
                                    } else {
                                        int srcA = (srcPixel >>> 24) & 0xFF;
                                        if (srcA == 255) {
                                            canvasPixels[cIdx] = srcPixel;
                                        } else if (srcA > 0) {
                                            int dstPixel = canvasPixels[cIdx];
                                            int dstA = (dstPixel >>> 24) & 0xFF;
                                            int outA = srcA + dstA * (255 - srcA) / 255;
                                            if (outA > 0) {
                                                int srcR = (srcPixel >> 16) & 0xFF;
                                                int srcG = (srcPixel >> 8) & 0xFF;
                                                int srcB = srcPixel & 0xFF;
                                                int dstR = (dstPixel >> 16) & 0xFF;
                                                int dstG = (dstPixel >> 8) & 0xFF;
                                                int dstB = dstPixel & 0xFF;
                                                int outR = (srcR * srcA + dstR * dstA * (255 - srcA) / 255) / outA;
                                                int outG = (srcG * srcA + dstG * dstA * (255 - srcA) / 255) / outA;
                                                int outB = (srcB * srcA + dstB * dstA * (255 - srcA) / 255) / outA;
                                                canvasPixels[cIdx] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
                                            }
                                        }
                                    }
                                }
                            }
                            
                            frames.add(new DecodedFrame(canvasW, canvasH, canvasPixels.clone(), duration));
                            
                            if (disposeToBg) {
                                for (int row = 0; row < frameH; row++) {
                                    int cy = frameY + row;
                                    if (cy < 0 || cy >= canvasH) continue;
                                    for (int col = 0; col < frameW; col++) {
                                        int cx = frameX + col;
                                        if (cx >= 0 && cx < canvasW) {
                                            canvasPixels[cy * canvasW + cx] = 0;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (!frames.isEmpty()) {
                    if (canvasW <= 0 || canvasH <= 0) {
                        canvasW = frames.get(0).getWidth();
                        canvasH = frames.get(0).getHeight();
                    }
                    return new DecodedAnimation(frames, canvasW, canvasH, loopCount);
                }

                // If not animated (static WebP), decode full image directly
                DecodedFrame single = decodeRgbaNativeFrame(fileBytes);
                if (single != null) {
                    return DecodedAnimation.ofSingleFrame(single);
                }
            } catch (Throwable t) {
                System.err.println("[AnimaCapes] WebP decoding notice: " + t.getMessage());
            }
        }

        // Fallback: static image decode via ImageIO
        return decodeStaticFallback(file);
    }

    private static int[] decodeRgbaNative(byte[] webpBytes, int expectedW, int expectedH) {
        DecodedFrame f = decodeRgbaNativeFrame(webpBytes);
        return f != null ? f.getPixelsArgb() : null;
    }

    private static DecodedFrame decodeRgbaNativeFrame(byte[] webpBytes) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rawSeg = arena.allocate(webpBytes.length);
            rawSeg.copyFrom(MemorySegment.ofArray(webpBytes));

            MemorySegment wPtr = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment hPtr = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment rgbaPtr = (MemorySegment) fnWebpDecodeRGBA.invokeExact(
                rawSeg,
                (long) webpBytes.length,
                wPtr,
                hPtr
            );

            if (rgbaPtr != null && !rgbaPtr.equals(MemorySegment.NULL)) {
                int w = wPtr.get(ValueLayout.JAVA_INT, 0);
                int h = hPtr.get(ValueLayout.JAVA_INT, 0);
                int numPixels = w * h;
                int[] argb = new int[numPixels];

                MemorySegment sizedPtr = rgbaPtr.reinterpret((long) numPixels * 4);
                for (int i = 0; i < numPixels; i++) {
                    int r = sizedPtr.get(ValueLayout.JAVA_BYTE, i * 4) & 0xFF;
                    int g = sizedPtr.get(ValueLayout.JAVA_BYTE, i * 4 + 1) & 0xFF;
                    int b = sizedPtr.get(ValueLayout.JAVA_BYTE, i * 4 + 2) & 0xFF;
                    int a = sizedPtr.get(ValueLayout.JAVA_BYTE, i * 4 + 3) & 0xFF;
                    argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }

                fnWebpFree.invokeExact(rgbaPtr);
                return new DecodedFrame(w, h, argb, 100);
            }
        } catch (Throwable t) {
            System.err.println("[AnimaCapes] Native WebP decode notice: " + t.getMessage());
        }
        return null;
    }

    public static DecodedAnimation decodeAvif(File file) throws IOException {
        init();
        return decodeStaticFallback(file);
    }

    public static DecodedAnimation decodeStaticFallback(File file) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) {
            throw new IOException("Unable to decode image format: " + file.getName());
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] rgb = new int[w * h];
        img.getRGB(0, 0, w, h, rgb, 0, w);
        DecodedFrame singleFrame = new DecodedFrame(w, h, rgb, 100);
        return DecodedAnimation.ofSingleFrame(singleFrame);
    }
}
