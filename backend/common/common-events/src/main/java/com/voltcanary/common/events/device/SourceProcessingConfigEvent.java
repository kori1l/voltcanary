package com.voltcanary.common.events.device;

import com.voltcanary.common.events.telemetry.VariableContract;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Secret-free immutable processing snapshot, distinct from source authorization. */
public record SourceProcessingConfigEvent(
    UUID eventId,
    String eventVersion,
    UUID accountId,
    UUID deviceId,
    UUID dataSourceId,
    String sourceId,
    long generation,
    String digest,
    boolean enabled,
    List<Binding> bindings,
    Instant occurredAt,
    String producer
) {
    public SourceProcessingConfigEvent {
        bindings = List.copyOf(bindings);
    }

    public record Binding(VariableContract contract, Map<String, String> inputPaths) {
        public Binding {
            inputPaths = Map.copyOf(inputPaths);
        }
    }
}
