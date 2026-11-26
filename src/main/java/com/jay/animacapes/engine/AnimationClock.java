package com.jay.animacapes.engine;

import com.jay.animacapes.config.AnimaCapesConfig;

public class AnimationClock {
    private static final long START_NANO = System.nanoTime();

    /**
     * Monotonic client render clock in milliseconds.
     * Guaranteed to advance smoothly at real-world time regardless of server daylight cycle,
     * gamerules (doDaylightCycle false), server TPS lag, or multiplayer proxy packet batching
     * (e.g. MCPVP, Hypixel).
     *
     * If syncReplay is explicitly enabled in config, it dynamically resolves client game time and partial ticks
     * for frame-accurate Replay Mod video exporting across all Minecraft versions (1.21.x and 26.x).
     */
    public static long getTimeMs() {
        try {
            if (AnimaCapesConfig.getInstance().syncReplay) {
                long replayTime = getReplayTimeSafe();
                if (replayTime >= 0) {
                    return replayTime;
                }
            }
        } catch (Throwable ignored) {
        }
        return (System.nanoTime() - START_NANO) / 1_000_000L;
    }

    private static long getReplayTimeSafe() {
        try {
            // Mojang mappings (26.0, 26.1, 26.2)
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            if (mc != null) {
                Object level = mcClass.getField("level").get(mc);
                if (level != null) {
                    long gameTime = (long) level.getClass().getMethod("getGameTime").invoke(level);
                    float partial = 0.0f;
                    try {
                        Object tracker = mcClass.getMethod("getDeltaTracker").invoke(mc);
                        partial = (float) tracker.getClass().getMethod("getGameTimeDeltaPartialTick", boolean.class).invoke(tracker, false);
                    } catch (Throwable ignored) {}
                    return (long) ((gameTime + partial) * 50.0d);
                }
            }
        } catch (Throwable t1) {
            try {
                // Intermediary mappings (1.21.x)
                Class<?> mcClass = Class.forName("net.minecraft.class_310");
                Object mc = mcClass.getMethod("method_1551").invoke(null);
                if (mc != null) {
                    Object world = mcClass.getField("field_1687").get(mc);
                    if (world != null) {
                        long gameTime = (long) world.getClass().getMethod("method_8510").invoke(world);
                        float partial = 0.0f;
                        try {
                            Object tracker = mcClass.getMethod("method_53526").invoke(mc);
                            partial = (float) tracker.getClass().getMethod("method_53531", boolean.class).invoke(tracker, false);
                        } catch (Throwable ignored) {}
                        return (long) ((gameTime + partial) * 50.0d);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return -1;
    }
}
