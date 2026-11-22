package com.voltcanary.alert.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.variables")
@Getter
@Setter
public class VariableRuleProperties {

    private String telemetryServiceBaseUrl = "http://localhost:8084";
}
