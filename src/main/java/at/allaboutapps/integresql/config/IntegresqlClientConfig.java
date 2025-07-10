package at.allaboutapps.integresql.config;

import java.util.Optional;

import at.allaboutapps.integresql.util.EnvUtil;

public class IntegresqlClientConfig {

    private final String baseUrl;
    private final String apiVersion;
    public final Boolean debug;
    public final Optional<Integer> portOverride;
    public final Optional<String> hostOverride;

    // Private constructor for builder pattern or factory method
    private IntegresqlClientConfig(String baseUrl, String apiVersion, Boolean debug, Optional<Integer> portOverride,
            Optional<String> hostOverride) {
        this.baseUrl = baseUrl;
        this.apiVersion = apiVersion;
        this.debug = debug != null ? debug : false;
        this.portOverride = portOverride != null ? portOverride : Optional.empty();
        this.hostOverride = hostOverride != null ? hostOverride : Optional.empty();
    }

    // --- Getters ---
    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    // --- Factory Method ---

    /**
     * Creates a default configuration by reading environment variables.
     * Reads "INTEGRESQL_CLIENT_BASE_URL" (default:
     * "<a href="http://integresql:5000/api">...</a>")
     * Reads "INTEGRESQL_CLIENT_API_VERSION" (default: "v1")
     *
     * @return A new IntegresqlClientConfig instance with default or
     *         environment-provided values.
     */
    public static IntegresqlClientConfig defaultConfigFromEnv() {
        String envBaseUrl = EnvUtil.getEnv("INTEGRESQL_CLIENT_BASE_URL", "http://integresql:5000/api");
        String envApiVersion = EnvUtil.getEnv("INTEGRESQL_CLIENT_API_VERSION", "v1");
        Boolean envDebug = EnvUtil.getEnvAsBool("INTEGRESQL_CLIENT_DEBUG", false);
        return new IntegresqlClientConfig(envBaseUrl, envApiVersion, envDebug, Optional.empty(), Optional.empty());
    }

    /**
     * Creates a configuration with specific values.
     *
     * @param baseUrl    The base URL of the IntegreSQL API (e.g.,
     *                   "<a href="http://localhost:5000/api">...</a>").
     * @param apiVersion The API version string (e.g., "v1").
     * @return A new IntegresqlClientConfig instance.
     */
    public static IntegresqlClientConfig customConfig(String baseUrl, String apiVersion, Boolean debug,
            Optional<Integer> portOverride, Optional<String> hostOverride) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Base URL cannot be null or empty");
        }
        if (apiVersion == null || apiVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("API version cannot be null or empty");
        }
        if (debug == null) {
            debug = false; // Default to false if not provided
        }
        return new IntegresqlClientConfig(baseUrl, apiVersion, debug, portOverride, hostOverride);
    }
}