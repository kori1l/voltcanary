package com.voltcanary.telemetry.observation;

import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit server opt-in and bounded first profile; no automatic capture of every source. */
@Component
@ConfigurationProperties(prefix = "app.observations")
@Getter
public class ObservationCaptureProperties {

    @Setter
    private boolean enabled;

    private Set<UUID> bindingIds = Set.of();
    private int pendingLimit = 10000;
    private long observationLimit = 100000;

    public void setBindingIds(Set<UUID> value) {
        if (value == null || value.size() > 64) throw new IllegalArgumentException(
            "Invalid observation binding limit"
        );
        bindingIds = Set.copyOf(value);
    }

    public void setPendingLimit(int value) {
        if (value < 1 || value > 10000) throw new IllegalArgumentException("Invalid pending limit");
        pendingLimit = value;
    }

    public void setObservationLimit(long value) {
        if (value < 1 || value > 1000000) throw new IllegalArgumentException(
            "Invalid observation limit"
        );
        observationLimit = value;
    }

    public boolean captures(UUID id) {
        return enabled && bindingIds.contains(id);
    }

    public String schemaGroup() {
        String selection = bindingIds
            .stream()
            .map(UUID::toString)
            .sorted()
            .collect(java.util.stream.Collectors.joining(","));
        return (
            "observation-schemas-" +
            UUID.nameUUIDFromBytes(selection.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }
}
