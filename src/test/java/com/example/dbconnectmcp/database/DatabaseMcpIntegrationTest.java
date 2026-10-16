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
            assertEquals(13, tools.size());
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
