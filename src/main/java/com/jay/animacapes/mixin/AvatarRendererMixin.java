package com.jay.animacapes.mixin;

import com.jay.animacapes.cloud.RemoteCapeManager;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.jay.animacapes.engine.CapeAnimationEngine;
import com.jay.animacapes.engine.CustomCapeAsset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL")
    )
    private void onExtractRenderState(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (!AnimaCapesConfig.getInstance().enabled || avatar == null) return;

        Minecraft mc = Minecraft.getInstance();
        boolean isLocal = (avatar instanceof LocalPlayer) ||
                          (mc != null && mc.player != null && (avatar == mc.player || avatar.getUUID().equals(mc.player.getUUID())));

        AnimaCapesConfig config = AnimaCapesConfig.getInstance();

        if (isLocal) {
            Identifier capeId = null;
            if (CapeAnimationEngine.getInstance().hasCape()) {
                capeId = CapeAnimationEngine.getInstance().getActiveCapeIdentifier();
            }

            if (capeId != null) {
                // Animated cape is active -> render animated cape and suppress vanilla cape
                PlayerSkin prev = state.skin;
                if (prev == null && mc != null && mc.player != null) {
                    prev = mc.player.getSkin();
                }
                if (prev != null) {
                    state.skin = new PlayerSkin(
                        prev.body(),
                        new CustomCapeAsset(capeId),
                        null, // Suppress vanilla cape elytra texture so it uses our custom cape
                        prev.model(),
                        prev.secure()
                    );
                }
                state.showCape = true;
            } else if (config.hideVanillaCape) {
                // Ensure the mod disables the user's Minecraft cape from being visible if cape rendering is enabled
                PlayerSkin prev = state.skin;
                if (prev != null) {
                    state.skin = new PlayerSkin(
                        prev.body(),
                        null, // Remove vanilla cape texture
                        null, // Remove vanilla cape elytra texture
                        prev.model(),
                        prev.secure()
                    );
                }
                state.showCape = false;
            }
        } else {
            // Remote players
            String remoteName = avatar.getScoreboardName();
            if (config.isBlocked(avatar.getUUID(), remoteName)) {
                // Blocked player: hide cape completely
                PlayerSkin prev = state.skin;
                if (prev != null && prev.cape() != null) {
                    state.skin = new PlayerSkin(
                        prev.body(),
                        null,
                        null,
                        prev.model(),
                        prev.secure()
                    );
                }
                state.showCape = false;
                return;
            }

            if (!config.showOtherPlayerCapes) {
                // User option not to view other people's capes
                PlayerSkin prev = state.skin;
                if (prev != null && prev.cape() != null) {
                    state.skin = new PlayerSkin(
                        prev.body(),
                        null,
                        null,
                        prev.model(),
                        prev.secure()
                    );
                }
                state.showCape = false;
                return;
            }

            if (config.cloudSyncEnabled) {
                // Distance culling: Skip players farther than 48 blocks away to save CPU and GPU cycles
                if (mc != null && mc.player != null && avatar.distanceToSqr(mc.player) > 2304.0) {
                    return;
                }
                Identifier capeId = RemoteCapeManager.getInstance().getOrQueryCape(avatar.getUUID());
                if (capeId != null) {
                    PlayerSkin prev = state.skin;
                    if (prev != null) {
                        state.skin = new PlayerSkin(
                            prev.body(),
                            new CustomCapeAsset(capeId),
                            null,
                            prev.model(),
                            prev.secure()
                        );
                    }
                    state.showCape = true;
                }
            }
        }
    }
}
