package com.voltcanary.device.config;

import com.voltcanary.device.service.DeviceDeletionCoordinator;
import com.voltcanary.device.service.DeviceDeletionFinalization;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DeviceDeletionConfiguration {
    @Bean
    DeviceDeletionCoordinator deviceDeletionCoordinator(JdbcTemplate jdbc, DeviceDeletionFinalization finalization,
            @Value("${INTERNAL_SERVICE_TOKEN:dev-internal-token}") String token,
            @Value("${TELEMETRY_SERVICE_URI:http://localhost:8084}") String telemetry,
            @Value("${ANALYTICS_SERVICE_URI:http://localhost:8085}") String analytics,
            @Value("${ALERT_SERVICE_URI:http://localhost:8086}") String alerts,
            @Value("${SIMULATOR_SERVICE_URI:http://localhost:8087}") String simulator,
            @Value("${HSL_CONNECTOR_SERVICE_URI:http://hsl-connector:8091}") String hsl) {
        return new DeviceDeletionCoordinator(jdbc, finalization, token, telemetry, analytics, alerts, simulator, hsl);
    }
}
