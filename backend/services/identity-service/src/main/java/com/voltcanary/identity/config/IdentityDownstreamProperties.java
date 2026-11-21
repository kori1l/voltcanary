package com.voltcanary.identity.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.identity.downstream")
@Getter
@Setter
public class IdentityDownstreamProperties {
    private String deviceServiceBaseUrl = "http://localhost:8082";
    private String internalServiceToken = "dev-internal-token";
    private boolean deviceLifecycleSyncEnabled = false;
    private boolean deviceLifecycleReconciliationEnabled = false;
    private int deviceLifecycleReconciliationBatchSize = 100;

}
