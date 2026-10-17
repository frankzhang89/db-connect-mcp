package com.example.dbconnectmcp.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Server-wide bearer authentication settings; authentication is enabled by default. */
@ConfigurationProperties("dbmcp.auth")
public record BearerAuthProperties(@DefaultValue("true") boolean enabled, String token) {
    @Override
    // Avoid exposing the configured secret through record diagnostics or logging.
    public String toString() { return "BearerAuthProperties[enabled=" + enabled + ", token=redacted]"; }
}
