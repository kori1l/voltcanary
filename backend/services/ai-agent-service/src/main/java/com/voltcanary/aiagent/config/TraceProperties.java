package com.voltcanary.aiagent.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai.traces")
@Getter
public class TraceProperties {

    private int maxEvents = 256;
    private int maxPayloadBytes = 8192;
    private int maxTurnBytes = 262144;

    public void setMaxEvents(int value) {
        maxEvents = bounded(value, 16, 1024);
    }

    public void setMaxPayloadBytes(int value) {
        maxPayloadBytes = bounded(value, 512, 65536);
    }

    public void setMaxTurnBytes(int value) {
        maxTurnBytes = bounded(value, 4096, 1048576);
    }

    private int bounded(int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
            "Trace limit outside supported range"
        );
        return value;
    }
}
