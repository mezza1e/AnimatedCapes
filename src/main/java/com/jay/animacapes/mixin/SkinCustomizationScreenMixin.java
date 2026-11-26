package com.jay.animacapes.mixin;

import com.jay.animacapes.gui.AnimaCapesScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkinCustomizationScreen.class)
public abstract class SkinCustomizationScreenMixin extends OptionsSubScreen {

    public SkinCustomizationScreenMixin() {
        super(null, null, null);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void onAddOptions(CallbackInfo ci) {
        int btnW = 150;
        int btnH = 20;
        int btnX = this.width / 2 - btnW / 2;
        int btnY = this.height - 54;

        this.addRenderableWidget(Button.builder(
            Component.literal("§bAnimated Capes..."),
            b -> {
                if (this.minecraft != null) {
                    this.minecraft.setScreenAndShow(new AnimaCapesScreen(this));
                }
            }
        ).bounds(btnX, btnY, btnW, btnH).build());
    }
}
