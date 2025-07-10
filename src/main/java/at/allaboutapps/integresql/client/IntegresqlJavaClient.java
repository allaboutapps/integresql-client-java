package at.allaboutapps.integresql.client;

import at.allaboutapps.integresql.client.dto.DatabaseConfig;
import at.allaboutapps.integresql.client.dto.TemplateDatabase;
import at.allaboutapps.integresql.client.dto.TestDatabase;
import at.allaboutapps.integresql.config.IntegresqlClientConfig;
import at.allaboutapps.integresql.exception.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer; // For setup callbacks

/**
 * A Java client for interacting with an IntegreSQL server API.
 * This client uses Java's built-in HttpClient (Java 11+).
 */
public class IntegresqlJavaClient implements AutoCloseable {

    private final URI baseApiUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration defaultTimeout = Duration.ofSeconds(30); // Default request timeout
    private final IntegresqlClientConfig config;

    /**
     * Creates a new client with the given configuration.
     *
     * @param config Client configuration.
     */
    public IntegresqlJavaClient(IntegresqlClientConfig config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // Or HTTP_2 if server supports it
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            // Construct base API URI: baseUrl + "/" + apiVersion (handle slashes carefully)
            String baseUrlStr = config.getBaseUrl();
            if (!baseUrlStr.endsWith("/")) {
                baseUrlStr += "/";
            }
            URI parsedBaseUrl = new URI(baseUrlStr);
            // Resolve API version path component
            this.baseApiUri = parsedBaseUrl.resolve(config.getApiVersion() + "/");

        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Base URL or API Version in config", e);
        }
    }

    /**
     * Creates a new client using default configuration loaded from environment
     * variables.
     * See {@link IntegresqlClientConfig#defaultConfigFromEnv()}.
     *
     * @return A new IntegresqlJavaClient instance.
     */
    public static IntegresqlJavaClient defaultClientFromEnv() {
        return new IntegresqlJavaClient(IntegresqlClientConfig.defaultConfigFromEnv());
    }

    // --- Private Helper Methods ---

    private URI resolveEndpoint(String endpoint) {
        // Ensure endpoint doesn't start with / if baseApiUri ends with /
        String cleanEndpoint = endpoint.startsWith("/") ? endpoint.substring(1) : endpoint;
        return this.baseApiUri.resolve(cleanEndpoint);
    }

    private HttpRequest.BodyPublisher createJsonBodyPublisher(Object body) {
        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            return HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IntegresqlException("Failed to serialize request body to JSON", e);
        }
    }

    private <T> T parseJsonResponse(HttpResponse<String> response, Class<T> responseType) throws IOException {
        String body = response.body();
        if (body == null || body.trim().isEmpty() || responseType == Void.class) {
            return null; // No body content or no type expected
        }
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JsonProcessingException e) {
            // Include response body snippet in error for debugging if possible/safe
            String bodySnippet = body.length() > 100 ? body.substring(0, 100) + "..." : body;
            throw new IOException(
                    "Failed to parse JSON response body: " + e.getMessage() + "; Body snippet: " + bodySnippet, e);
        }
    }

    private <T> ResponseWrapper<T> doRequestInternal(String method, String endpoint, Object requestBody,
            Class<T> responseType) {
        URI targetUri = resolveEndpoint(endpoint);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(targetUri)
                .timeout(defaultTimeout) // Apply default timeout
                .header("Accept", "application/json");

        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();
        if (requestBody != null) {
            requestBuilder.header("Content-Type", "application/json; charset=UTF-8");
            bodyPublisher = createJsonBodyPublisher(requestBody);
        }

        if (this.config.debug) { // Assuming 'Debug' is a public boolean field in IntegresqlClientConfig
            System.out.println("DEBUG IntegreSQL Request:");
            System.out.println("  URL: " + method.toUpperCase() + " " + targetUri);
            StringBuilder headersLog = new StringBuilder("  Headers: ");
            headersLog.append("Accept=application/json");
            if (requestBody != null) {
                headersLog.append(", Content-Type=application/json; charset=UTF-8");
            }
            System.out.println(headersLog.toString());

            if (requestBody != null) {
                try {
                    String jsonPayload = objectMapper.writeValueAsString(requestBody);
                    System.out.println("  Body: " + jsonPayload);
                } catch (JsonProcessingException e) {
                    System.err.println(
                            "DEBUG IntegreSQL: Failed to serialize request body for logging - " + e.getMessage());
                }
            } else {
                System.out.println("  Body: (empty)");
            }
        }

        // Set method and body publisher
        // Set method and body publisher
        requestBuilder.method(method.toUpperCase(), bodyPublisher);

        HttpRequest request = requestBuilder.build();

        try {
            // Send request and get response as String
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (this.config.debug) {
                System.out.println("DEBUG IntegreSQL Response:");
                System.out.println("  Status Code: " + response.statusCode());
                System.out.println("  Headers: " + response.headers().map()); // Log all headers
                System.out.println("  Body: " + response.body());
            }

            // Try parsing if status code indicates success and response type is expected
            T responseBody = null;
            // Only parse if not 202/204 and a specific response type (not Void) is expected
            if (response.statusCode() != 202 && response.statusCode() != 204 && responseType != null
                    && responseType != Void.class) {
                responseBody = parseJsonResponse(response, responseType);
            }

            return new ResponseWrapper<>(response.statusCode(), responseBody);

        } catch (IOException e) {
            // Network errors, parsing errors during response reading/parsing
            throw new IntegresqlException("HTTP request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupt status
            throw new IntegresqlException("HTTP request interrupted", e);
        }
    }

    // Helper class to wrap response status and body
    private static class ResponseWrapper<T> {
        final int statusCode;
        final T body;

        ResponseWrapper(int statusCode, T body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    // --- Public API Methods ---

    /**
     * Resets all tracked templates and test databases on the IntegreSQL server.
     * Corresponds to DELETE /admin/templates.
     * Requires admin access configured on the server side.
     */
    public void resetAllTracking() {
        ResponseWrapper<Void> response = doRequestInternal("DELETE", "/admin/templates", null, Void.class);

        if (response.statusCode != 204) {
            throw new IntegresqlException("Failed to reset all tracking", response.statusCode);
        }
        // Success (204 No Content)
    }

    /**
     * Requests the server to prepare a database template for the given hash.
     * If the template is already being prepared or is ready, specific exceptions
     * are thrown.
     * Corresponds to POST /templates.
     *
     * @param hash The template hash.
     * @return Details of the template database being initialized.
     * @throws TemplateAlreadyInitializedException if initialization is already in
     *                                             progress or finished.
     * @throws ManagerNotReadyException            if the manager service is
     *                                             unavailable.
     * @throws IntegresqlException                 for other errors (network,
     *                                             unexpected status).
     */
    public TemplateDatabase initializeTemplate(String hash) {
        Objects.requireNonNull(hash, "Template hash cannot be null");
        Map<String, String> payload = Map.of("hash", hash);

        // The Go client uses POST /templates, let's stick to that unless API doc
        // differs
        ResponseWrapper<TemplateDatabase> response = doRequestInternal("POST", "/templates", payload,
                TemplateDatabase.class);

        // Locked (StatusLocked in Go)
        // Service Unavailable
        return switch (response.statusCode) {
            case 200 -> // OK
                response.body;
            case 423 ->
                throw new TemplateAlreadyInitializedException(
                        "Template initialization already started or finished for hash: " + hash);
            case 503 -> throw new ManagerNotReadyException("IntegreSQL manager service is not ready.");
            default ->
                throw new IntegresqlException("Initialize template failed with unexpected status", response.statusCode);
        };
    }

    /**
     * Marks a template initialization as complete and makes it available for tests.
     * Corresponds to PUT /templates/{hash}.
     *
     * @param hash The template hash to finalize.
     * @throws TemplateNotFoundException if the template hash is not found (e.g.,
     *                                   never initialized or discarded).
     * @throws ManagerNotReadyException  if the manager service is unavailable.
     * @throws IntegresqlException       for other errors.
     */
    public void finalizeTemplate(String hash) {
        Objects.requireNonNull(hash, "Template hash cannot be null");
        String endpoint = String.format("/templates/%s", hash); // URL encoding handled by URI builder

        ResponseWrapper<Void> response = doRequestInternal("PUT", endpoint, null, Void.class);

        switch (response.statusCode) {
            case 204: // No Content
                return; // Success
            case 404: // Not Found
                throw new TemplateNotFoundException("Template not found for finalize: " + hash);
            case 503: // Service Unavailable
                throw new ManagerNotReadyException("IntegreSQL manager service is not ready.");
            default:
                throw new IntegresqlException("Finalize template failed with unexpected status", response.statusCode);
        }
    }

    /**
     * Discards a template during initialization (e.g., if setup fails).
     * Corresponds to DELETE /templates/{hash}.
     *
     * @param hash The template hash to discard.
     * @throws TemplateNotFoundException if the template hash is not found.
     * @throws ManagerNotReadyException  if the manager service is unavailable.
     * @throws IntegresqlException       for other errors.
     */
    public void discardTemplate(String hash) {
        Objects.requireNonNull(hash, "Template hash cannot be null");
        String endpoint = String.format("/templates/%s", hash);

        ResponseWrapper<Void> response = doRequestInternal("DELETE", endpoint, null, Void.class);

        switch (response.statusCode) {
            case 204: // No Content
                return; // Success
            case 404: // Not Found
                throw new TemplateNotFoundException("Template not found for discard: " + hash);
            case 503: // Service Unavailable
                throw new ManagerNotReadyException("IntegreSQL manager service is not ready.");
            default:
                throw new IntegresqlException("Discard template failed with unexpected status", response.statusCode);
        }
    }

    /**
     * High-level method to ensure a template is set up.
     * It calls {@link #initializeTemplate(String)}. If successful, it runs the
     * provided
     * initializer function with the template database's connection string, and then
     * calls
     * {@link #finalizeTemplate(String)}. If initialization fails, it attempts to
     * discard
     * the template. If the template was already initialized, it does nothing.
     *
     * @param hash        The template hash.
     * @param initializer A consumer function that takes the JDBC connection string
     *                    to perform setup (e.g., run migrations).
     *                    Any exception thrown by the initializer will cause the
     *                    setup to fail and the template to be discarded.
     * @throws Exception If any step fails (initialization, initializer function,
     *                   finalization, discard).
     *                   Specific exceptions like ManagerNotReadyException might be
     *                   thrown.
     */
    public void setupTemplate(String hash, Consumer<String> initializer) throws Exception {
        Objects.requireNonNull(hash, "Template hash cannot be null");
        Objects.requireNonNull(initializer, "Initializer function cannot be null");

        TemplateDatabase template = null;
        try {
            template = initializeTemplate(hash); // Throws TemplateAlreadyInitialized, ManagerNotReady, etc.

            // If we got here, initialization started successfully
            try {
                DatabaseConfig config = Objects.requireNonNull(template.database.config,
                        "Template database config is null");

                if (this.config.portOverride.isPresent()) {
                    config.port = this.config.portOverride.get();
                }

                if (this.config.hostOverride.isPresent()) {
                    config.host = this.config.hostOverride.get();
                }

                String connectionString = config.connectionString();
                // Add user/password to connection string for JDBC
                String user = Objects.requireNonNull(config.username, "Template username is null");
                String password = Objects.requireNonNull(config.password, "Template password is null");

                // Construct full JDBC URL (Note: DriverManager needs this format)
                String jdbcUrl = String.format("%s?user=%s&password=%s", connectionString, user, password);

                initializer.accept(jdbcUrl); // Run user's setup logic

            } catch (Exception e) {
                // Initializer failed, attempt to discard before re-throwing
                System.err.println("Initializer failed for template " + hash + ", attempting to discard.");
                try {
                    discardTemplate(hash);
                } catch (Exception discardEx) {
                    System.err.println("Failed to discard template " + hash + " after initializer failure: "
                            + discardEx.getMessage());
                    e.addSuppressed(discardEx); // Add discard error to original error
                }
                throw e; // Re-throw the original initializer exception
            }

            // Initializer succeeded, finalize the template
            finalizeTemplate(hash);

        } catch (TemplateAlreadyInitializedException e) {
            // This is not an error, template is already ready. Log maybe?
            System.out.println("Template " + hash + " is already initialized.");
            return; // Success (idempotent)
        }
    }

    /**
     * High-level method similar to {@link #setupTemplate(String, Consumer)}, but
     * provides
     * a standard JDBC {@link Connection} to the initializer function.
     * Requires a PostgreSQL JDBC driver to be available on the classpath.
     * The connection is automatically managed (opened and closed).
     *
     * @param hash        The template hash.
     * @param initializer A consumer function that takes the JDBC {@link Connection}
     *                    to perform setup.
     *                    Any exception thrown by the initializer will cause the
     *                    setup to fail and the template to be discarded.
     * @throws Exception             If any step fails (initialization, DB
     *                               connection, initializer function, finalization,
     *                               discard).
     * @throws IllegalStateException if the PostgreSQL JDBC driver is not found.
     */
    public void setupTemplateWithDBClient(String hash, Consumer<Connection> initializer) throws Exception {
        // Check for JDBC driver presence early
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "PostgreSQL JDBC driver not found on classpath. Please add 'org.postgresql:postgresql' dependency.",
                    e);
        }

        Objects.requireNonNull(hash, "Template hash cannot be null");
        Objects.requireNonNull(initializer, "Initializer function cannot be null");

        TemplateDatabase template = null;
        try {
            template = initializeTemplate(hash);

            // If we got here, initialization started successfully
            DatabaseConfig config = Objects.requireNonNull(template.database.config,
                    "Template database config is null");

            if (this.config.portOverride.isPresent()) {
                config.port = this.config.portOverride.get();
            }

            if (this.config.hostOverride.isPresent()) {
                config.host = this.config.hostOverride.get();
            }

            String connectionString = config.connectionString(); // Base URL: jdbc:postgresql://host:port/database
            String user = Objects.requireNonNull(config.username, "Template username is null");
            String password = Objects.requireNonNull(config.password, "Template password is null");

            // log connection details (optional)
            System.out.println("Connected to template database: " + connectionString);
            System.out.println("Using user: " + user);
            System.out.println("Template hash: " + hash);
            // overwrite connection string with localhost:5432

            // Use try-with-resources for automatic connection closing
            try (Connection connection = DriverManager.getConnection(connectionString, user, password)) {

                // log connection details (optional)
                System.out.println("Connected to template database: " + connectionString);
                System.out.println("Using user: " + user);
                System.out.println("Template hash: " + hash);

                // Optional: Check if connection is valid
                if (!connection.isValid(5)) { // Timeout 5 seconds
                    throw new SQLException("Failed to establish a valid connection to the template database.");
                }

                initializer.accept(connection); // Run user's setup logic

            } catch (Exception e) { // Catch SQLException from getConnection/isValid or any exception from
                                    // initializer
                System.err.println(
                        "Initializer or DB connection failed for template " + hash + ", attempting to discard.");
                try {
                    discardTemplate(hash);
                } catch (Exception discardEx) {
                    System.err.println(
                            "Failed to discard template " + hash + " after failure: " + discardEx.getMessage());
                    e.addSuppressed(discardEx);
                }
                throw e;
            }

            // Initializer succeeded, finalize the template
            finalizeTemplate(hash);

        } catch (TemplateAlreadyInitializedException e) {
            System.out.println("Template " + hash + " is already initialized.");
            return; // Success
        }
    }

    /**
     * Gets a unique, isolated test database based on a previously finalized
     * template hash.
     * Corresponds to GET /templates/{hash}/tests.
     *
     * @param templateHash The hash of the finalized template.
     * @return Details of the provisioned test database.
     * @throws TemplateNotFoundException  if the template hash is not found or not
     *                                    finalized.
     * @throws DatabaseDiscardedException if the template database was discarded
     *                                    during setup.
     * @throws ManagerNotReadyException   if the manager service is unavailable.
     * @throws IntegresqlException        for other errors.
     */
    public TestDatabase getTestDatabase(String templateHash) {
        Objects.requireNonNull(templateHash, "Template hash cannot be null");
        String endpoint = String.format("/templates/%s/tests", templateHash);

        ResponseWrapper<TestDatabase> response = doRequestInternal("GET", endpoint, null, TestDatabase.class);

        // Not Found
        // Gone
        // Service Unavailable
        return switch (response.statusCode) {
            case 200 -> // OK
                response.body;
            case 404 ->
                throw new TemplateNotFoundException(
                        "Template not found or not finalized for getTestDatabase: " + templateHash);
            case 410 ->
                throw new DatabaseDiscardedException(
                        "Template database was discarded, cannot get test database for hash: " + templateHash);
            case 503 -> throw new ManagerNotReadyException("IntegreSQL manager service is not ready.");
            default ->
                throw new IntegresqlException("Get test database failed with unexpected status", response.statusCode);
        };
    }

    /**
     * Returns a test database to the pool, marking it as no longer in use.
     * Corresponds to DELETE /templates/{hash}/tests/{id}.
     *
     * @param templateHash The hash of the template the test database was based on.
     * @param databaseId   The unique ID (or hash) of the test database instance
     *                     (from TestDatabase.databaseHash).
     * @throws TemplateNotFoundException if the template hash is not found.
     * @throws TestNotFoundException     if the specific test ID is not found for
     *                                   that template.
     * @throws ManagerNotReadyException  if the manager service is unavailable.
     * @throws IntegresqlException       for other errors.
     */
    public void returnTestDatabase(String templateHash, Integer databaseId) {
        Objects.requireNonNull(templateHash, "Template hash cannot be null");
        Objects.requireNonNull(databaseId, "Database ID/hash cannot be null");

        // Go client used int ID, but DTOs suggest ID might be the hash string. Adjust
        // if API uses int.
        String endpoint = String.format("/templates/%s/tests/%d/unlock", templateHash, databaseId);

        ResponseWrapper<Void> response = doRequestInternal("POST", endpoint, null, Void.class);

        switch (response.statusCode) {
            case 204: // No Content
                return; // Success
            case 404: // Not Found
                throw new TemplateNotFoundException("Template or Test Database not found for return: template="
                        + templateHash + ", dbId=" + databaseId);
            case 503: // Service Unavailable
                throw new ManagerNotReadyException("IntegreSQL manager service is not ready.");
            default:
                throw new IntegresqlException("Return test database failed with unexpected status",
                        response.statusCode);
        }
    }

    /**
     * Closes the underlying HttpClient. Optional cleanup.
     * HttpClient shutdown is often managed globally, but provided for completeness.
     */
    @Override
    public void close() {
        // HttpClient doesn't have a direct close(). Shutdown is typically done
        // via its executor if a custom one was provided. Java 18+ has .close()
        // If using default executor, it might be shared. Explicit shutdown usually not
        // needed.
        System.out.println(
                "IntegresqlJavaClient close() called - Note: HttpClient cleanup might be managed elsewhere or automatic.");
    }
}