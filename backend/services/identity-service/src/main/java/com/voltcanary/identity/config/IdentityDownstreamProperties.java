package com.voltcanary.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.identity.downstream")
public class IdentityDownstreamProperties {
    private String deviceServiceBaseUrl = "http://localhost:8082";
    private String internalServiceToken = "dev-internal-token";
    private boolean deviceLifecycleSyncEnabled = false;
    private boolean deviceLifecycleReconciliationEnabled = false;
    private int deviceLifecycleReconciliationBatchSize = 100;

    public String getDeviceServiceBaseUrl() {
        return deviceServiceBaseUrl;
    }

    public void setDeviceServiceBaseUrl(String deviceServiceBaseUrl) {
        this.deviceServiceBaseUrl = deviceServiceBaseUrl;
    }

    public String getInternalServiceToken() {
        return internalServiceToken;
    }

    public void setInternalServiceToken(String internalServiceToken) {
        this.internalServiceToken = internalServiceToken;
    }

    public boolean isDeviceLifecycleSyncEnabled() {
        return deviceLifecycleSyncEnabled;
    }

    public void setDeviceLifecycleSyncEnabled(boolean deviceLifecycleSyncEnabled) {
        this.deviceLifecycleSyncEnabled = deviceLifecycleSyncEnabled;
    }

    public boolean isDeviceLifecycleReconciliationEnabled() {
        return deviceLifecycleReconciliationEnabled;
    }

    public void setDeviceLifecycleReconciliationEnabled(boolean deviceLifecycleReconciliationEnabled) {
        this.deviceLifecycleReconciliationEnabled = deviceLifecycleReconciliationEnabled;
    }

    public int getDeviceLifecycleReconciliationBatchSize() {
        return deviceLifecycleReconciliationBatchSize;
    }

    public void setDeviceLifecycleReconciliationBatchSize(int deviceLifecycleReconciliationBatchSize) {
        this.deviceLifecycleReconciliationBatchSize = deviceLifecycleReconciliationBatchSize;
    }
}
