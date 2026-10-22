package com.example.dbconnectmcp.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class StdioEnvironmentPostProcessorTest {
    @Test
    void absentOrFalseSwitchLeavesHttpAndLoggingConfigurationAlone() {
        for (Map<String, Object> settings : java.util.List.of(Map.<String, Object>of(),
                Map.<String, Object>of("spring.ai.mcp.server.stdio", false))) {
            var environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("test", settings));
            new StdioEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
            assertFalse(environment.getPropertySources().contains("stdioTransportSettings"));
        }
    }

    @Test
    void stdioEnforcesNoWebAndStderrLoggingEvenWithConflictingProperties() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.ai.mcp.server.stdio", true, "spring.main.web-application-type", "servlet",
                "logging.config", "custom.xml", "spring.main.banner-mode", "console")));
        new StdioEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        assertEquals("none", environment.getProperty("spring.main.web-application-type"));
        assertEquals("off", environment.getProperty("spring.main.banner-mode"));
        assertEquals("classpath:logback-stdio.xml", environment.getProperty("logging.config"));
    }
}
