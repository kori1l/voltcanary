package com.voltcanary.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ml-registry")
public class MlRegistryProperties {

    private boolean demoSeedingEnabled;

    public boolean isDemoSeedingEnabled() {
        return demoSeedingEnabled;
    }

    public void setDemoSeedingEnabled(boolean demoSeedingEnabled) {
        this.demoSeedingEnabled = demoSeedingEnabled;
    }
}
