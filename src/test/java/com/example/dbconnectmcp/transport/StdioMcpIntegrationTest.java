package com.example.dbconnectmcp.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Uses actual OS pipes and separate JVMs; stdout must remain pure MCP from startup to shutdown. */
@Timeout(90)
class StdioMcpIntegrationTest {
    private final JsonMapper json = new JsonMapper();

    @Test
    void discoversCallsCommitsRollsBackAndExitsOnEofWithoutOpeningHttp() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "stdio-integration-").toAbsolutePath();
        String url = "jdbc:h2:mem:transactions;DB_CLOSE_DELAY=-1;INIT=CREATE TABLE IF NOT EXISTS account(id INT PRIMARY KEY, balance INT)";
        // If the application accidentally starts Tomcat, this already-occupied port makes startup fail.
        try (var occupiedPort = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var server = start(directory, url, occupiedPort.getLocalPort(), true)) {
            server.initialize();
            server.call("db_execute_dml", Map.of("sql", "INSERT INTO account VALUES(1,1000)"));
            var tools = server.rpc("tools/list", Map.of()).path("result").path("tools");
            assertEquals(14, tools.size());
            for (JsonNode tool : tools) {
                if (tool.path("name").asString().equals("db_execute")) {
                    var properties = tool.path("inputSchema").path("properties");
                    assertFalse(properties.has("context"));
                    assertFalse(properties.has("sessionId"));
                }
            }
            var query = server.call("db_query", Map.of("sql", "SELECT balance FROM account WHERE id = ?",
                    "parameters", List.of(1), "maxRows", 1));
            assertEquals(1000, query.path("rows").get(0).path("BALANCE").asInt());
            String transaction = server.begin();
            server.execute(transaction, "UPDATE account SET balance=900 WHERE id=1");
            server.execute(transaction, "ROLLBACK");
            assertEquals(1000, server.balance());
            transaction = server.begin();
            server.execute(transaction, "UPDATE account SET balance=800 WHERE id=1");
            server.execute(transaction, "COMMIT");
            assertEquals(800, server.balance());
            transaction = server.begin();
            var result = server.execute(transaction, "UPDATE account SET balance=700 WHERE id=1; SELECT balance FROM account WHERE id=1");
            assertEquals(700, result.path("results").get(1).path("rows").get(0).path("BALANCE").asInt());
            assertTrue(server.process.isAlive(), "A completed tool call must leave the server alive");
            server.endInput();
            server.assertExited(0);
            assertTrue(server.stderr().contains("Stdio fixture ready"));
            assertTrue(server.stderr().contains("Stdio fixture debug enabled"), "logging.level overrides remain effective");
            assertTrue(server.stderr().contains("Shutdown completed"), "Hikari pools must close on EOF");
            assertFalse(server.stderr().contains("Tomcat started"));
            assertFalse(server.stderr().contains(":: Spring Boot ::"));
            server.assertProtocolOnly();
        }
        // The child opens an independent JDBC connection after Spring closes; the uncommitted 700 must be gone.
        assertEquals("800", Files.readString(directory.resolve("observed-balance.txt")));
    }

    @Test
    void eofBeforeInitializationStillClosesTheApplication() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "stdio-early-eof-").toAbsolutePath();
        try (var server = start(directory, "jdbc:h2:mem:early_eof", 0)) {
            server.endInput();
            server.assertExited(0);
            assertTrue(server.stderr().contains("Shutdown completed"));
            server.assertProtocolOnly();
        }
    }

    @Test
    void failedDatabaseStartupExitsWithDiagnosticsOnlyOnStderr() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "stdio-failure-").toAbsolutePath();
        try (var server = start(directory, "jdbc:unsupported:startup", 0)) {
            // Keep stdin open: failure itself, rather than EOF, must release resources and end the process.
            assertTrue(server.process.waitFor(30, TimeUnit.SECONDS), server.stderr());
            assertNotEquals(0, server.process.exitValue());
            server.joinReaders();
            assertTrue(server.stderr().contains("APPLICATION FAILED TO START")
                    || server.stderr().contains("Application run failed"));
            server.assertProtocolOnly();
        }
    }

    private Server start(Path directory, String url, int port) throws Exception {
        return start(directory, url, port, false);
    }

    private Server start(Path directory, String url, int port, boolean observeBalance) throws Exception {
        Path config = directory.resolve("application.yml");
        Files.writeString(config, """
                spring:
                  ai:
                    mcp:
                      server:
                        stdio: true
                        protocol: STREAMABLE
                        type: SYNC
                server:
                  address: 127.0.0.1
                  port: %d
                logging:
                  level:
                    com.example.dbconnectmcp.transport.StdioServerFixture: DEBUG
                dbmcp:
                  auth:
                    enabled: true
                    token: "replace-with-a-random-long-token"
                  database:
                    url: "%s"
                    username: sa
                    password: ""
                    max-active: 4
                    min-idle: 0
                    connection-timeout: 1000
                """.formatted(port, url));
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = new ArrayList<>(List.of(executable, "-Xms32m", "-Xmx192m", "-XX:ActiveProcessorCount=2",
                "-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log"), "-cp", classpath, StdioServerFixture.class.getName(),
                "--spring.config.location=" + config.toUri(),
                // Transport invariants must win over conflicting user options before logging/Web startup.
                "--spring.main.web-application-type=servlet", "--logging.config=classpath:does-not-exist.xml"));
        if (observeBalance) { command.add("--test.observed-balance-file=" + directory.resolve("observed-balance.txt")); }
        return new Server(new ProcessBuilder(command).start());
    }

    private final class Server implements AutoCloseable {
        final Process process;
        final BufferedWriter input;
        final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        final List<String> stdout = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Thread outputReader;
        final Thread errorReader;
        int id;
        boolean inputClosed;

        Server(Process process) {
            this.process = process;
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            outputReader = drain(process.getInputStream(), stdout, responses);
            errorReader = drain(process.getErrorStream(), errors, null);
        }

        private Thread drain(InputStream stream, List<String> lines, BlockingQueue<String> queue) {
            return Thread.ofPlatform().daemon(true).start(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                        if (queue != null) { queue.add(line); }
                    }
                }
                catch (java.io.IOException error) { errors.add("Pipe read failure: " + error); }
            });
        }

        void initialize() throws Exception {
            assertTrue(rpc("initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "stdio-integration", "version", "1"))).has("result"));
            send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        }

        void send(Map<String, ?> message) throws Exception {
            input.write(json.writeValueAsString(message));
            input.newLine();
            input.flush();
        }

        JsonNode rpc(String method, Map<String, ?> params) throws Exception {
            int requestId = ++id;
            send(Map.of("jsonrpc", "2.0", "id", requestId, "method", method, "params", params));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                String line = responses.poll(1, TimeUnit.SECONDS);
                if (line == null) {
                    assertTrue(process.isAlive(), "Child exited before response: " + stderr());
                    continue;
                }
                JsonNode response = json.readTree(line);
                assertEquals("2.0", response.path("jsonrpc").asString(), line);
                if (response.has("id") && response.path("id").asInt() == requestId) {
                    assertFalse(response.has("error"), response.toString());
                    return response;
                }
            }
            throw new AssertionError("Timed out waiting for " + method + ": " + stderr());
        }

        JsonNode call(String name, Map<String, ?> arguments) throws Exception {
            JsonNode result = rpc("tools/call", Map.of("name", name, "arguments", arguments)).path("result");
            assertFalse(result.path("isError").asBoolean(), result.toString());
            return json.readTree(result.path("content").get(0).path("text").asString());
        }

        String begin() throws Exception { return call("db_execute", Map.of("sql", "BEGIN")).path("transactionId").asString(); }
        JsonNode execute(String transaction, String sql) throws Exception {
            return call("db_execute", Map.of("transactionId", transaction, "sql", sql));
        }
        int balance() throws Exception {
            return call("db_query", Map.of("sql", "SELECT balance FROM account WHERE id=1"))
                    .path("rows").get(0).path("BALANCE").asInt();
        }
        String stderr() { return String.join("\n", errors); }
        void endInput() throws Exception {
            if (!inputClosed) { inputClosed = true; input.close(); }
        }
        void assertExited(int code) throws Exception {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Child did not exit after EOF: " + stderr());
            joinReaders();
            assertEquals(code, process.exitValue(), stderr());
        }
        void joinReaders() throws Exception { outputReader.join(3000); errorReader.join(3000); }
        void assertProtocolOnly() {
            for (String line : stdout) {
                JsonNode message = json.readTree(line);
                assertEquals("2.0", message.path("jsonrpc").asString(), "Non-MCP stdout: " + line);
            }
        }
        @Override
        public void close() throws Exception {
            try { endInput(); }
            finally {
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
                }
                joinReaders();
            }
        }
    }
}
