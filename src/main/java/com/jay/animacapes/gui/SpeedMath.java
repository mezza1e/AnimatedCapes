package com.jay.animacapes.gui;

public class SpeedMath {
    public static double speedToValue(float speed) {
        if (speed <= 1.0f) {
            return Math.max(0.0, (speed - 0.25) / 0.75 * 0.5);
        } else {
            return Math.min(1.0, 0.5 + (speed - 1.0) / 2.0 * 0.5);
        }
    }

    public static float valueToSpeed(double val) {
        if (Math.abs(val - 0.5) <= 0.025) {
            return 1.0f;
        }
        float speed;
        if (val <= 0.5) {
            speed = 0.25f + (float) (val / 0.5) * 0.75f;
        } else {
            speed = 1.00f + (float) ((val - 0.5) / 0.5) * 2.00f;
        }
        if (Math.abs(speed - 1.0f) < 0.06f) {
            speed = 1.0f;
        }
        return Math.round(speed * 20.0f) / 20.0f; // Round to nearest 0.05
    }
}
