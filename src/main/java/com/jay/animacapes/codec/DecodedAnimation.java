package com.jay.animacapes.codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DecodedAnimation {
    private final List<DecodedFrame> frames;
    private final int width;
    private final int height;
    private final int loopCount;
    private final long totalDurationMs;
    private final long[] cumulativeDurationsMs;

    public DecodedAnimation(List<DecodedFrame> frames, int width, int height, int loopCount) {
        this.frames = (frames != null && !frames.isEmpty()) ? frames : new ArrayList<>();
        this.width = width;
        this.height = height;
        this.loopCount = loopCount;

        long total = 0;
        this.cumulativeDurationsMs = new long[this.frames.size()];
        for (int i = 0; i < this.frames.size(); i++) {
            total += this.frames.get(i).getDurationMs();
            this.cumulativeDurationsMs[i] = total;
        }
        this.totalDurationMs = Math.max(1, total);
    }

    public static DecodedAnimation ofSingleFrame(DecodedFrame frame) {
        List<DecodedFrame> list = new ArrayList<>();
        list.add(frame);
        return new DecodedAnimation(list, frame.getWidth(), frame.getHeight(), 0);
    }

    public int getFrameCount() {
        return frames.size();
    }

    public void preRasterizeAllFrames(String fitMode) {
        for (DecodedFrame frame : frames) {
            if (frame != null) {
                frame.getOrRasterizeSlotPixels(fitMode);
            }
        }
    }

    public boolean isAnimated() {
        return frames.size() > 1;
    }

    public List<DecodedFrame> getFrames() {
        return frames;
    }

    public DecodedFrame getFrame(int index) {
        if (frames.isEmpty()) return null;
        int clamped = Math.floorMod(index, frames.size());
        return frames.get(clamped);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getLoopCount() {
        return loopCount;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public int getFrameIndexAt(long elapsedMs) {
        if (frames.size() <= 1) return 0;
        long timeInLoop = Math.floorMod(elapsedMs, totalDurationMs);
        int idx = Arrays.binarySearch(cumulativeDurationsMs, timeInLoop);
        if (idx >= 0) {
            return Math.min(idx + 1, frames.size() - 1);
        } else {
            int insertionPoint = -idx - 1;
            return Math.min(insertionPoint, frames.size() - 1);
        }
    }
}
