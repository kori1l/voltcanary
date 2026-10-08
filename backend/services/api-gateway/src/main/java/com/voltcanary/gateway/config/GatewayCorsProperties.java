package com.voltcanary.gateway.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.gateway.cors")
public class GatewayCorsProperties {

    private List<String> allowedOriginPatterns = new ArrayList<>(List.of("http://localhost:3000"));
    private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
    private List<String> allowedHeaders = new ArrayList<>(List.of("Authorization", "Content-Type", "X-Correlation-Id", "If-Match", "If-None-Match", "Idempotency-Key"));
    private List<String> exposedHeaders = new ArrayList<>(List.of("X-Correlation-Id", "ETag", "Retry-After"));
    private boolean allowCredentials = true;

    public List<String> getAllowedOriginPatterns() {
        return normalized(allowedOriginPatterns);
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    public List<String> getAllowedMethods() {
        return normalized(allowedMethods);
    }

    public void setAllowedMethods(List<String> allowedMethods) {
        this.allowedMethods = allowedMethods;
    }

    public List<String> getAllowedHeaders() {
        return normalized(allowedHeaders);
    }

    public void setAllowedHeaders(List<String> allowedHeaders) {
        this.allowedHeaders = allowedHeaders;
    }

    public List<String> getExposedHeaders() {
        return normalized(exposedHeaders);
    }

    public void setExposedHeaders(List<String> exposedHeaders) {
        this.exposedHeaders = exposedHeaders;
    }

    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    public void setAllowCredentials(boolean allowCredentials) {
        this.allowCredentials = allowCredentials;
    }

    private List<String> normalized(List<String> values) {
        return values.stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }
}
