package com.voltcanary.ingestion.adapter.mqtt;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MqttTopicResolver {

    public Optional<String> sourceId(String topic) {
        if (topic == null || topic.isBlank()) {
            return Optional.empty();
        }
        String[] parts = topic.split("/");
        if (parts.length != 4
                || !"voltcanary".equals(parts[0])
                || !"sources".equals(parts[1])
                || !"telemetry".equals(parts[3])) {
            return Optional.empty();
        }
        return parts[2].isBlank() ? Optional.empty() : Optional.of(parts[2]);
    }
}
