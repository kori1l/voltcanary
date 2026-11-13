package com.voltcanary.telemetry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.internal")
public class ObservationOwnerProperties {
    private String serviceToken = "";
    private String coverageAttestorToken = "";
    public String getServiceToken() { return serviceToken; }
    public void setServiceToken(String value) { serviceToken = value; }
    public String getCoverageAttestorToken() { return coverageAttestorToken; }
    public void setCoverageAttestorToken(String value) { coverageAttestorToken = value; }
}
