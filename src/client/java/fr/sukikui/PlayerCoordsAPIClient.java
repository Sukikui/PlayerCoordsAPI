package fr.sukikui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.sukikui.config.ModConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class PlayerCoordsAPIClient implements ClientModInitializer {
	private HttpServer server;
	private boolean serverStarted = false;
	// Hardcoded port value - no longer in config
	private static final int PORT = 25565;

	@Override
	public void onInitializeClient() {
		// Start server on init if enabled
		if (PlayerCoordsAPI.getConfig().enabled) {
			startServer();
		}
		
		// Register tick event to constantly check config status
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean configEnabled = PlayerCoordsAPI.getConfig().enabled;
			
			// If enabled and server not started, start server
			if (configEnabled && !serverStarted) {
				startServer();
			}
			
			// If disabled and server is running, stop server
			if (!configEnabled && serverStarted) {
				stopServer();
			}
		});
		
		PlayerCoordsAPI.LOGGER.info("Registered config monitor");
	}
	
	private void startServer() {
		if (serverStarted) return;
		
		try {
			PlayerCoordsAPI.LOGGER.info("Starting PlayerCoordsAPI HTTP server on port " + PORT);
			server = HttpServer.create(new InetSocketAddress(PORT), 0);
			server.createContext("/coords", this::handleCoordsRequest);
			server.setExecutor(Executors.newSingleThreadExecutor());
			server.start();
			serverStarted = true;
			PlayerCoordsAPI.LOGGER.info("PlayerCoordsAPI HTTP server started successfully");
		} catch (IOException e) {
			PlayerCoordsAPI.LOGGER.error("Failed to start PlayerCoordsAPI HTTP server", e);
		}
	}
	
	private void stopServer() {
		if (server != null) {
			PlayerCoordsAPI.LOGGER.info("Stopping PlayerCoordsAPI HTTP server");
			
			// Create a separate thread to stop the server to prevent blocking
			Thread stopThread = new Thread(() -> {
				server.stop(0); // Stop with no delay
				PlayerCoordsAPI.LOGGER.info("PlayerCoordsAPI HTTP server stopped successfully");
			});
			stopThread.setDaemon(true);
			stopThread.start();
			
			// Set variables immediately so we know the server is being stopped
			server = null;
			serverStarted = false;
		}
	}

	private void handleCoordsRequest(HttpExchange exchange) throws IOException {
		// Check if the client is allowed to access (only localhost)
		String remoteAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
		if (!remoteAddress.equals("127.0.0.1") && !remoteAddress.equals("0:0:0:0:0:0:0:1")) {
			sendResponse(exchange, 403, "{\"error\": \"Access denied\"}");
			return;
		}

		// Get player coordinates
		MinecraftClient client = MinecraftClient.getInstance();
		PlayerEntity player = client.player;
		
		String responseText;
		if (player != null) {
			double x = player.getX();
			double y = player.getY();
			double z = player.getZ();
			String world = player.getWorld().getRegistryKey().getValue().toString();
			
			// Format as JSON
			responseText = String.format(
				"{\"x\": %.2f, \"y\": %.2f, \"z\": %.2f, \"world\": \"%s\"}",
				x, y, z, world
			);
			sendResponse(exchange, 200, responseText);
		} else {
			sendResponse(exchange, 404, "{\"error\": \"Player not in world\"}");
		}
	}
	
	private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, response.length());
		
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(response.getBytes());
		}
	}
}