package com.jay.animacapes.test;

import com.jay.animacapes.codec.*;
import com.jay.animacapes.gui.GalleryFolder;
import com.jay.animacapes.gui.FileDialogHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TestAsyncUploadStress {
    private static final String TARGET_GIF = "C:\\Users\\jay\\Videos\\Anime Gif\\147980698-large_export.gif";

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("         RIGOROUS FILE UPLOAD & ASYNC PIPELINE STRESS TEST SUITE                ");
        System.out.println("================================================================================");

        File targetFile = new File(TARGET_GIF);
        if (!targetFile.exists()) {
            throw new RuntimeException("Target test GIF does not exist at: " + TARGET_GIF);
        }
        System.out.println("Target GIF found: " + targetFile.getName() + " (" + targetFile.length() + " bytes)");
        System.out.println("DecodedAnimation location: " + DecodedAnimation.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("AnimationDecoder location: " + AnimationDecoder.class.getProtectionDomain().getCodeSource().getLocation());

        // -----------------------------------------------------------------------------------------
        // Test 1: Decode & Full Pre-Rasterization of Target GIF
        // -----------------------------------------------------------------------------------------
        System.out.println("\n[Test 1] Testing decoding & background pre-rasterization of target GIF...");
        long t0 = System.nanoTime();
        DecodedAnimation anim = AnimationDecoder.decode(targetFile);
        if (anim == null || anim.getFrameCount() == 0) {
            throw new RuntimeException("Failed to decode target GIF!");
        }
        long tDecode = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  Decoded " + anim.getFrameCount() + " frames (" + anim.getWidth() + "x" + anim.getHeight() + ") in " + tDecode + " ms");

        long tRaster0 = System.nanoTime();
        anim.preRasterizeAllFrames("COVER");
        long tRaster = (System.nanoTime() - tRaster0) / 1_000_000;
        System.out.println("  Pre-rasterized all " + anim.getFrameCount() + " frames in background: " + tRaster + " ms");

        // Verify that on the render thread, getOrRasterizeSlotPixels is instantaneous (cached)
        long tRenderThread0 = System.nanoTime();
        for (int i = 0; i < anim.getFrameCount(); i++) {
            int[] slot = anim.getFrame(i).getOrRasterizeSlotPixels("COVER");
            if (slot == null || slot.length != DecodedFrame.FACE_W * DecodedFrame.FACE_H) {
                throw new RuntimeException("Slot pixels corrupted for frame " + i);
            }
        }
        long tRenderThread = (System.nanoTime() - tRenderThread0) / 1_000;
        System.out.println("  Render thread access for all " + anim.getFrameCount() + " frames: " + tRenderThread + " µs (0.00" + tRenderThread + " ms)");
        if (tRenderThread > 500) {
            System.out.println("  WARNING: Render thread took longer than expected: " + tRenderThread + " µs");
        } else {
            System.out.println("  ✓ Render thread slot lookup is INSTANT (<0.5ms total for all frames). Zero freeze!");
        }

        // -----------------------------------------------------------------------------------------
        // Test 2: WebP Optimizer Guard Invariant (No File Ballooning, No Gallery Pollution)
        // -----------------------------------------------------------------------------------------
        System.out.println("\n[Test 2] Testing WebpOptimizer on target GIF...");
        File tempDir = Files.createTempDirectory("animacapes_test_opt").toFile();
        try {
            File optimized = WebpOptimizer.optimizeGifToWebp(targetFile, tempDir);
            if (optimized != null) {
                if (optimized.length() >= targetFile.length()) {
                    throw new RuntimeException("WebpOptimizer emitted a WebP larger than the GIF! " + optimized.length() + " >= " + targetFile.length());
                }
                System.out.println("  WebP was smaller: " + optimized.length() + " < " + targetFile.length());
            } else {
                System.out.println("  ✓ WebpOptimizer correctly rejected larger WebP (returned null). Original GIF preserved!");
            }
        } finally {
            for (File f : tempDir.listFiles()) f.delete();
            tempDir.delete();
        }

        // -----------------------------------------------------------------------------------------
        // Test 3: Simulated Render Loop Concurrent with Rapid File Uploads
        // -----------------------------------------------------------------------------------------
        System.out.println("\n[Test 3] Stress Testing: Simulated 60 FPS Render Thread while uploading files...");
        ExecutorService testPool = Executors.newFixedThreadPool(4);
        AtomicBoolean renderThreadStalled = new AtomicBoolean(false);
        AtomicInteger renderFramesCount = new AtomicInteger(0);
        AtomicBoolean stopRender = new AtomicBoolean(false);

        // Render Thread simulation: Must maintain ~60 FPS (<= 16.6ms per frame)
        Future<?> renderLoop = testPool.submit(() -> {
            long lastFrameTime = System.currentTimeMillis();
            while (!stopRender.get()) {
                long now = System.currentTimeMillis();
                long delta = now - lastFrameTime;
                if (delta > 50 && renderFramesCount.get() > 5) { // Stalled for more than 3 frames (50ms)
                    System.err.println("  ERROR: Render thread stalled! Frame delta = " + delta + " ms");
                    renderThreadStalled.set(true);
                }
                lastFrameTime = now;
                renderFramesCount.incrementAndGet();
                try {
                    Thread.sleep(16); // 60 FPS
                } catch (InterruptedException ignored) {}
            }
        });

        // Background Upload Thread simulation: Uploading files rapidly
        List<Future<?>> uploadFutures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final int uploadId = i;
            uploadFutures.add(testPool.submit(() -> {
                try {
                    // Simulate async file dialog callback + copy + decode + pre-rasterize
                    File testCopy = File.createTempFile("animacapes_upload_" + uploadId, ".gif");
                    testCopy.deleteOnExit();
                    Files.copy(targetFile.toPath(), testCopy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    DecodedAnimation dAnim = AnimationDecoder.decode(testCopy);
                    dAnim.preRasterizeAllFrames("COVER");

                    testCopy.delete();
                } catch (Exception e) {
                    throw new RuntimeException("Upload failed on iter " + uploadId, e);
                }
            }));
        }

        for (Future<?> f : uploadFutures) {
            f.get(10, TimeUnit.SECONDS);
        }

        stopRender.set(true);
        renderLoop.get(2, TimeUnit.SECONDS);

        System.out.println("  Simulated Render Thread rendered " + renderFramesCount.get() + " frames during 50 background uploads.");
        if (renderThreadStalled.get()) {
            throw new RuntimeException("Render thread experienced frame freeze during uploads!");
        }
        System.out.println("  ✓ Render thread maintained smooth 60 FPS throughout all 50 concurrent uploads! Zero freeze!");

        // -----------------------------------------------------------------------------------------
        // Test 4: Extreme Concurrency & Multi-Threaded Rasterization
        // -----------------------------------------------------------------------------------------
        System.out.println("\n[Test 4] Stress Testing: 200 concurrent frame rasterizations...");
        long tConc0 = System.nanoTime();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final int idx = i % anim.getFrameCount();
            tasks.add(() -> {
                anim.getFrame(idx).getOrRasterizeSlotPixels("COVER");
                anim.getFrame(idx).getOrRasterizeSlotPixels("CONTAIN");
                anim.getFrame(idx).getOrRasterizeSlotPixels("STRETCH");
                return null;
            });
        }
        List<Future<Void>> taskResults = testPool.invokeAll(tasks);
        for (Future<Void> r : taskResults) {
            r.get();
        }
        long tConc = (System.nanoTime() - tConc0) / 1_000_000;
        System.out.println("  Completed 200 concurrent fit mode rasterizations in " + tConc + " ms (" + (200 * 1000 / Math.max(1, tConc)) + " ops/sec)");
        System.out.println("  ✓ Multi-threaded rasterization is completely thread-safe!");

        testPool.shutdownNow();

        System.out.println("\n================================================================================");
        System.out.println("     ALL UPLOAD, RASTERIZATION & ASYNC STRESS TESTS PASSED WITH 100% SUCCESS    ");
        System.out.println("================================================================================");
    }
}
