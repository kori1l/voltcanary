package com.voltcanary.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit local cutover; old immutable reviews remain readable/executable in either mode. */
@Component
@ConfigurationProperties(prefix = "app.ai.orchestration")
@Getter
public class PrimaryAssistantProperties {

    @Setter
    private boolean enabled;

    @Setter
    private boolean nativeDataEnabled;

    private com.voltcanary.aiagent.service.PrimaryExecutionProfile profile =
        com.voltcanary.aiagent.service.PrimaryExecutionProfile.STANDARD;

    public void setProfile(com.voltcanary.aiagent.service.PrimaryExecutionProfile profile) {
        this.profile = java.util.Objects.requireNonNull(profile);
    }
}
