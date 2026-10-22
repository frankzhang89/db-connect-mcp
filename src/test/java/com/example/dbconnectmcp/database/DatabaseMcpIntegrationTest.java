package com.example.dbconnectmcp.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.dbconnectmcp.DbConnectMcpApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs the real HTTP/MCP/Hikari pipeline against an in-process JDBC test double. */
@ExtendWith(OutputCaptureExtension.class)
class DatabaseMcpIntegrationTest {
    private static final String TOKEN = "n8wK4aLm7xQ2vPr5ZcT9";
    private static final String PASSWORD = "integration-database-password-marker";
    private Path directory;
    private final JsonMapper mapper = new JsonMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void createTestDirectory() throws Exception {
        // Keep synthetic configurations as build artifacts; do not load the user's YAML.
        directory = Files.createTempDirectory(Path.of("target"), "mcp-integration-");
        // Warm Mockito before the probe driver's deliberately short connection deadline.
        // Filter registration can now initialize the runtime earlier during Tomcat startup.
        mock(Connection.class);
        mock(DatabaseMetaData.class);
    }

    @Test
    void authenticatesAllRequestsSwitchesWithoutRestartAndRestoresYamlOnRestart(CapturedOutput output) throws Exception {
        Path config = configuration("one", TOKEN);
        byte[] original = Files.readAllBytes(config);
        try (var context = start(config)) {
            String endpoint = endpoint(context);
            for (String method : new String[] {"GET", "POST", "DELETE", "OPTIONS"}) {
                var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build();
                assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            for (String suffix : new String[] {"/", ";test=1"}) {
                assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(endpoint + suffix)).POST(HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }

            String session = initialize(endpoint);
            var tools = rpc(endpoint, session, "tools/list", Map.of()).body().path("result").path("tools");
            assertEquals(14, tools.size());
            JsonNode switchTool = null;
            for (JsonNode tool : tools) {
                assertFalse(tool.path("inputSchema").path("properties").has("expectedDatasourceId"));
                if (tool.path("name").asString().equals("db_switch_datasource")) { switchTool = tool; }
            }
            assertNotNull(switchTool);
            assertTrue(switchTool.path("inputSchema").path("properties").has("password"));

            var before = call(endpoint, session, "db_query", Map.of("sql", "SELECT id FROM users"));
            assertEquals(1, before.path("rows").get(0).path("id").asInt());
            var switched = call(endpoint, session, "db_switch_datasource", Map.of("jdbcUrl", "jdbc:probe:two", "username", "user", "password", "", "schema", "analytics"));
            assertTrue(switched.path("success").asBoolean());
            assertEquals(before.path("datasourceId").asString(), switched.path("previousDatasourceId").asString());
            assertEquals("analytics", switched.path("schema").asString());
            var after = call(endpoint, session, "db_query", Map.of("sql", "SELECT id FROM users"));
            assertEquals(2, after.path("rows").get(0).path("id").asInt());
            assertEquals(switched.path("datasourceId").asString(), after.path("datasourceId").asString());
            assertEquals(endpoint, endpoint(context));

            var failed = rpc(endpoint, session, "tools/call", Map.of("name", "db_switch_datasource", "arguments",
                    Map.of("jdbcUrl", "jdbc:probe:fail", "username", "bad-user", "password", PASSWORD))).body();
            assertTrue(failed.path("result").path("isError").asBoolean(), failed.toString());
            assertTrue(failed.toString().contains("AUTHENTICATION_FAILED"));
            assertFalse(failed.toString().contains(PASSWORD));
            var retained = call(endpoint, session, "db_query", Map.of("sql", "SELECT id FROM users"));
            assertEquals(after.path("datasourceId"), retained.path("datasourceId"));

            var resetSchema = call(endpoint, session, "db_switch_datasource", Map.of("jdbcUrl", "jdbc:probe:two", "username", "user", "password", ""));
            assertEquals("schema_two", resetSchema.path("schema").asString());
            assertNotEquals(after.path("datasourceId"), resetSchema.path("datasourceId"));

            // A valid existing session never substitutes for the token on subsequent requests.
            var request = HttpRequest.newBuilder(URI.create(endpoint)).header("Mcp-Session-Id", session)
                    .POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
            assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertArrayEquals(original, Files.readAllBytes(config));
        try (var restarted = start(config)) {
            String endpoint = endpoint(restarted);
            var result = call(endpoint, initialize(endpoint), "db_query", Map.of("sql", "SELECT id FROM users"));
            assertEquals(1, result.path("rows").get(0).path("id").asInt());
        }
        assertFalse(output.getAll().contains(TOKEN));
        assertFalse(output.getAll().contains(PASSWORD));
        assertFalse(output.getAll().contains("raw-driver-private-host"));
    }

    @Test
    void invalidStartupDatabaseOrMissingTokenPreventsStartupAndErrorsAreRedacted(CapturedOutput output) throws Exception {
        assertThrows(Exception.class, () -> { try (var ignored = start(configuration("fail", TOKEN))) { fail("Startup should fail"); } });
        int attempts = ProbeDriver.attempts.get();
        assertThrows(Exception.class, () -> { try (var ignored = start(configuration("one", ""))) { fail("Startup should fail"); } });
        assertEquals(attempts, ProbeDriver.attempts.get(), "Invalid auth should fail before the first DB attempt");
        assertFalse(output.getAll().contains(TOKEN));
        assertFalse(output.getAll().contains(PASSWORD));
        assertFalse(output.getAll().contains("raw-driver-private-host"));
    }

    @Test
    void transactionToolUsesRealConnectionsAndHiddenSessionContext() throws Exception {
        String address = "jdbc:h2:mem:mcp_transactions_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        Path config = configuration("transaction-test", TOKEN);
        Files.writeString(config, Files.readString(config).replace("jdbc:probe:transaction-test", address)
                .replace("max-active: 1", "max-active: 6"));
        try (Connection observer = java.sql.DriverManager.getConnection(address, "user", PASSWORD);
                var statement = observer.createStatement()) {
            statement.execute("CREATE TABLE account(id INT PRIMARY KEY, balance INT)");
            statement.execute("COMMENT ON TABLE account IS 'Account balances'");
            statement.execute("COMMENT ON COLUMN account.balance IS 'Current balance'");
            statement.execute("INSERT INTO account VALUES(1,1000)");
            try (var context = start(config)) {
                String endpoint = endpoint(context);
                String session = initialize(endpoint);
                String otherSession = initialize(endpoint);
                var description = call(endpoint, session, "db_describe_table", Map.of("tableName", "ACCOUNT"));
                assertEquals("Account balances", description.path("comment").asString());
                assertEquals("Current balance", description.path("columns").get(1).path("comment").asString());
                assertTrue(description.has("indexes"));
                assertTrue(description.path("foreignKeys").isArray());
                assertTrue(description.path("uniqueConstraints").isArray());
                assertTrue(description.path("columns").get(1).has("defaultValue"));
                assertTrue(description.path("columns").get(1).path("defaultValue").isNull());
                var listed = call(endpoint, session, "db_list_tables", Map.of());
                assertEquals("Account balances", listed.path("tables").get(0).path("comment").asString());
                var tools = rpc(endpoint, session, "tools/list", Map.of()).body().path("result").path("tools");
                JsonNode transactionTool = null;
                for (JsonNode tool : tools) {
                    if (tool.path("name").asString().equals("db_execute")) { transactionTool = tool; }
                }
                assertNotNull(transactionTool);
                var properties = transactionTool.path("inputSchema").path("properties");
                assertEquals(2, properties.size());
                assertTrue(properties.has("sql"));
                assertTrue(properties.has("transactionId"));
                assertFalse(properties.has("context"));

                var begun = call(endpoint, session, "db_execute", Map.of("sql", "BEGIN"));
                String id = begun.path("transactionId").asString();
                var own = call(endpoint, session, "db_execute", Map.of("transactionId", id,
                        "sql", "UPDATE account SET balance=900 WHERE id=1; SELECT balance FROM account WHERE id=1"));
                assertEquals(900, own.path("results").get(1).path("rows").get(0).path("BALANCE").asInt());
                try (var result = statement.executeQuery("SELECT balance FROM account WHERE id=1")) {
                    result.next(); assertEquals(1000, result.getInt(1));
                }
                var denied = rpc(endpoint, otherSession, "tools/call", Map.of("name", "db_execute",
                        "arguments", Map.of("sql", "COMMIT", "transactionId", id))).body();
                assertTrue(denied.path("result").path("isError").asBoolean());
                assertTrue(denied.toString().contains("TRANSACTION_NOT_FOUND"));
                call(endpoint, session, "db_execute", Map.of("sql", "ROLLBACK", "transactionId", id));

                id = call(endpoint, session, "db_execute", Map.of("sql", "BEGIN")).path("transactionId").asString();
                call(endpoint, session, "db_execute", Map.of("sql", "UPDATE account SET balance=800 WHERE id=1", "transactionId", id));
                call(endpoint, session, "db_execute", Map.of("sql", "COMMIT", "transactionId", id));
                try (var result = statement.executeQuery("SELECT balance FROM account WHERE id=1")) {
                    result.next(); assertEquals(800, result.getInt(1));
                }

                id = call(endpoint, session, "db_execute", Map.of("sql", "BEGIN")).path("transactionId").asString();
                call(endpoint, session, "db_execute", Map.of("sql", "UPDATE account SET balance=700 WHERE id=1", "transactionId", id));
                var delete = HttpRequest.newBuilder(URI.create(endpoint)).header("Authorization", "Bearer " + TOKEN)
                        .header("Mcp-Session-Id", session).DELETE().build();
                assertTrue(client.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode() < 300);
                // DELETE cleanup occurs after transport handling; await resource release.
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    try (var result = statement.executeQuery("SELECT balance FROM account WHERE id=1")) {
                        result.next(); assertEquals(800, result.getInt(1));
                    }
                    assertEquals(0, context.getBean(DatabaseTransactionManager.class).activeCount());
                });
            }
        }
    }

    private Path configuration(String database, String token) throws Exception {
        Path path = directory.resolve("config-" + database + "-" + System.nanoTime() + ".yml");
        Files.writeString(path, """
                server:
                  address: 127.0.0.1
                  port: 0
                spring:
                  ai:
                    mcp:
                      server:
                        protocol: STREAMABLE
                        type: SYNC
                        streamable-http:
                          mcp-endpoint: /private-mcp
                dbmcp:
                  auth:
                    token: "%s"
                  database:
                    url: jdbc:probe:%s
                    username: user
                    password: "%s"
                    min-idle: 0
                    max-active: 1
                    connection-timeout: 300
                  switch:
                    timeout-seconds: 3
                    validation-timeout-seconds: 1
                """.formatted(token, database, PASSWORD));
        return path;
    }

    private ConfigurableApplicationContext start(Path config) {
        return new SpringApplicationBuilder(DbConnectMcpApplication.class, ProbeConfiguration.class)
                .run("--spring.config.location=" + config.toUri(), "--spring.main.banner-mode=off");
    }

    private String endpoint(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort() + "/private-mcp";
    }

    private String initialize(String endpoint) throws Exception {
        var response = rpc(endpoint, null, "initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "integration-test", "version", "1")));
        assertTrue(response.body().has("result"));
        String session = response.response().headers().firstValue("Mcp-Session-Id").orElseThrow();
        rpc(endpoint, session, "notifications/initialized", Map.of());
        return session;
    }

    private record Rpc(JsonNode body, HttpResponse<String> response) { }

    private Rpc rpc(String endpoint, String session, String method, Map<String, ?> params) throws Exception {
        Map<String, ?> payload = method.startsWith("notifications/") ? Map.of("jsonrpc", "2.0", "method", method, "params", params)
                : Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params);
        var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (session != null) { request.header("Mcp-Session-Id", session); }
        var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 202, response.body());
        String body = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
            body = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).strip()).reduce("", (a, b) -> a + b);
        }
        return new Rpc(body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body), response);
    }

    private JsonNode call(String endpoint, String session, String name, Map<String, ?> args) throws Exception {
        var response = rpc(endpoint, session, "tools/call", Map.of("name", name, "arguments", args)).body();
        assertFalse(response.path("result").path("isError").asBoolean(), response.toString());
        return mapper.readTree(response.path("result").path("content").get(0).path("text").asString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean DatabaseDialect transactionTestDialect() {
            return new DatabaseDialect() {
                @Override public String name() { return "H2 test database"; }
                @Override public String urlPrefix() { return "jdbc:h2:"; }
                @Override public String driverClassName() { return "org.h2.Driver"; }
                @Override public String schemaPattern(Connection connection) throws SQLException { return connection.getSchema(); }
                @Override public boolean supportsGeneratedKeys() { return false; }
                // H2 TRUNCATE commits implicitly; do not borrow PostgreSQL's guarantee.
                @Override public boolean transactionalTruncate() { return false; }
            };
        }
        @Bean DatabaseDialect probeDialect() {
            return new PostgreSqlDialect() {
                @Override public String name() { return "Probe"; }
                @Override public String urlPrefix() { return "jdbc:probe:"; }
                @Override public String driverClassName() { return ProbeDriver.class.getName(); }
            };
        }
    }

    public static class ProbeDriver implements Driver {
        static final AtomicInteger attempts = new AtomicInteger();
        public ProbeDriver() { }
        @Override public Connection connect(String url, Properties info) throws SQLException {
            attempts.incrementAndGet();
            if (url.endsWith(":fail")) {
                throw new SQLException("raw-driver-private-host " + info.getProperty("password"), "28P01");
            }
            int id = url.endsWith(":one") ? 1 : 2;
            Connection connection = mock(Connection.class);
            DatabaseMetaData metadata = mock(DatabaseMetaData.class);
            when(connection.isValid(anyInt())).thenReturn(true);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(connection.getCatalog()).thenReturn("probe");
            when(connection.getSchema()).thenReturn(id == 1 ? "schema_one" : "schema_two");
            when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getSearchStringEscape()).thenReturn("\\");
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                PreparedStatement statement = mock(PreparedStatement.class);
                ResultSet rows = mock(ResultSet.class);
                ResultSetMetaData columns = mock(ResultSetMetaData.class);
                when(statement.executeQuery()).thenReturn(rows);
                when(rows.getMetaData()).thenReturn(columns);
                when(columns.getColumnCount()).thenReturn(1);
                when(columns.getColumnLabel(1)).thenReturn("id");
                when(rows.next()).thenReturn(true, false);
                when(rows.getObject(1)).thenReturn(id);
                return statement;
            });
            return connection;
        }
        @Override public boolean acceptsURL(String url) { return url.startsWith("jdbc:probe:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
