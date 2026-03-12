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
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side entrypoint that exposes the local HTTP API and tracks its runtime state.
 */
public class PlayerCoordsAPIClient implements ClientModInitializer {
    private static final String ALLOWED_METHODS = "GET, OPTIONS";
    private static final String DEFAULT_ALLOWED_HEADERS = "Content-Type, Authorization";
    private static final String ACCESS_DENIED_RESPONSE = "{\"error\": \"Access denied\"}";
    private static final String ORIGIN_NOT_ALLOWED_RESPONSE = "{\"error\": \"Origin not allowed\"}";
    private static final String NON_BROWSER_CLIENTS_NOT_ALLOWED_RESPONSE = "{\"error\": \"Non-browser local clients not allowed\"}";
    private static final String METHOD_NOT_ALLOWED_RESPONSE = "{\"error\": \"Method not allowed\"}";
    private static final String PLAYER_NOT_IN_WORLD_RESPONSE = "{\"error\": \"Player not in world\"}";
    private static volatile ServerStatus serverStatus = ServerStatus.disabled(ModConfig.DEFAULT_API_PORT);
    private static PlayerCoordsAPIClient instance;

    private HttpServer server;
    private ExecutorService serverExecutor;
    private boolean serverStarted = false;
    private boolean lastConfigEnabled;
    private int lastConfiguredPort;
    private boolean startBlockedUntilRefresh;
    private boolean retryRequested;
    private volatile PlayerSnapshot latestSnapshot;

    /**
     * Returns the current server status for the config screen.
     */
    public static ServerStatus getServerStatus() {
        return serverStatus;
    }

    /**
     * Requests a one-shot reevaluation of the current server configuration.
     */
    public static void requestServerRefresh() {
        if (instance != null) {
            instance.retryRequested = true;
        }
    }

    /**
     * Registers lifecycle hooks and starts the API immediately when enabled.
     */
    @Override
    public void onInitializeClient() {
        instance = this;
        ModConfig config = PlayerCoordsAPI.getConfig();
        lastConfigEnabled = config.enabled;
        lastConfiguredPort = ModConfig.normalizeApiPort(config.apiPort);
        serverStatus = lastConfigEnabled
                ? ServerStatus.stopped(lastConfiguredPort)
                : ServerStatus.disabled(lastConfiguredPort);

        if (lastConfigEnabled) {
            tryStartServer();
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            updateSnapshot(client);
            handleConfigState(PlayerCoordsAPI.getConfig());
        });

        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) -> updateSnapshot(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearSnapshot());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            clearSnapshot();
            stopServer();
        });

        PlayerCoordsAPI.LOGGER.info("Registered config monitor");
    }

    /**
     * Applies runtime changes when the user edits the config in-game.
     */
    private void handleConfigState(ModConfig config) {
        boolean configEnabled = config.enabled;
        int configuredPort = ModConfig.normalizeApiPort(config.apiPort);

        if (configuredPort != lastConfiguredPort) {
            lastConfiguredPort = configuredPort;
            lastConfigEnabled = configEnabled;
            clearStartBlock();

            if (serverStarted) {
                stopServer();
            }

            if (configEnabled) {
                tryStartServer();
            }

            return;
        }

        if (configEnabled != lastConfigEnabled) {
            lastConfigEnabled = configEnabled;
            clearStartBlock();

            if (configEnabled) {
                tryStartServer();
            } else {
                stopServer();
            }

            return;
        }

        if (retryRequested) {
            retryRequested = false;

            if (!configEnabled) {
                return;
            }

            clearStartBlock();
            tryStartServer();
            return;
        }

        if (configEnabled && !serverStarted && !startBlockedUntilRefresh) {
            tryStartServer();
        }
    }

    /**
     * Refreshes the cached player snapshot from the client thread.
     */
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

    /**
     * Clears the cached player snapshot when no valid in-game state is available.
     */
    private void clearSnapshot() {
        latestSnapshot = null;
    }

    /**
     * Attempts to start the embedded HTTP server with the current config.
     */
    private void tryStartServer() {
        if (serverStarted || startBlockedUntilRefresh) {
            return;
        }

        int port = lastConfiguredPort;

        try {
            PlayerCoordsAPI.LOGGER.info("Starting PlayerCoordsAPI HTTP server on port " + port);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/api/coords", this::handleCoordsRequest);
            serverExecutor = Executors.newSingleThreadExecutor();
            server.setExecutor(serverExecutor);
            server.start();
            serverStarted = true;
            clearStartBlock();
            serverStatus = ServerStatus.running(port);
            PlayerCoordsAPI.LOGGER.info("PlayerCoordsAPI HTTP server started successfully");
        } catch (IOException e) {
            cleanupServerResources();
            startBlockedUntilRefresh = true;
            String failureDetail = formatStartFailure(port, e);
            serverStatus = ServerStatus.failed(port, failureDetail);
            PlayerCoordsAPI.LOGGER.warn("Failed to start PlayerCoordsAPI HTTP server on port {}: {}", port, failureDetail);
        }
    }

    /**
     * Stops the embedded HTTP server and updates the exposed runtime status.
     */
    private void stopServer() {
        if (server == null && serverExecutor == null) {
            serverStatus = ServerStatus.disabled(lastConfiguredPort);
            clearStartBlock();
            return;
        }

        PlayerCoordsAPI.LOGGER.info("Stopping PlayerCoordsAPI HTTP server");
        cleanupServerResources();
        clearStartBlock();
        serverStatus = lastConfigEnabled
                ? ServerStatus.stopped(lastConfiguredPort)
                : ServerStatus.disabled(lastConfiguredPort);
        PlayerCoordsAPI.LOGGER.info("PlayerCoordsAPI HTTP server stopped successfully");
    }

    /**
     * Releases all server-side resources after stop or failed startup.
     */
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

    /**
     * Clears the startup failure gate so the next refresh can retry startup.
     */
    private void clearStartBlock() {
        startBlockedUntilRefresh = false;
    }

    /**
     * Maps low-level bind failures to short user-facing status details.
     */
    private static String formatStartFailure(int port, IOException exception) {
        String message = exception.getMessage();

        if (exception instanceof BindException && message != null) {
            if (message.equalsIgnoreCase("Address already in use")) {
                return "Port already in use";
            }

            if (message.equalsIgnoreCase("Permission denied")) {
                return "Permission denied";
            }
        }

        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }

        return message;
    }

    /**
     * Handles all requests to the local coordinates endpoint.
     */
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

    /**
     * Evaluates whether the request should receive CORS headers or be rejected.
     */
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

    /**
     * Mirrors requested CORS headers back to the client when present.
     */
    private String resolveAllowedHeaders(HttpExchange exchange) {
        String requestedHeaders = exchange.getRequestHeaders().getFirst("Access-Control-Request-Headers");

        if (requestedHeaders == null || requestedHeaders.isBlank()) {
            return DEFAULT_ALLOWED_HEADERS;
        }

        return requestedHeaders;
    }

    /**
     * Sends an HTTP response and attaches CORS headers when required.
     */
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

    /**
     * Escapes JSON string content for the lightweight manual serializer used by the API.
     */
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

    /**
     * Describes how a single request should be handled from a CORS perspective.
     */
    private record CorsDecision(boolean allowed, String allowOrigin, String allowHeaders, boolean varyByOrigin, String errorResponse) {
        /**
         * Creates a decision for an allowed request that should expose CORS headers.
         */
        private static CorsDecision allowed(String allowOrigin, String allowHeaders, boolean varyByOrigin) {
            return new CorsDecision(true, allowOrigin, allowHeaders, varyByOrigin, null);
        }

        /**
         * Creates a denied request decision with a JSON error payload.
         */
        private static CorsDecision denied(String errorResponse) {
            return new CorsDecision(false, null, null, false, errorResponse);
        }

        /**
         * Creates an allowed request decision that does not emit CORS headers.
         */
        private static CorsDecision noCors() {
            return new CorsDecision(true, null, null, false, null);
        }
    }

    /**
     * Immutable snapshot of the current player state served by the HTTP endpoint.
     */
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
        /**
         * Serializes the snapshot to the JSON payload returned by the API.
         */
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

    /**
     * Exposes the server state consumed by the config screen banner.
     */
    public record ServerStatus(State state, int port, String detail) {
        /**
         * High-level lifecycle states for the embedded API server.
         */
        public enum State {
            DISABLED,
            STOPPED,
            RUNNING,
            FAILED
        }

        /**
         * Creates a disabled status for the given configured port.
         */
        private static ServerStatus disabled(int port) {
            return new ServerStatus(State.DISABLED, port, null);
        }

        /**
         * Creates a stopped status for the given configured port.
         */
        private static ServerStatus stopped(int port) {
            return new ServerStatus(State.STOPPED, port, null);
        }

        /**
         * Creates a running status for the given configured port.
         */
        private static ServerStatus running(int port) {
            return new ServerStatus(State.RUNNING, port, null);
        }

        /**
         * Creates a failed status with a short user-facing detail string.
         */
        private static ServerStatus failed(int port, String detail) {
            return new ServerStatus(State.FAILED, port, detail);
        }
    }
}
