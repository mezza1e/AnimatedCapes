package com.jay.animacapes;

import com.jay.animacapes.codec.NativeCodecBridge;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.jay.animacapes.engine.CapeAnimationEngine;
import com.jay.animacapes.gui.AnimaCapesScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class AnimaCapesClient implements ClientModInitializer {
    public static final String MOD_ID = "animacapes";
    public static KeyMapping OPEN_GUI_KEY;
    private static int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        System.out.println("[AnimatedCapes] Initializing Animated Capes for Minecraft 26.2...");

        // 1. Initialize native codecs
        NativeCodecBridge.init();

        // 1.5 Register In-Game Commands (/whitelist, /unwhitelist, /ban, /unban, /cape ...)
        com.jay.animacapes.command.ClientCommandHandler.init();

        // 2. Register Keymapping (Default 'H' key)
        KeyMapping.Category category = new KeyMapping.Category(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        OPEN_GUI_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.animacapes.open_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            category
        ));

        // 3. Register client tick listener for key trigger and cache cleanup
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_GUI_KEY.consumeClick()) {
                if (isScreenNull(client)) {
                    client.setScreenAndShow(new AnimaCapesScreen(null));
                }
            }

            if (++tickCounter % 600 == 0) {
                com.jay.animacapes.cloud.RemoteCapeManager.getInstance().cleanupUnusedTextures();
            }
        });

        // 4. Load configured cape once client starts
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            AnimaCapesConfig config = AnimaCapesConfig.getInstance();
            if (config.enabled && config.capeFilePath != null && !config.capeFilePath.trim().isEmpty()) {
                CapeAnimationEngine.getInstance().reload();
            }
        });

        System.out.println("[AnimatedCapes] Animated Capes initialized successfully!");
    }

    private static boolean isScreenNull(net.minecraft.client.Minecraft client) {
        if (client == null) return false;
        try {
            var gui = client.gui;
            if (gui != null) {
                var method = gui.getClass().getMethod("screen");
                return method.invoke(gui) == null;
            }
        } catch (Throwable ignored) {}
        try {
            var field = client.getClass().getField("screen");
            return field.get(client) == null;
        } catch (Throwable ignored) {}
        return true;
    }
}
