package fr.sukikui.playercoordsapi;

import fr.sukikui.playercoordsapi.config.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod entrypoint responsible for registering and sanitizing the shared config.
 */
public class PlayerCoordsAPI implements ModInitializer {
	public static final String MOD_ID = "playercoordsapi";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static ModConfig config;

	/**
	 * Registers the config serializer and normalizes persisted values once at startup.
	 */
	@Override
	public void onInitialize() {
		AutoConfig.register(ModConfig.class, JanksonConfigSerializer::new);
		config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
		config.sanitize();
		AutoConfig.getConfigHolder(ModConfig.class).save();

		LOGGER.info("PlayerCoordsAPI initialized - API will be available at http://localhost:{}/api/coords when enabled", config.apiPort);
	}
	
	/**
	 * Returns the shared mutable config instance managed by AutoConfig.
	 */
	public static ModConfig getConfig() {
		return config;
	}
}
