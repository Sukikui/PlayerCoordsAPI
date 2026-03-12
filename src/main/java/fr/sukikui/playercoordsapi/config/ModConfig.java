package fr.sukikui.playercoordsapi.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import fr.sukikui.playercoordsapi.PlayerCoordsAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Persistent mod configuration used by the runtime server and config screen.
 */
@Config(name = PlayerCoordsAPI.MOD_ID)
public class ModConfig implements ConfigData {
    public static final int DEFAULT_API_PORT = 25565;
    public static final int MIN_API_PORT = 1;
    public static final int MAX_API_PORT = 65535;
    public static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of();

    /**
     * Defines how requests with an {@code Origin} header are filtered.
     */
    public enum CorsPolicy {
        ALLOW_ALL,
        LOCAL_WEB_APPS_ONLY,
        CUSTOM_WHITELIST
    }

    /**
     * Controls how whitelist entries infer or force their scheme.
     */
    public enum OriginSchemeMode {
        AUTO,
        HTTP,
        HTTPS
    }

    /**
     * Editable whitelist entry stored in config and mirrored by the custom UI.
     */
    public static class OriginEntry {
        public OriginSchemeMode schemeMode = OriginSchemeMode.AUTO;
        public String host = "";
        public String port = "";
    }

    /**
     * Returns whether the API port is inside the valid TCP user range handled by the UI.
     */
    public static boolean isValidApiPort(int port) {
        return port >= MIN_API_PORT && port <= MAX_API_PORT;
    }

    /**
     * Falls back to the default API port when the persisted value is invalid.
     */
    public static int normalizeApiPort(int port) {
        return isValidApiPort(port) ? port : DEFAULT_API_PORT;
    }

    /**
     * Parses a raw port string coming from text inputs.
     */
    public static OptionalInt parseApiPort(String rawPort) {
        if (rawPort == null) {
            return OptionalInt.empty();
        }

        String trimmedPort = rawPort.trim();

        if (trimmedPort.isEmpty()) {
            return OptionalInt.empty();
        }

        try {
            int port = Integer.parseInt(trimmedPort);
            return isValidApiPort(port) ? OptionalInt.of(port) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /**
     * Normalizes nullable, legacy, or partially-invalid persisted config values.
     */
    public void sanitize() {
        if (corsPolicy == null) {
            corsPolicy = CorsPolicy.ALLOW_ALL;
        }

        if (allowedOrigins == null) {
            allowedOrigins = new ArrayList<>(DEFAULT_ALLOWED_ORIGINS);
        }

        if (originEntries == null) {
            originEntries = new ArrayList<>();
        }

        apiPort = normalizeApiPort(apiPort);

        if (originEntries.isEmpty() && !allowedOrigins.isEmpty()) {
            originEntries = CorsUtils.createConfiguredOriginEntries(allowedOrigins);
        }

        allowedOrigins = originEntries.isEmpty()
                ? CorsUtils.normalizeConfiguredOrigins(allowedOrigins)
                : CorsUtils.normalizeConfiguredOriginsFromEntries(originEntries);
    }

    public boolean enabled = true;
    public int apiPort = DEFAULT_API_PORT;
    public CorsPolicy corsPolicy = CorsPolicy.ALLOW_ALL;
    public boolean allowNonBrowserLocalClients = true;
    public List<String> allowedOrigins = new ArrayList<>(DEFAULT_ALLOWED_ORIGINS);
    public List<OriginEntry> originEntries = new ArrayList<>();
}
