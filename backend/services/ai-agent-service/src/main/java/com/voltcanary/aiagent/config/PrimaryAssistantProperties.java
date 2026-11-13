package com.voltcanary.aiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit local cutover; old immutable reviews remain readable/executable in either mode. */
@Component
@ConfigurationProperties(prefix = "app.ai.orchestration")
public class PrimaryAssistantProperties {
    private boolean enabled;
    private boolean nativeDataEnabled;
    public boolean isNativeDataEnabled() { return nativeDataEnabled; }
    public void setNativeDataEnabled(boolean nativeDataEnabled) { this.nativeDataEnabled = nativeDataEnabled; }
    private com.voltcanary.aiagent.service.PrimaryExecutionProfile profile = com.voltcanary.aiagent.service.PrimaryExecutionProfile.STANDARD;
    public com.voltcanary.aiagent.service.PrimaryExecutionProfile getProfile() { return profile; }
    public void setProfile(com.voltcanary.aiagent.service.PrimaryExecutionProfile profile) { this.profile = java.util.Objects.requireNonNull(profile); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
