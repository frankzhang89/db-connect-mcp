package com.example.dbconnectmcp.transport;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Selects stdio-only settings before Boot initializes logging and the web context. */
public final class StdioEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty("spring.ai.mcp.server.stdio", Boolean.class, false)) {
            // These are transport invariants, including when other settings were supplied on the command line.
            environment.getPropertySources().addFirst(new MapPropertySource("stdioTransportSettings", Map.of(
                    "spring.main.web-application-type", "none",
                    "spring.main.banner-mode", "off",
                    "logging.config", "classpath:logback-stdio.xml")));
        }
    }
}
