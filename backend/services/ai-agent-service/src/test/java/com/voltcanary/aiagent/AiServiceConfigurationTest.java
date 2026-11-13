package com.voltcanary.aiagent;

import com.voltcanary.aiagent.config.AiAgentProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class AiServiceConfigurationTest {
    @Test void deployedServiceUrisBindUnderTheActualAiConfigurationPrefix() throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("deployment", Map.of(
                "DEVICE_SERVICE_URI", "http://devices:8082", "DASHBOARD_SERVICE_URI", "http://dashboards:8089",
                "TELEMETRY_SERVICE_URI", "http://telemetry:8084")));
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        var services = Binder.get(environment).bind("app.ai", AiAgentProperties.class).get().getServices();
        assertThat(services.getDeviceBaseUrl()).isEqualTo("http://devices:8082");
        assertThat(services.getDashboardBaseUrl()).isEqualTo("http://dashboards:8089");
        assertThat(services.getTelemetryBaseUrl()).isEqualTo("http://telemetry:8084");
    }
}
