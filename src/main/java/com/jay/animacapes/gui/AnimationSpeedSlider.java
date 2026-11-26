package com.jay.animacapes.gui;

import com.jay.animacapes.config.AnimaCapesConfig;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public class AnimationSpeedSlider extends AbstractSliderButton {

    public AnimationSpeedSlider(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty(), speedToValue(AnimaCapesConfig.getInstance().getAnimationSpeed()));
        this.updateMessage();
    }

    public static double speedToValue(float speed) {
        return SpeedMath.speedToValue(speed);
    }

    public static float valueToSpeed(double val) {
        return SpeedMath.valueToSpeed(val);
    }

    @Override
    protected void updateMessage() {
        float speed = valueToSpeed(this.value);
        String label;
        if (Math.abs(speed - 1.0f) < 0.01f) {
            label = "Speed: 1.00x (Normal)";
        } else if (speed < 1.0f) {
            label = String.format("Speed: %.2fx (Slow)", speed);
        } else {
            label = String.format("Speed: %.2fx (Fast)", speed);
        }
        this.setMessage(Component.literal(label));
    }

    @Override
    protected void applyValue() {
        float speed = valueToSpeed(this.value);
        AnimaCapesConfig.getInstance().animationSpeed = speed;
        AnimaCapesConfig.getInstance().save();
    }
}
