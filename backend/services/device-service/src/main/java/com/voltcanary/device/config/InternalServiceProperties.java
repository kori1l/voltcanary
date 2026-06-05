package com.voltcanary.device.config;

import com.voltcanary.device.domain.Device;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Component
@ConfigurationProperties(prefix = "app.internal")
public class InternalServiceProperties {

    private String serviceToken = "dev-internal-token";

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }
}
