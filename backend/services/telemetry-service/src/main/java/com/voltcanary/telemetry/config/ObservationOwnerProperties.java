package com.voltcanary.telemetry.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.internal")
@Getter
@Setter
public class ObservationOwnerProperties {
    private String serviceToken = "";
    private String coverageAttestorToken = "";

}
