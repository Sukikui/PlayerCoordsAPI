package fr.sukikui.playercoordsapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.sukikui.playercoordsapi.config.CorsUtils;
import fr.sukikui.playercoordsapi.config.ModConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerCoordsAPIClient implements ClientModInitializer {
    private static final int PORT = 25565;
    private static final long START_RETRY_DELAY_MS = 5_000L;
    private static final String ALLOWED_METHODS = "GET, OPTIONS";
    private static final String DEFAULT_ALLOWED_HEADERS = "Content-Type, Authorization";
    private static final String ACCESS_DENIED_RESPONSE = "{\"error\": \"Access denied\"}";
    private static final String ORIGIN_NOT_ALLOWED_RESPONSE = "{\"error\": \"Origin not allowed\"}";
    private static final String NON_BROWSER_CLIENTS_NOT_ALLOWED_RESPONSE = "{\"error\": \"Non-browser local clients not allowed\"}";
    private static final String METHOD_NOT_ALLOWED_RESPONSE = "{\"error\": \"Method not allowed\"}";
    private static final String PLAYER_NOT_IN_WORLD_RESPONSE = "{\"error\": \"Player not in world\"}";

    private HttpServer server;
    private ExecutorService serverExecutor;
    private boolean serverStarted = false;
    private boolean lastConfigEnabled;
    private long nextStartAttemptAt = 0L;
    private volatile PlayerSnapshot latestSnapshot;

    @Override
    public void onInitializeClient() {
        lastConfigEnabled = PlayerCoordsAPI.getConfig().enabled;

        if (lastConfigEnabled) {
            tryStartServer();
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            updateSnapshot(client);
            handleConfigState(PlayerCoordsAPI.getConfig().enabled);
        });

        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) -> updateSnapshot(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearSnapshot());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            clearSnapshot();
            stopServer();
        });

        PlayerCoordsAPI.LOGGER.info("Registered config monitor");
    }

    private void handleConfigState(boolean configEnabled) {
        if (configEnabled != lastConfigEnabled) {
            lastConfigEnabled = configEnabled;

            if (configEnabled) {
                nextStartAttemptAt = 0L;
                tryStartServer();
            } else {
                nextStartAttemptAt = 0L;
                stopServer();
            }

            return;
        }

        if (configEnabled && !serverStarted) {
            tryStartServer();
        }
    }

    private void updateSnapshot(MinecraftClient client) {
        PlayerEntity player = client.player;
        ClientWorld worldObj = client.world;

        if (player == null || worldObj == null) {
            latestSnapshot = null;
            return;
        }

        RegistryEntry<Biome> biomeEntry = worldObj.getBiome(player.getBlockPos());
        String biome = biomeEntry.getKey()
                .map(key -> key.getValue().toString())
                .orElse("unknown");

        latestSnapshot = new PlayerSnapshot(
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYaw(),
                player.getPitch(),
                worldObj.getRegistryKey().getValue().toString(),
                biome,
                player.getUuidAsString(),
                player.getName().getString()
        );
    }

    private void clearSnapshot() {
        latestSnapshot = null;
    }

    private void tryStartServer() {
        if (serverStarted) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now < nextStartAttemptAt) {
            return;
        }

        try {
            PlayerCoordsAPI.LOGGER.info("Starting PlayerCoordsAPI HTTP server on port " + PORT);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
            server.createContext("/api/coords", this::handleCoordsRequest);
            serverExecutor = Executors.newSingleThreadExecutor();
            server.setExecutor(serverExecutor);
            server.start();
            serverStarted = true;
            nextStartAttemptAt = 0L;
            PlayerCoordsAPI.LOGGER.info("PlayerCoordsAPI HTTP server started successfully");
        } catch (IOException e) {
            cleanupServerResources();
            nextStartAttemptAt = now + START_RETRY_DELAY_MS;
            PlayerCoordsAPI.LOGGER.warn(
                    "Failed to start PlayerCoordsAPI HTTP server, retrying in {} seconds",
                    START_RETRY_DELAY_MS / 1000,
                    e
            );
        }
    }

    private void stopServer() {
        if (server == null && serverExecutor == null) {
            return;
        }

        PlayerCoordsAPI.LOGGER.info("Stopping PlayerCoordsAPI HTTP server");
        cleanupServerResources();
        PlayerCoordsAPI.LOGGER.info("PlayerCoordsAPI HTTP server stopped successfully");
    }

    private void cleanupServerResources() {
        if (server != null) {
            server.stop(0);
            server = null;
        }

        if (serverExecutor != null) {
            serverExecutor.shutdown();
            serverExecutor = null;
        }

        serverStarted = false;
    }

    private void handleCoordsRequest(HttpExchange exchange) throws IOException {
        InetAddress remoteAddress = exchange.getRemoteAddress().getAddress();
        if (remoteAddress == null || !remoteAddress.isLoopbackAddress()) {
            sendResponse(exchange, 403, ACCESS_DENIED_RESPONSE, CorsDecision.noCors());
            return;
        }

        CorsDecision corsDecision = evaluateCorsDecision(exchange);
        if (!corsDecision.allowed()) {
            sendResponse(exchange, 403, corsDecision.errorResponse(), CorsDecision.noCors());
            return;
        }

        String method = exchange.getRequestMethod();

        if (method.equalsIgnoreCase("OPTIONS")) {
            sendResponse(exchange, 204, null, corsDecision);
            return;
        }

        if (!method.equalsIgnoreCase("GET")) {
            exchange.getResponseHeaders().set("Allow", ALLOWED_METHODS);
            sendResponse(exchange, 405, METHOD_NOT_ALLOWED_RESPONSE, corsDecision);
            return;
        }

        PlayerSnapshot snapshot = latestSnapshot;
        if (snapshot != null) {
            sendResponse(exchange, 200, snapshot.toJson(), corsDecision);
        } else {
            sendResponse(exchange, 404, PLAYER_NOT_IN_WORLD_RESPONSE, corsDecision);
        }
    }

    private CorsDecision evaluateCorsDecision(HttpExchange exchange) {
        ModConfig config = PlayerCoordsAPI.getConfig();
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");

        if (requestOrigin == null || requestOrigin.isBlank()) {
            return config.allowNonBrowserLocalClients
                    ? CorsDecision.noCors()
                    : CorsDecision.denied(NON_BROWSER_CLIENTS_NOT_ALLOWED_RESPONSE);
        }

        if (config.corsPolicy == ModConfig.CorsPolicy.ALLOW_ALL) {
            return CorsDecision.allowed("*", resolveAllowedHeaders(exchange), false);
        }

        if (!CorsUtils.isOriginAllowed(config, requestOrigin)) {
            return CorsDecision.denied(ORIGIN_NOT_ALLOWED_RESPONSE);
        }

        return CorsUtils.normalizeOrigin(requestOrigin)
                .map(origin -> CorsDecision.allowed(origin, resolveAllowedHeaders(exchange), true))
                .orElseGet(() -> CorsDecision.denied(ORIGIN_NOT_ALLOWED_RESPONSE));
    }

    private String resolveAllowedHeaders(HttpExchange exchange) {
        String requestedHeaders = exchange.getRequestHeaders().getFirst("Access-Control-Request-Headers");

        if (requestedHeaders == null || requestedHeaders.isBlank()) {
            return DEFAULT_ALLOWED_HEADERS;
        }

        return requestedHeaders;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response, CorsDecision corsDecision) throws IOException {
        if (corsDecision.allowOrigin() != null) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", corsDecision.allowOrigin());
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", ALLOWED_METHODS);
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", corsDecision.allowHeaders());

            if (corsDecision.varyByOrigin()) {
                exchange.getResponseHeaders().set("Vary", "Origin, Access-Control-Request-Headers");
            }
        }

        if (response != null) {
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } else {
            exchange.sendResponseHeaders(statusCode, -1);
        }
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);

            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }

        return escaped.toString();
    }

    private record CorsDecision(boolean allowed, String allowOrigin, String allowHeaders, boolean varyByOrigin, String errorResponse) {
        private static CorsDecision allowed(String allowOrigin, String allowHeaders, boolean varyByOrigin) {
            return new CorsDecision(true, allowOrigin, allowHeaders, varyByOrigin, null);
        }

        private static CorsDecision denied(String errorResponse) {
            return new CorsDecision(false, null, null, false, errorResponse);
        }

        private static CorsDecision noCors() {
            return new CorsDecision(true, null, null, false, null);
        }
    }

    private record PlayerSnapshot(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            String world,
            String biome,
            String uuid,
            String username
    ) {
        private String toJson() {
            return String.format(Locale.US,
                    "{\"x\": %.2f, \"y\": %.2f, \"z\": %.2f, \"yaw\": %.2f, \"pitch\": %.2f, \"world\": \"%s\", \"biome\": \"%s\", \"uuid\": \"%s\", \"username\": \"%s\"}",
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    escapeJson(world),
                    escapeJson(biome),
                    escapeJson(uuid),
                    escapeJson(username)
            );
        }
    }
}
