package fr.sukikui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class PlayerCoordsAPIClient implements ClientModInitializer {
	private static final int PORT = 25565;
	private HttpServer server;

	@Override
	public void onInitializeClient() {
		// Start HTTP server
		try {
			PlayerCoordsAPI.LOGGER.info("Starting PlayerCoordsAPI HTTP server on port " + PORT);
			server = HttpServer.create(new InetSocketAddress(PORT), 0);
			server.createContext("/coords", this::handleCoordsRequest);
			server.setExecutor(Executors.newSingleThreadExecutor());
			server.start();
			PlayerCoordsAPI.LOGGER.info("PlayerCoordsAPI HTTP server started successfully");
		} catch (IOException e) {
			PlayerCoordsAPI.LOGGER.error("Failed to start PlayerCoordsAPI HTTP server", e);
		}
	}

	private void handleCoordsRequest(HttpExchange exchange) throws IOException {
		// Check if the client is allowed to access (only localhost)
		String remoteAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
		if (!remoteAddress.equals("127.0.0.1") && !remoteAddress.equals("0:0:0:0:0:0:0:1")) {
			sendResponse(exchange, 403, "Access denied");
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
			String dimension = player.getWorld().getRegistryKey().getValue().toString();
			
			// Format as JSON
			responseText = String.format(
				"{\"x\": %.2f, \"y\": %.2f, \"z\": %.2f, \"dimension\": \"%s\"}", 
				x, y, z, dimension
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