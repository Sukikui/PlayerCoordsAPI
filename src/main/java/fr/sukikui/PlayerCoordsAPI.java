package fr.sukikui;

import fr.sukikui.config.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerCoordsAPI implements ModInitializer {
	public static final String MOD_ID = "playercoordsapi";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	
	// Config instance
	private static ModConfig config;

	@Override
	public void onInitialize() {
		// Register config
		AutoConfig.register(ModConfig.class, JanksonConfigSerializer::new);
		config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("PlayerCoordsAPI initialized - coordinates will be available at http://localhost:25565/coords when enabled");
	}
	
	public static ModConfig getConfig() {
		return config;
	}
}