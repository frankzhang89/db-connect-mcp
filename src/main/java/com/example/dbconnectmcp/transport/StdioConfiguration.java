package com.example.dbconnectmcp.transport;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Reuses the SDK stdio implementation and adds application-level resource cleanup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
public class StdioConfiguration {
    @Bean
    StdioLifecycle stdioLifecycle(ConfigurableApplicationContext context) {
        return new StdioLifecycle(context);
    }

    @Bean
    McpServerTransportProviderBase stdioServerTransport(StdioLifecycle lifecycle,
            @Qualifier("mcpServerJsonMapper") JsonMapper mapper) {
        return new StdioServerTransportProvider(new JacksonMcpJsonMapper(mapper),
                lifecycle.observe(System.in), System.out);
    }
}
