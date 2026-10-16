package com.example.dbconnectmcp.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpBearerFilterTest {
    private static final String TOKEN = "s7R3lNm92gQa0cV4TtX1";
    private final McpBearerFilter filter = new McpBearerFilter(
            new BearerTokenVerifier(new BearerAuthProperties(true, TOKEN)), "/db-mcp");

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE", "OPTIONS", "HEAD", "PUT"})
    void rejectsMissingCredentialsForEveryHttpMethod(String method) throws Exception {
        var request = new MockHttpServletRequest(method, "/db-mcp");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Unauthenticated request reached MCP"));
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertFalse(response.getContentAsString().contains(TOKEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer wrong", "Basic abc", "Bearer ", "Bearer  bad", "Bearer abc, Bearer def"})
    void rejectsInvalidHeadersWithoutEchoingThem(String header) throws Exception {
        var request = new MockHttpServletRequest("POST", "/db-mcp");
        request.addHeader("Authorization", header);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail());
        assertEquals(401, response.getStatus());
        assertEquals("{\"error\":\"unauthorized\"}", response.getContentAsString());
    }

    @Test
    void acceptsCorrectBearerWithCaseInsensitiveSchemeButCaseSensitiveToken() throws Exception {
        var request = new MockHttpServletRequest("POST", "/db-mcp");
        request.addHeader("Authorization", "bEaReR " + TOKEN);
        var response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> invoked.set(true));
        assertTrue(invoked.get());
        assertFalse(new BearerTokenVerifier(new BearerAuthProperties(true, TOKEN)).accepts("Bearer " + TOKEN.toLowerCase()));
    }

    @Test
    void queryParameterAndSessionIdCannotReplaceAuthorizationAndDuplicateHeadersAreRejected() throws Exception {
        var request = new MockHttpServletRequest("POST", "/db-mcp");
        request.setParameter("token", TOKEN);
        request.addHeader("Mcp-Session-Id", "existing-session");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail());
        assertEquals(401, response.getStatus());
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail());
        assertEquals(401, response.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/db-mcp/", "/db-mcp/session", "/db-mcp;session=1"})
    void protectsEndpointVariants(String path) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", path), response, (req, res) -> fail());
        assertEquals(401, response.getStatus());
    }

    @Test
    void followsCustomEndpointAndContextPath() throws Exception {
        var custom = new McpBearerFilter(new BearerTokenVerifier(new BearerAuthProperties(true, TOKEN)), "/custom/mcp");
        var request = new MockHttpServletRequest("POST", "/app/custom/mcp");
        request.setContextPath("/app");
        var response = new MockHttpServletResponse();
        custom.doFilter(request, response, (req, res) -> fail());
        assertEquals(401, response.getStatus());
        AtomicBoolean invoked = new AtomicBoolean();
        custom.doFilter(new MockHttpServletRequest("GET", "/health"), new MockHttpServletResponse(), (req, res) -> invoked.set(true));
        assertTrue(invoked.get());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "replace-with-a-random-long-token", "change-me", "token with spaces", "your-token"})
    void enabledAuthenticationRejectsMissingAndPlaceholderTokens(String token) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new BearerTokenVerifier(new BearerAuthProperties(true, token)));
        assertEquals("MCP authentication is enabled; configure a non-placeholder dbmcp.auth.token in YAML.", error.getMessage());
    }

    @Test
    void authenticationDefaultsToEnabledAndCanOnlyBeExplicitlyDisabled() {
        var runner = new ApplicationContextRunner().withUserConfiguration(McpAuthConfiguration.class);
        runner.run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("dbmcp.auth.token=" + TOKEN).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(BearerTokenVerifier.class).accepts(null));
        });
        runner.withPropertyValues("dbmcp.auth.enabled=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(BearerTokenVerifier.class).accepts(null));
        });
        assertFalse(new BearerAuthProperties(true, TOKEN).toString().contains(TOKEN));
    }
}
