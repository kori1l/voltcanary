package com.voltcanary.device.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.variables")
public class VariableDefinitionProperties {
    private boolean activationEnabled;
    public boolean isActivationEnabled() { return activationEnabled; }
    public void setActivationEnabled(boolean value) { activationEnabled = value; }
}
