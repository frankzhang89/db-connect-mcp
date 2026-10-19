package com.example.dbconnectmcp.database;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reclaims transactions after the MCP transport successfully accepts session deletion. */
public final class TransactionSessionFilter extends OncePerRequestFilter {
    private final DatabaseTransactionManager transactions;
    private final String endpoint;

    public TransactionSessionFilter(DatabaseTransactionManager transactions, String endpoint) {
        this.transactions = transactions;
        this.endpoint = endpoint.length() > 1 && endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        String path = request.getRequestURI().substring(request.getContextPath().length()).replaceAll(";[^/]*", "");
        String session = request.getHeader("Mcp-Session-Id");
        // A completed POST or disconnected SSE stream is not session termination.
        // Authentication runs earlier, and only transport-approved DELETEs trigger cleanup.
        if ("DELETE".equals(request.getMethod()) && (path.equals(endpoint) || path.equals(endpoint + "/"))
                && response.getStatus() >= 200 && response.getStatus() < 300 && session != null) {
            transactions.closeSession(session);
        }
    }
}
