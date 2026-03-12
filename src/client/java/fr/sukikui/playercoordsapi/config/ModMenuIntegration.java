package fr.sukikui.playercoordsapi.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Registers the custom config screen with Mod Menu.
 */
@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {
    /**
     * Returns the screen factory used by Mod Menu for this mod.
     */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return PlayerCoordsConfigScreen::new;
    }
}
