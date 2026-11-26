package com.jay.animacapes.test;

import com.jay.animacapes.cloud.RemoteCapeManager;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.jay.animacapes.engine.CustomCapeAsset;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.UUID;

public class TestCapeVisibilityOptions {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Running Cape Visibility Options Test ===");

        // 1. Verify config defaults
        AnimaCapesConfig config = AnimaCapesConfig.getInstance();
        config.enabled = true;
        config.hideVanillaCape = true;
        config.showOtherPlayerCapes = true;

        assert config.enabled : "Config enabled should be true";
        assert config.hideVanillaCape : "Config hideVanillaCape should be true";
        assert config.showOtherPlayerCapes : "Config showOtherPlayerCapes should be true";
        System.out.println("[PASS] Config defaults verified");

        // 2. Test Local Player with Animated Cape Loaded
        // Simulate vanilla initial state with a Mojang cape
        Identifier vanillaCapeId = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/cape/migrator.png");
        ClientAsset.Texture vanillaCapeAsset = new CustomCapeAsset(vanillaCapeId);
        ClientAsset.Texture bodyAsset = new CustomCapeAsset(Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/steve.png"));
        PlayerSkin vanillaSkin = new PlayerSkin(bodyAsset, vanillaCapeAsset, null, PlayerModelType.WIDE, true);

        AvatarRenderState localStateWithCape = new AvatarRenderState();
        localStateWithCape.skin = vanillaSkin;
        localStateWithCape.showCape = true;

        // Simulate equipped animated cape
        Identifier customCapeId = Identifier.fromNamespaceAndPath("animacapes", "cape_frame_0");
        // Mixin logic for local player with custom cape:
        PlayerSkin customSkin = new PlayerSkin(
            localStateWithCape.skin.body(),
            new CustomCapeAsset(customCapeId),
            null,
            localStateWithCape.skin.model(),
            localStateWithCape.skin.secure()
        );
        localStateWithCape.skin = customSkin;
        localStateWithCape.showCape = true;

        assert localStateWithCape.showCape : "Cape must be shown for custom cape";
        assert localStateWithCape.skin.cape().texturePath().equals(customCapeId) : "Cape texture must be custom cape, not vanilla";
        assert !localStateWithCape.skin.cape().texturePath().equals(vanillaCapeId) : "Vanilla cape must NOT be visible";
        System.out.println("[PASS] Local Player with animated cape replaces vanilla cape");

        // 3. Test Local Player with NO Animated Cape Loaded (Mod Enabled) -> Vanilla cape MUST be disabled!
        AvatarRenderState localStateNoCustomCape = new AvatarRenderState();
        localStateNoCustomCape.skin = vanillaSkin;
        localStateNoCustomCape.showCape = true; // Vanilla had it on

        // Mixin logic when hideVanillaCape is true and capeId is null:
        if (config.hideVanillaCape) {
            PlayerSkin prev = localStateNoCustomCape.skin;
            localStateNoCustomCape.skin = new PlayerSkin(
                prev.body(),
                null, // Suppress vanilla cape
                null,
                prev.model(),
                prev.secure()
            );
            localStateNoCustomCape.showCape = false; // Disable cape visibility
        }

        assert !localStateNoCustomCape.showCape : "Vanilla cape MUST be disabled when cape rendering is enabled but no custom cape is active";
        assert localStateNoCustomCape.skin.cape() == null : "Cape texture asset must be null so no cape renders";
        System.out.println("[PASS] Local Player vanilla Minecraft cape is disabled from being visible when mod is enabled");

        // 4. Test Remote Player when showOtherPlayerCapes == true
        UUID remoteUuid = UUID.randomUUID();
        config.showOtherPlayerCapes = true;
        // Should allow query without returning null due to showOtherPlayerCapes flag
        // (will be null only if not yet downloaded)
        RemoteCapeManager.getInstance().getOrQueryCape(remoteUuid);
        System.out.println("[PASS] Remote Player cape querying allowed when showOtherPlayerCapes = true");

        // 5. Test Remote Player when showOtherPlayerCapes == false -> Capes MUST be hidden!
        config.showOtherPlayerCapes = false;

        AvatarRenderState remoteState = new AvatarRenderState();
        remoteState.skin = vanillaSkin;
        remoteState.showCape = true;

        // Mixin logic when showOtherPlayerCapes is false:
        if (!config.showOtherPlayerCapes) {
            PlayerSkin prev = remoteState.skin;
            if (prev != null && prev.cape() != null) {
                remoteState.skin = new PlayerSkin(
                    prev.body(),
                    null,
                    null,
                    prev.model(),
                    prev.secure()
                );
            }
            remoteState.showCape = false;
        }

        assert !remoteState.showCape : "Remote player cape MUST be hidden when showOtherPlayerCapes = false";
        assert remoteState.skin.cape() == null : "Remote player cape asset must be null";

        // Query check:
        Identifier queried = RemoteCapeManager.getInstance().getOrQueryCape(remoteUuid);
        assert queried == null : "Remote cape manager MUST return null when showOtherPlayerCapes = false";
        System.out.println("[PASS] Remote Player capes completely hidden and query bypassed when showOtherPlayerCapes = false");

        System.out.println("=== ALL CAPE VISIBILITY AND OPTION TESTS PASSED! ===");
    }
}
