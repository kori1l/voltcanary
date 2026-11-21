package com.voltcanary.device.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.source-capture")
@Getter
@Setter
public class SourceCaptureProperties {
    private String telemetryServiceBaseUrl = "http://localhost:8084";
    private String internalServiceToken = "";
    private Duration timeout = Duration.ofSeconds(2);

}
