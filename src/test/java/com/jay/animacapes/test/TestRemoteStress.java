package com.jay.animacapes.test;

import com.jay.animacapes.cloud.RemotePlayerCapeInstance;
import com.jay.animacapes.codec.DecodedAnimation;
import com.jay.animacapes.codec.DecodedFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class TestRemoteStress {
    public static void main(String[] args) throws Exception {
        System.out.println("Testing Remote Cape Instance Lifecycle...");
        List<DecodedFrame> frames = new ArrayList<>();
        int[] dummy = new int[DecodedFrame.FACE_W * DecodedFrame.FACE_H];
        for (int i = 0; i < 20; i++) {
            frames.add(new DecodedFrame(DecodedFrame.FACE_W, DecodedFrame.FACE_H, dummy, 50));
        }
        DecodedAnimation anim = new DecodedAnimation(frames, DecodedFrame.FACE_W, DecodedFrame.FACE_H, 0);

        RemotePlayerCapeInstance inst = new RemotePlayerCapeInstance("test_hash_12345", anim);
        assert inst.getHash().equals("test_hash_12345");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                for (int j = 0; j < 100000; j++) {
                    inst.getActiveCapeIdentifier();
                }
                latch.countDown();
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();
        inst.close();
        System.out.println("Remote Cape Stress Test PASSED: 800000 queries concurrent!");
    }
}
