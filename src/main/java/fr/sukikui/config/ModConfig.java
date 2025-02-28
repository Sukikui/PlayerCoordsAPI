package fr.sukikui.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import fr.sukikui.PlayerCoordsAPI;

@Config(name = PlayerCoordsAPI.MOD_ID)
public class ModConfig implements ConfigData {
    
    @ConfigEntry.Gui.Tooltip(count = 0) // This tells autoconfig to use the tooltip from lang files
    public boolean enabled = true;
}