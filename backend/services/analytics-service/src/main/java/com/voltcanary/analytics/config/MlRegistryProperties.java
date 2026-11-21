package com.voltcanary.analytics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ml-registry")
@Getter
@Setter
public class MlRegistryProperties {

    private boolean demoSeedingEnabled;

}
