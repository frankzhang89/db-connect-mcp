package com.example.dbconnectmcp.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("dbmcp.auth")
public record BearerAuthProperties(@DefaultValue("true") boolean enabled, String token) {
    @Override
    public String toString() { return "BearerAuthProperties[enabled=" + enabled + ", token=redacted]"; }
}
