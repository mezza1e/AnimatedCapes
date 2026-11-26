package com.jay.animacapes.compat;

import com.jay.animacapes.gui.AnimaCapesScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AnimaCapesScreen::new;
    }
}
