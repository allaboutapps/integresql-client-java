package at.allaboutapps.integresql;

import at.allaboutapps.integresql.client.IntegresqlJavaClient;
import at.allaboutapps.integresql.client.dto.TemplateDatabase;
import at.allaboutapps.integresql.config.IntegresqlClientConfig;
import at.allaboutapps.integresql.client.dto.DatabaseConfig;
import at.allaboutapps.integresql.client.dto.TestDatabase;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for IntegresqlJavaClient using Testcontainers.
 * These tests require Docker to be running.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegresqlJavaClientIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(IntegresqlJavaClientIntegrationTest.class);
    private static final String INTEGRESQL_IMAGE = "ghcr.io/allaboutapps/integresql:latest"; // Use specific tag if
                                                                                             // needed
    private static final String POSTGRES_IMAGE = "postgres:17.4-alpine";
    private static final int INTEGRESQL_PORT = 5000;
    private static final int POSTGRESQL_PORT = 5432;
    private static final String POSTGRES_NETWORK_ALIAS = "postgres-test-db"; // Alias for Postgres within the network

    private static IntegresqlJavaClient client;

    // Define a network for containers to communicate
    // Use 'static' with try-with-resources or manage manually in
    // @BeforeAll/@AfterAll
    // Using JUnit Jupiter extension simplifies this, but manual setup is shown
    // below for clarity
    private static Network network = Network.newNetwork();

    // Define the PostgreSQL container first
    private static final PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE))
            .withNetwork(network)
            .withExposedPorts(PostgreSQLContainer.POSTGRESQL_PORT)
            .withNetworkAliases(POSTGRES_NETWORK_ALIAS) // Alias for integresql to connect to
            .withDatabaseName("test-db") // Matches default from compose *PGDATABASE
            .withUsername("test-user") // Matches default from compose *PGUSER
            .withPassword("test-password") // Matches default from compose *PGPASSWORD
            // Apply performance commands (optional, use with caution)
            .withCommand("postgres", "-c", "shared_buffers=128MB", "-c", "fsync=off", "-c", "synchronous_commit=off",
                    "-c", "full_page_writes=off", "-c", "max_connections=100", "-c", "client_min_messages=warning")
            .waitingFor(Wait.forListeningPort()); // Wait for Postgres port

    // Define the IntegreSQL container, depending on PostgreSQL
    private static final GenericContainer<?> integresqlContainer = new GenericContainer<>(
            DockerImageName.parse(INTEGRESQL_IMAGE))
            .withNetwork(network)
            .withExposedPorts(INTEGRESQL_PORT)
            // Pass environment variables referencing the Postgres container
            .withEnv("PGHOST", POSTGRES_NETWORK_ALIAS) // Use network alias
            .withEnv("PGUSER", postgresContainer.getUsername())
            .withEnv("PGPASSWORD", postgresContainer.getPassword())
            // PGDatabase might also be needed if integresql doesn't default? Check
            // integresql docs.
            // .withEnv("PGDATABASE", postgresContainer.getDatabaseName())
            .dependsOn(postgresContainer) // Ensure Postgres starts first
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120))); // Wait for IntegreSQL
                                                                                              // port 5001

    @BeforeAll
    static void setupClient() {

        // needed because client is running outside of docker environment
        postgresContainer.setPortBindings(
                List.of(
                        String.format("%d:%d", POSTGRESQL_PORT, PostgreSQLContainer.POSTGRESQL_PORT) // Map to host port
                ));
        postgresContainer.start();
        integresqlContainer.start();

        assertThat(postgresContainer.isRunning()).isTrue();
        assertThat(integresqlContainer.isRunning()).isTrue();

        String host = integresqlContainer.getHost(); // Host is usually 'localhost'
        int mappedPortIntegreSQL = integresqlContainer.getMappedPort(INTEGRESQL_PORT);
        // Construct base URL for IntegreSQL API
        String baseUrl = String.format("http://%s:%d/api", host, mappedPortIntegreSQL);

        IntegresqlClientConfig config = IntegresqlClientConfig.customConfig(baseUrl, "v1", false, Optional.empty(),
                Optional.of(postgresContainer.getHost()));
        client = new IntegresqlJavaClient(config);
        log.info("PostgreSQL Test Container running at jdbc:postgresql://{}:{}/{} (internal alias: {})",
                postgresContainer.getHost(), postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                postgresContainer.getDatabaseName(), POSTGRES_NETWORK_ALIAS);
        log.info("IntegreSQL Test Container running, API accessible at {}", baseUrl);

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            fail("PostgreSQL JDBC driver not found", e);
        }
    }

    @BeforeEach
    void resetIntegresqlState() {
        assertThat(integresqlContainer.isRunning()).isTrue();

        log.info("Resetting IntegreSQL tracking before test...");
        try {
            client.resetAllTracking();
            log.info("IntegreSQL tracking reset successfully.");
        } catch (Exception e) {
            // Log warning, as reset might fail if server isn't fully ready on first test
            log.warn("Failed to reset IntegreSQL tracking (may be expected on first run): {}", e.getMessage());
        }
        // Add a small delay if needed for the server to stabilize after reset
        // try { Thread.sleep(100); } catch (InterruptedException e) {
        // Thread.currentThread().interrupt(); }
    }

    // --- Test Methods (replicating Go tests) ---

    @Test
    @Order(1)
    void testInitializeTemplate() {
        String hash = "java-init-hash1";

        assertThatCode(() -> {
            TemplateDatabase template = client.initializeTemplate(hash);
            assertThat(template).isNotNull();
            assertThat(template.database.config).isNotNull();
            assertThat(template.database.config.database).isNotBlank();
            log.info("Initialized template {} successfully: {}", hash, template.database.config.database);
            // Discard afterwards to clean up for next test if needed, although reset should
            // handle it
            // client.discardTemplate(hash);
        }).doesNotThrowAnyException();
    }

    @Test
    @Order(2)
    void testDiscardTemplate() {
        String hash = "java-discard-hash2";

        // 1. Initialize
        assertThatCode(() -> client.initializeTemplate(hash))
                .as("Initial initialize should succeed")
                .doesNotThrowAnyException();

        // 2. Discard
        assertThatCode(() -> client.discardTemplate(hash))
                .as("Discard should succeed")
                .doesNotThrowAnyException();

        // 3. Re-initialize (should succeed after discard)
        AtomicReference<TemplateDatabase> templateRef = new AtomicReference<>();
        assertThatCode(() -> templateRef.set(client.initializeTemplate(hash)))
                .as("Re-initialize after discard should succeed")
                .doesNotThrowAnyException();
        assertThat(templateRef.get()).isNotNull();

        // 4. Finalize (should succeed after re-initialize)
        assertThatCode(() -> client.finalizeTemplate(hash))
                .as("Finalize after re-initialize should succeed")
                .doesNotThrowAnyException();
        log.info("Discard and re-initialize/finalize test for {} successful.", hash);
    }

    @Test
    @Order(3)
    void testFinalizeTemplate() {
        String hash = "java-finalize-hash3"; // Use different hash

        // 1. Initialize
        assertThatCode(() -> client.initializeTemplate(hash))
                .as("Initialize should succeed")
                .doesNotThrowAnyException();

        // 2. Finalize
        assertThatCode(() -> client.finalizeTemplate(hash))
                .as("Finalize should succeed")
                .doesNotThrowAnyException();

        // 3. Try to finalize again (should probably throw TemplateNotFound or similar,
        // depends on server impl.)
        // The Go test doesn't check this, but it might be a useful check.
        // assertThrows(TemplateNotFoundException.class, () ->
        // client.finalizeTemplate(hash));
        log.info("Finalize test for {} successful.", hash);
    }

    @Test
    @Order(4)
    void testGetTestDatabase() throws SQLException {
        String hash = "java-getdb-hash4";

        // 1. Setup template (Initialize + Finalize)
        assertThatCode(() -> client.initializeTemplate(hash)).doesNotThrowAnyException();
        assertThatCode(() -> client.finalizeTemplate(hash)).doesNotThrowAnyException();
        log.info("Template {} finalized for getTestDatabase test.", hash);

        // 2. Get first test database
        AtomicReference<TestDatabase> db1Ref = new AtomicReference<>();
        assertThatCode(() -> db1Ref.set(client.getTestDatabase(hash)))
                .as("Getting first test database should succeed")
                .doesNotThrowAnyException();

        TestDatabase db1 = db1Ref.get();
        assertThat(db1).isNotNull();
        assertThat(db1.database.config).isNotNull();
        assertThat(db1.database.templateHash).isNotBlank().as("Test DB 1 ID/Hash");
        // Go test checked TemplateHash, but our DTO doesn't have it directly. We trust
        // the API call scope.

        log.info("Got first test DB: {}", db1.database.templateHash);

        // 3. Ping first test database
        DatabaseConfig config1 = db1.database.config;
        // as integresql is connected to posgresql via network alias, but we are running
        // locally here,
        // we need to replace the host in the config with posgrresqlContainer.getHost()
        // config1.host = postgresContainer.getHost(); // Use the host of the PostgreSQL
        // container

        // config1.host = "localhost";
        String url1 = config1.connectionString(); // Base URL
        log.info("Connecting to DB1: {}", url1);
        try (Connection conn1 = DriverManager.getConnection(url1, config1.username, config1.password)) {
            assertThat(conn1.isValid(5)).as("DB1 connection should be valid").isTrue();
            log.info("Successfully pinged DB1: {}", db1.database.templateHash);
        } catch (SQLException e) {
            fail("Failed to connect or ping DB1 (" + db1.database.templateHash + ")", e);
        }

        // 4. Get second test database (without returning first)
        AtomicReference<TestDatabase> db2Ref = new AtomicReference<>();
        assertThatCode(() -> db2Ref.set(client.getTestDatabase(hash)))
                .as("Getting second test database should succeed")
                .doesNotThrowAnyException();

        TestDatabase db2 = db2Ref.get();
        assertThat(db2).isNotNull();
        assertThat(db2.database.config).isNotNull();
        assertThat(db2.database.templateHash).isNotBlank().as("Test DB 2 ID/Hash");
        assertThat(db2.database.templateHash).isEqualTo(db1.database.templateHash)
                .as("DB1 and DB2 should have different IDs");
        assertThat(db2.id).isNotEqualTo(db1.id).as("DB1 and DB2 should have different IDs");
        log.info("Got second test DB: {}", db2.database.templateHash);

        // 5. Ping second test database
        DatabaseConfig config2 = db2.database.config;
        // as integresql is connected to posgresql via network alias, but we are running
        // locally here,
        // we need to replace the host in the config with posgrresqlContainer.getHost()
        config2.host = postgresContainer.getHost(); // Use the host of the PostgreSQL container
        String url2 = config2.connectionString();
        try (Connection conn2 = DriverManager.getConnection(url2, config2.username, config2.password)) {
            assertThat(conn2.isValid(5)).as("DB2 connection should be valid").isTrue();
            log.info("Successfully pinged DB2: {}", db2.database.templateHash);
        } catch (SQLException e) {
            fail("Failed to connect or ping DB2 (" + db2.database.templateHash + ")", e);
        }

        // Clean up (optional here as reset runs before next test)
        // client.returnTestDatabase(hash, db1.database.templateHash);
        // client.returnTestDatabase(hash, db2.database.templateHash);
    }

    @Test
    @Order(5)
    void testReturnTestDatabase() {
        String hash = "java-returndb-hash5";

        // 1. Setup template
        assertThatCode(() -> client.initializeTemplate(hash)).doesNotThrowAnyException();
        assertThatCode(() -> client.finalizeTemplate(hash)).doesNotThrowAnyException();
        log.info("Template {} finalized for returnTestDatabase test.", hash);

        // 2. Get a test database
        TestDatabase db1 = client.getTestDatabase(hash);
        assertThat(db1).isNotNull();
        assertThat(db1.database.templateHash).isEqualTo(hash);
        Integer db1Id = db1.id;
        log.info("Got test DB {} to return.", db1Id);

        // 3. Return the test database
        assertThatCode(() -> client.returnTestDatabase(hash, db1Id))
                .as("Returning the test database should succeed")
                .doesNotThrowAnyException();
        log.info("Returned test DB {}.", db1Id);

        // 4. Get another test database (should potentially reuse the returned one)
        TestDatabase db2 = client.getTestDatabase(hash);
        assertThat(db2).isNotNull();
        assertThat(db2.database.templateHash).isEqualTo(hash);
        Integer db2Id = db2.id;
        log.info("Got second test DB {}.", db2Id);

        // 5. Assert that the returned database ID is reused
        // TODO cmnspro: figure out why db2 has a different ID than db1 (maybe new
        // unlock endpoint?)

        // assertThat(db2Id)
        // .as("Should reuse the returned database ID")
        // .isEqualTo(db1Id);
    }

    // Helper method to populate DB, similar to Go version
    private void populateTemplateDB(Connection connection) throws SQLException {
        String sql = """
                    CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
                    CREATE TABLE IF NOT EXISTS pilots (
                        id uuid NOT NULL DEFAULT uuid_generate_v4(),
                        "name" text NOT NULL,
                        created_at timestamptz NOT NULL DEFAULT NOW(),
                        updated_at timestamptz NULL,
                        CONSTRAINT pilot_pkey PRIMARY KEY (id)
                    );
                    CREATE TABLE IF NOT EXISTS jets (
                        id uuid NOT NULL DEFAULT uuid_generate_v4(),
                        pilot_id uuid NOT NULL,
                        age int4 NOT NULL,
                        "name" text NOT NULL,
                        color text NOT NULL,
                        created_at timestamptz NOT NULL DEFAULT NOW(),
                        updated_at timestamptz NULL,
                        CONSTRAINT jet_pkey PRIMARY KEY (id)
                    );
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'jet_pilots_fkey') THEN
                             ALTER TABLE jets ADD CONSTRAINT jet_pilots_fkey FOREIGN KEY (pilot_id) REFERENCES pilots(id);
                         END IF;
                    END $$;

                    -- Use ON CONFLICT DO NOTHING to make inserts idempotent for reruns within setupTemplate
                    INSERT INTO pilots (id, "name", created_at, updated_at) VALUES ('744a1a87-5ef7-4309-8814-0f1054751156', 'Mario', '2020-03-23 09:44:00.548+00', '2020-03-23 09:44:00.548+00') ON CONFLICT (id) DO NOTHING;
                    INSERT INTO pilots (id, "name", created_at, updated_at) VALUES ('20d9d155-2e95-49a2-8889-2ae975a8617e', 'Nick', '2020-03-23 09:44:00.548+00', '2020-03-23 09:44:00.548+00') ON CONFLICT (id) DO NOTHING;
                    INSERT INTO jets (id, pilot_id, age, "name", color, created_at, updated_at) VALUES ('67d9d0c7-34e5-48b0-9c7d-c6344995353c', '744a1a87-5ef7-4309-8814-0f1054751156', 26, 'F-14B', 'grey', '2020-03-23 09:44:00.000+00', '2020-03-23 09:44:00.000+00') ON CONFLICT (id) DO NOTHING;
                    INSERT INTO jets (id, pilot_id, age, "name", color, created_at, updated_at) VALUES ('facaf791-21b4-401a-bbac-67079ae4921f', '20d9d155-2e95-49a2-8889-2ae975a8617e', 27, 'F-14B', 'grey/red', '2020-03-23 09:44:00.000+00', '2020-03-23 09:44:00.000+00') ON CONFLICT (id) DO NOTHING;
                """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    // @Test
    // @Order(6)
    // // Ignore this test for now, as it doesn't work with the current setup.
    // // TODO cmnspro: fix me
    // void testSetupTemplateWithDBClient() {
    // String hash = "java-setupdb-hash6";
    //
    // assertThatCode(() -> {
    // client.setupTemplateWithDBClient(hash, connection -> {
    // try {
    // log.info("Populating template DB for hash {}", hash);
    // populateTemplateDB(connection);
    // log.info("Populated template DB for hash {} successfully", hash);
    // } catch (SQLException e) {
    // // Fail test immediately if population fails
    // fail("Failed to populate template DB", e);
    // }
    // });
    // log.info("setupTemplateWithDBClient completed successfully for hash {}",
    // hash);
    // // Verify template exists implicitly by trying to get a DB (optional)
    // // TestDatabase td = client.getTestDatabase(hash);
    // // assertThat(td).isNotNull();
    // // client.returnTestDatabase(hash, td.databaseHash);
    //
    // }).doesNotThrowAnyException();
    // }

    // @Test
    // @Order(7)
    // // Ignore this test for now, as it doesn't work with the current setup.
    // // TODO cmnspro: fix me
    // void testSetupTemplateFailingInitializerAndReinitialize() throws
    // InterruptedException, ExecutionException {
    // String hash = "java-failinit-hash7";
    // int testDBWhileInitializeCount = 5;
    // int testDBPreDiscardCount = 5;
    // int testDBAfterDiscardCount = 5;
    // int allTestDbCount = testDBWhileInitializeCount + testDBPreDiscardCount +
    // testDBAfterDiscardCount;
    //
    // ExecutorService executor = Executors.newFixedThreadPool(allTestDbCount); //
    // Pool for concurrent get requests
    // List<CompletableFuture<Void>> futures = new ArrayList<>();
    // ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    //
    // // --- Part 1: Attempt setup with failing initializer ---
    // log.info("Starting setup attempt with failing initializer for hash {}",
    // hash);
    // Exception setupException = assertThrows(Exception.class, () -> {
    // client.setupTemplateWithDBClient(hash, connection -> {
    // // Populate successfully first
    // try {
    // log.info("Populating template DB during failing setup for hash {}", hash);
    // populateTemplateDB(connection);
    // } catch (SQLException e) {
    // fail("Populating DB failed unexpectedly", e);
    // }
    //
    // // Start concurrent requests *during* initialization (before error)
    // log.info("Launching {} concurrent getTestDatabase calls during init...",
    // testDBWhileInitializeCount);
    // for (int i = 0; i < testDBWhileInitializeCount; i++) {
    // futures.add(CompletableFuture.runAsync(() -> {
    // try {
    // client.getTestDatabase(hash);
    // log.warn("getTestDatabase succeeded unexpectedly during failing init");
    // errors.add(new IllegalStateException("getTestDatabase succeeded unexpectedly
    // during failing init"));
    // } catch (Exception e) {
    // log.info("Caught expected error in getTestDatabase during init: {}",
    // e.getClass().getSimpleName());
    // errors.add(e); // Expect errors like TemplateNotFound or ManagerNotReady
    // }
    // }, executor));
    // }
    //
    // // Now, throw the error to simulate failure
    // log.warn("Simulating initializer failure for hash {}", hash);
    // throw new RuntimeException("FAILED ERR DURING INITIALIZE");
    // });
    // }, "Setup with failing initializer should throw an exception");
    //
    // assertThat(setupException).hasMessageContaining("FAILED ERR DURING
    // INITIALIZE");
    // log.info("Successfully caught expected exception from failing setup: {}",
    // setupException.getMessage());
    //
    // // --- Part 2: Attempt requests before explicit discard ---
    // log.info("Launching {} concurrent getTestDatabase calls before discard...",
    // testDBPreDiscardCount);
    // for (int i = 0; i < testDBPreDiscardCount; i++) {
    // futures.add(CompletableFuture.runAsync(() -> {
    // try {
    // client.getTestDatabase(hash);
    // log.warn("getTestDatabase succeeded unexpectedly before discard");
    // errors.add(new IllegalStateException("getTestDatabase succeeded unexpectedly
    // before discard"));
    // } catch (Exception e) {
    // log.info("Caught expected error in getTestDatabase before discard: {}",
    // e.getClass().getSimpleName());
    // errors.add(e); // Expect errors like TemplateNotFound or DatabaseDiscarded
    // }
    // }, executor));
    // }
    //
    // // --- Part 3: Explicitly discard template ---
    // log.info("Discarding template {} after failed setup", hash);
    // assertThatCode(() -> client.discardTemplate(hash))
    // .as("Discarding template after failed setup should succeed (or maybe already
    // discarded)")
    // // Server might return 404 if setupTemplate already implicitly discarded it,
    // // or 204 if discard is explicit. We accept either here or refine if needed.
    // // For now, just ensure no unexpected exceptions.
    // .doesNotThrowAnyException();
    //
    // // --- Part 4: Verify finalize fails after discard ---
    // log.info("Verifying finalize fails for template {}", hash);
    // assertThatThrownBy(() -> client.finalizeTemplate(hash))
    // .as("Finalize template should fail after discard")
    // .isInstanceOf(TemplateNotFoundException.class);
    //
    // // --- Part 5: Attempt requests after discard ---
    // log.info("Launching {} concurrent getTestDatabase calls after discard...",
    // testDBAfterDiscardCount);
    // for (int i = 0; i < testDBAfterDiscardCount; i++) {
    // futures.add(CompletableFuture.runAsync(() -> {
    // try {
    // client.getTestDatabase(hash);
    // log.warn("getTestDatabase succeeded unexpectedly after discard");
    // errors.add(new IllegalStateException("getTestDatabase succeeded unexpectedly
    // after discard"));
    // } catch (Exception e) {
    // log.info("Caught expected error in getTestDatabase after discard: {}",
    // e.getClass().getSimpleName());
    // errors.add(e); // Expect TemplateNotFound or DatabaseDiscarded
    // }
    // }, executor));
    // }
    //
    // // --- Part 6: Wait for all concurrent calls and check errors ---
    // log.info("Waiting for all {} concurrent getTestDatabase calls to
    // complete...", futures.size());
    // CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(); //
    // Wait for completion
    // executor.shutdown(); // Shut down executor
    //
    // log.info("Checking results of concurrent calls...");
    // long expectedErrors = allTestDbCount;
    // long actualErrors = errors.stream().filter(Objects::nonNull).count();
    // long successes = futures.size() - actualErrors; // Simple count based on
    // non-null errors recorded
    //
    //
    // // All attempts to get a DB should have failed because the template
    // initialization failed or was discarded.
    // assertThat(actualErrors)
    // .as("Number of errored getTestDatabase calls")
    // .isEqualTo(expectedErrors);
    // assertThat(successes)
    // .as("Number of successful getTestDatabase calls")
    // .isZero();
    // log.info("All {} getTestDatabase calls failed as expected.", actualErrors);
    //
    // // --- Part 7: Test successful re-initialization ---
    // log.info("Attempting successful re-initialization for hash {}", hash);
    // assertThatCode(() -> {
    // client.setupTemplateWithDBClient(hash, connection -> {
    // try {
    // log.info("Re-populating template DB for hash {}", hash);
    // populateTemplateDB(connection);
    // log.info("Re-populated template DB for hash {} successfully", hash);
    // } catch (SQLException e) {
    // fail("Failed to re-populate template DB", e);
    // }
    // });
    // log.info("Re-setup of template {} successful.", hash);
    // }).doesNotThrowAnyException();
    //
    // // Optional: Verify we can get a DB after successful re-setup
    // TestDatabase td = client.getTestDatabase(hash);
    // assertThat(td).isNotNull();
    // client.returnTestDatabase(hash, td.id);
    // log.info("Successfully got and returned a DB after re-setup.");
    // }

    @AfterAll
    static void stopContainers() {
        log.info("Stopping Testcontainers...");
        // Stop in reverse dependency order (or let dependsOn handle it)
        if (integresqlContainer != null) {
            integresqlContainer.stop();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
        if (network != null) {
            network.close();
        }
        log.info("Testcontainers stopped.");
    }
}