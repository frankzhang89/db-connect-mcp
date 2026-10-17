package com.example.dbconnectmcp.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Validates configured credentials at startup and compares bearer token digests. */
public final class BearerTokenVerifier {
    private final boolean enabled;
    private final byte[] digest;

    public BearerTokenVerifier(BearerAuthProperties properties) {
        enabled = properties.enabled();
        String token = properties.token();
        if (enabled && (token == null || token.length() > 4096 || !token.matches("[A-Za-z0-9\\-._~+/]+=*") || isPlaceholder(token))) {
            throw new IllegalArgumentException("MCP authentication is enabled; configure a non-placeholder dbmcp.auth.token in YAML.");
        }
        // Keep a fixed-length digest for comparison instead of comparing secret strings.
        digest = enabled ? hash(token) : new byte[0];
    }

    /** Accepts one bounded Bearer header, or bypasses checking when explicitly disabled. */
    public boolean accepts(String authorization) {
        if (!enabled) { return true; }
        if (authorization == null || authorization.length() > 8192 || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) { return false; }
        String token = authorization.substring(7);
        // Digest equality avoids ordinary String.equals() early-exit comparisons.
        return token.matches("[A-Za-z0-9\\-._~+/]+=*") && MessageDigest.isEqual(digest, hash(token));
    }

    private static boolean isPlaceholder(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        return value.contains("replace") || value.contains("change-me") || value.contains("changeme")
                || value.contains("your-token") || value.contains("placeholder") || value.equals("example");
    }

    private static byte[] hash(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable."); }
    }
}
