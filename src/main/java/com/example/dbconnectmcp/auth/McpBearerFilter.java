package com.example.dbconnectmcp.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.web.filter.OncePerRequestFilter;

public final class McpBearerFilter extends OncePerRequestFilter {
    private final BearerTokenVerifier verifier;
    private final String endpoint;

    public McpBearerFilter(BearerTokenVerifier verifier, String endpoint) {
        this.verifier = verifier;
        if (endpoint == null || !endpoint.startsWith("/") || endpoint.matches(".*[?;#*].*")) {
            throw new IllegalArgumentException("Invalid MCP endpoint path.");
        }
        this.endpoint = endpoint.length() > 1 && endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) { path = request.getRequestURI().substring(request.getContextPath().length()); }
        path = path.replaceAll(";[^/]*", "");
        return !endpoint.equals("/") && !path.equals(endpoint) && !path.startsWith(endpoint + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (!verifier.accepts(headers.size() == 1 ? headers.getFirst() : null)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
