package com.jay.animacapes.test;

import com.jay.animacapes.codec.DecodedFrame;
import com.mojang.blaze3d.platform.NativeImage;

public class BenchStamp {
    public static void main(String[] args) {
        int[] dummy = new int[DecodedFrame.FACE_W * DecodedFrame.FACE_H];
        for (int i = 0; i < dummy.length; i++) dummy[i] = 0xFF55AACC;
        DecodedFrame frame = new DecodedFrame(300, 300, dummy, 33);

        NativeImage cape = new NativeImage(NativeImage.Format.RGBA, DecodedFrame.TARGET_W, DecodedFrame.TARGET_H, false);

        // Warmup
        for (int i = 0; i < 50; i++) {
            frame.stampOntoCapeImage(cape, "COVER");
        }

        long t0 = System.nanoTime();
        int iters = 500;
        for (int i = 0; i < iters; i++) {
            frame.stampOntoCapeImage(cape, "COVER");
        }
        long t1 = System.nanoTime();
        double msPerStamp = (t1 - t0) / (iters * 1_000_000.0);
        System.out.println("stampOntoCapeImage: " + msPerStamp + " ms per frame (" + (int)(1000 / msPerStamp) + " FPS)");
    }
}
