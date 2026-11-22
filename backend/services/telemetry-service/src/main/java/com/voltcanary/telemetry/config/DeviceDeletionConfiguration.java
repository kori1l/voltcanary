package com.voltcanary.telemetry.config;

import com.voltcanary.common.web.DeviceDeletionAuthorization;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DeviceDeletionConfiguration {

    @Bean
    DeviceDeletionAuthorization deviceDeletionAuthorization(
        @Value("${DEVICE_SERVICE_URI:http://localhost:8082}") String devices,
        @Value("${INTERNAL_SERVICE_TOKEN:dev-internal-token}") String token
    ) {
        return new DeviceDeletionAuthorization(devices, token);
    }
}
