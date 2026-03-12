package fr.sukikui.playercoordsapi.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;

/**
 * Utility methods for normalizing, validating, and comparing configured origins.
 */
public final class CorsUtils {
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";

    private CorsUtils() {
    }

    /**
     * Normalizes a request {@code Origin} header if it is valid.
     */
    public static Optional<String> normalizeOrigin(String rawOrigin) {
        return normalizeOrigin(rawOrigin, false);
    }

    /**
     * Normalizes a configured origin, allowing implicit scheme inference.
     */
    public static Optional<String> normalizeConfiguredOrigin(String rawOrigin) {
        return normalizeOrigin(rawOrigin, true);
    }

    /**
     * Builds and normalizes an origin from split host, port, and scheme-mode fields.
     */
    public static Optional<String> normalizeConfiguredOrigin(String host, String port, ModConfig.OriginSchemeMode schemeMode) {
        String authority = buildAuthority(host, port);

        if (authority.isEmpty()) {
            return Optional.empty();
        }

        return switch (schemeMode) {
            case AUTO -> normalizeConfiguredOrigin(authority);
            case HTTP -> normalizeOrigin(HTTP_SCHEME + "://" + authority);
            case HTTPS -> normalizeOrigin(HTTPS_SCHEME + "://" + authority);
        };
    }

    /**
     * Validates and canonicalizes an origin string.
     */
    private static Optional<String> normalizeOrigin(String rawOrigin, boolean allowImplicitScheme) {
        if (rawOrigin == null) {
            return Optional.empty();
        }

        String trimmedOrigin = rawOrigin.trim();

        if (trimmedOrigin.isEmpty() || trimmedOrigin.equalsIgnoreCase("null")) {
            return Optional.empty();
        }

        if (allowImplicitScheme && !trimmedOrigin.contains("://")) {
            Optional<String> inferredOrigin = inferOriginWithScheme(trimmedOrigin);

            if (inferredOrigin.isEmpty()) {
                return Optional.empty();
            }

            trimmedOrigin = inferredOrigin.get();
        }

        URI originUri;

        try {
            originUri = new URI(trimmedOrigin);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        String scheme = originUri.getScheme();
        String host = originUri.getHost();

        if (scheme == null || host == null) {
            return Optional.empty();
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);

        if (!normalizedScheme.equals(HTTP_SCHEME) && !normalizedScheme.equals(HTTPS_SCHEME)) {
            return Optional.empty();
        }

        if (originUri.getRawUserInfo() != null || originUri.getRawQuery() != null || originUri.getRawFragment() != null) {
            return Optional.empty();
        }

        String path = originUri.getRawPath();

        if (path != null && !path.isEmpty() && !path.equals("/")) {
            return Optional.empty();
        }

        int port = originUri.getPort();

        if (port < -1 || port > 65535) {
            return Optional.empty();
        }

        boolean isDefaultPort = port == -1
                || (normalizedScheme.equals(HTTP_SCHEME) && port == 80)
                || (normalizedScheme.equals(HTTPS_SCHEME) && port == 443);
        String normalizedHost = stripIpv6Brackets(host).toLowerCase(Locale.ROOT);
        String hostForOutput = normalizedHost.contains(":") ? "[" + normalizedHost + "]" : normalizedHost;

        if (isDefaultPort) {
            return Optional.of(normalizedScheme + "://" + hostForOutput);
        }

        return Optional.of(normalizedScheme + "://" + hostForOutput + ":" + port);
    }

    /**
     * Infers a scheme for configured origins that omit one.
     */
    private static Optional<String> inferOriginWithScheme(String rawOrigin) {
        String authority = prepareAuthority(rawOrigin);
        URI authorityUri;

        try {
            authorityUri = new URI("//" + authority);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        String host = authorityUri.getHost();

        if (host == null) {
            return Optional.empty();
        }

        String inferredScheme = inferScheme(host, authorityUri.getPort());
        return Optional.of(inferredScheme + "://" + authority);
    }

    /**
     * Converts raw host input into a URI authority, wrapping plain IPv6 addresses when needed.
     */
    private static String prepareAuthority(String rawOrigin) {
        String authority = rawOrigin.startsWith("//") ? rawOrigin.substring(2) : rawOrigin;

        if (!authority.contains("[") && !authority.contains("]") && authority.indexOf(':') != authority.lastIndexOf(':') && !authority.contains("/")) {
            return "[" + authority + "]";
        }

        return authority;
    }

    /**
     * Returns whether a host string points to a loopback address.
     */
    private static boolean isLoopbackHost(String host) {
        String normalizedHost = stripIpv6Brackets(host).toLowerCase(Locale.ROOT);

        return normalizedHost.equals("localhost")
                || normalizedHost.equals("::1")
                || normalizedHost.equals("0:0:0:0:0:0:0:1")
                || normalizedHost.startsWith("127.");
    }

    /**
     * Infers the default scheme to use for configured authorities without one.
     */
    private static String inferScheme(String host, int port) {
        if (port == 80) {
            return HTTP_SCHEME;
        }

        if (port == 443) {
            return HTTPS_SCHEME;
        }

        return isLoopbackHost(host) ? HTTP_SCHEME : HTTPS_SCHEME;
    }

    /**
     * Builds a normalized authority string from the UI host/port inputs.
     */
    private static String buildAuthority(String host, String port) {
        String trimmedHost = host == null ? "" : host.trim();

        if (trimmedHost.isEmpty() || trimmedHost.contains("://") || trimmedHost.contains("/") || trimmedHost.contains("?") || trimmedHost.contains("#")) {
            return "";
        }

        String authorityHost = trimmedHost;

        if (!authorityHost.contains("[") && !authorityHost.contains("]") && authorityHost.indexOf(':') != authorityHost.lastIndexOf(':')) {
            authorityHost = "[" + authorityHost + "]";
        }

        String trimmedPort = port == null ? "" : port.trim();

        if (trimmedPort.isEmpty()) {
            return authorityHost;
        }

        try {
            int parsedPort = Integer.parseInt(trimmedPort);

            if (parsedPort < 1 || parsedPort > 65535) {
                return "";
            }
        } catch (NumberFormatException e) {
            return "";
        }

        return authorityHost + ":" + trimmedPort;
    }

    /**
     * Removes square brackets from IPv6 literals when present.
     */
    private static String stripIpv6Brackets(String host) {
        if (host == null) {
            return null;
        }

        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }

        return host;
    }

    /**
     * Checks whether the given origin is allowed by the current policy.
     */
    public static boolean isOriginAllowed(ModConfig config, String origin) {
        Optional<String> normalizedOrigin = normalizeOrigin(origin);
        ModConfig.CorsPolicy corsPolicy = config.corsPolicy == null
                ? ModConfig.CorsPolicy.ALLOW_ALL
                : config.corsPolicy;
        List<String> allowedOrigins = config.allowedOrigins == null
                ? List.of()
                : config.allowedOrigins;

        if (normalizedOrigin.isEmpty()) {
            return false;
        }

        return switch (corsPolicy) {
            case ALLOW_ALL -> true;
            case LOCAL_WEB_APPS_ONLY -> isLoopbackOrigin(normalizedOrigin.get());
            case CUSTOM_WHITELIST -> allowedOrigins.contains(normalizedOrigin.get());
        };
    }

    /**
     * Resolves whether an origin points to a loopback host after normalization.
     */
    public static boolean isLoopbackOrigin(String origin) {
        Optional<String> normalizedOrigin = normalizeOrigin(origin);

        if (normalizedOrigin.isEmpty()) {
            return false;
        }

        try {
            URI originUri = new URI(normalizedOrigin.get());
            String host = stripIpv6Brackets(originUri.getHost());

            if (host == null) {
                return false;
            }

            if (host.equalsIgnoreCase("localhost")) {
                return true;
            }

            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (URISyntaxException | UnknownHostException e) {
            return false;
        }
    }

    /**
     * Normalizes and deduplicates a persisted list of origin strings.
     */
    public static List<String> normalizeConfiguredOrigins(List<String> origins) {
        Set<String> normalizedOrigins = new LinkedHashSet<>();

        for (String origin : origins) {
            normalizeConfiguredOrigin(origin).ifPresent(normalizedOrigins::add);
        }

        return new ArrayList<>(normalizedOrigins);
    }

    /**
     * Normalizes and deduplicates the origin list represented by config entries.
     */
    public static List<String> normalizeConfiguredOriginsFromEntries(List<ModConfig.OriginEntry> originEntries) {
        Set<String> normalizedOrigins = new LinkedHashSet<>();

        for (ModConfig.OriginEntry originEntry : originEntries) {
            if (originEntry == null || originEntry.schemeMode == null) {
                continue;
            }

            normalizeConfiguredOrigin(originEntry.host, originEntry.port, originEntry.schemeMode)
                    .ifPresent(normalizedOrigins::add);
        }

        return new ArrayList<>(normalizedOrigins);
    }

    /**
     * Converts normalized origin strings into editable config entries.
     */
    public static List<ModConfig.OriginEntry> createConfiguredOriginEntries(List<String> origins) {
        List<ModConfig.OriginEntry> originEntries = new ArrayList<>();

        for (String origin : origins) {
            createConfiguredOriginEntry(origin).ifPresent(originEntries::add);
        }

        return originEntries;
    }

    /**
     * Converts a single normalized origin string into an editable config entry.
     */
    public static Optional<ModConfig.OriginEntry> createConfiguredOriginEntry(String origin) {
        Optional<String> normalizedOrigin = normalizeOrigin(origin);

        if (normalizedOrigin.isEmpty()) {
            return Optional.empty();
        }

        try {
            URI originUri = new URI(normalizedOrigin.get());
            String scheme = originUri.getScheme();
            String host = stripIpv6Brackets(originUri.getHost());

            if (scheme == null || host == null) {
                return Optional.empty();
            }

            String port = originUri.getPort() == -1 ? "" : Integer.toString(originUri.getPort());
            String authority = buildAuthority(host, port);
            Optional<String> autoOrigin = normalizeConfiguredOrigin(authority);

            ModConfig.OriginEntry originEntry = new ModConfig.OriginEntry();
            originEntry.host = host;
            originEntry.port = port;
            originEntry.schemeMode = autoOrigin.isPresent() && autoOrigin.get().equals(normalizedOrigin.get())
                    ? ModConfig.OriginSchemeMode.AUTO
                    : ("http".equalsIgnoreCase(scheme) ? ModConfig.OriginSchemeMode.HTTP : ModConfig.OriginSchemeMode.HTTPS);

            return Optional.of(originEntry);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
