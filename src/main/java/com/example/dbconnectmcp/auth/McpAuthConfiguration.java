package com.example.dbconnectmcp.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BearerAuthProperties.class)
public class McpAuthConfiguration {
    @Bean
    public BearerTokenVerifier mcpBearerVerifier(BearerAuthProperties properties) {
        return new BearerTokenVerifier(properties);
    }

    @Bean
    public FilterRegistrationBean<McpBearerFilter> mcpBearerFilter(BearerTokenVerifier verifier,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
        var registration = new FilterRegistrationBean<>(new McpBearerFilter(verifier, endpoint));
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR, DispatcherType.FORWARD);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
