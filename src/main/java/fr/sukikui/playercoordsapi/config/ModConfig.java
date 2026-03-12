package fr.sukikui.playercoordsapi.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import fr.sukikui.playercoordsapi.PlayerCoordsAPI;

import java.util.ArrayList;
import java.util.List;

@Config(name = PlayerCoordsAPI.MOD_ID)
public class ModConfig implements ConfigData {
    public static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of();

    public enum CorsPolicy {
        ALLOW_ALL,
        LOCAL_WEB_APPS_ONLY,
        CUSTOM_WHITELIST
    }

    public enum OriginSchemeMode {
        AUTO,
        HTTP,
        HTTPS
    }

    public static class OriginEntry {
        public OriginSchemeMode schemeMode = OriginSchemeMode.AUTO;
        public String host = "";
        public String port = "";
    }

    public boolean enabled = true;
    public CorsPolicy corsPolicy = CorsPolicy.ALLOW_ALL;
    public List<String> allowedOrigins = new ArrayList<>(DEFAULT_ALLOWED_ORIGINS);
    public List<OriginEntry> originEntries = new ArrayList<>();
}
