package com.jay.animacapes.engine;

import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;

public record CustomCapeAsset(Identifier texturePath) implements ClientAsset.Texture {
    @Override
    public Identifier id() {
        return texturePath;
    }

    @Override
    public Identifier texturePath() {
        return texturePath;
    }
}
