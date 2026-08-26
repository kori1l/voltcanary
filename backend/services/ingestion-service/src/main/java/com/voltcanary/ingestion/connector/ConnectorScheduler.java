package com.voltcanary.ingestion.connector;

import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

@Component
public class ConnectorScheduler {

    private final ConnectorRuntimeProperties properties;

    public ConnectorScheduler(ConnectorRuntimeProperties properties) {
        this.properties = properties;
    }

    public Duration pollingInterval(SourceRuntimeConfig config) {
        Object value = config.adapterConfig().getOrDefault("pollingInterval", config.adapterConfig().get("pollInterval"));
        if (value == null) {
            return properties.getDefaultPollingInterval();
        }
        if (value instanceof Number number) {
            return Duration.ofMillis(number.longValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return DurationStyle.detectAndParse(text.trim());
        }
        return properties.getDefaultPollingInterval();
    }
}
