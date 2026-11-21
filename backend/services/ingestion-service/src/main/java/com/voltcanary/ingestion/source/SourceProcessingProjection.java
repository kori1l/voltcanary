package com.voltcanary.ingestion.source;

import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.common.events.telemetry.ProcessingContext;
import com.voltcanary.ingestion.config.VariableProcessingProperties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class SourceProcessingProjection {
    private final Map<UUID, SourceProcessingConfigEvent> snapshots = new ConcurrentHashMap<>();
    private final VariableProcessingProperties properties;

    public void apply(SourceProcessingConfigEvent event) {
        if (event == null || event.dataSourceId() == null || event.accountId() == null
                || event.deviceId() == null || event.generation() < 1 || event.digest() == null
                || !event.digest().matches("[a-f0-9]{64}") || event.bindings().size() > 32) {
            throw new IllegalArgumentException("Invalid processing snapshot");
        }
        snapshots.compute(event.dataSourceId(), (id, old) -> {
            if (old != null && old.generation() == event.generation() && !old.digest().equals(event.digest())) {
                throw new IllegalArgumentException("Conflicting processing snapshot");
            }
            return old != null && old.generation() > event.generation() ? old : event;
        });
    }

    public ProcessingContext pin(ResolvedSource source) {
        Object required = source.mapping().get("processingGeneration");
        if (required == null) return null;
        if (!properties.isEnabled()) unavailable();
        var snapshot = snapshots.get(source.dataSourceId());
        if (!(required instanceof Number generation) || snapshot == null
                || snapshot.generation() != generation.longValue()
                || !snapshot.digest().equals(source.mapping().get("processingDigest"))
                || !snapshot.accountId().equals(source.accountId())
                || !snapshot.deviceId().equals(source.deviceId())) unavailable();
        return new ProcessingContext(source.dataSourceId(), snapshot.generation(), snapshot.digest(), "v1");
    }

    private void unavailable() {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Source processing configuration is propagating");
    }

    public void validateInputs(ResolvedSource source, Map<String, Object> metrics) {
        validateInputs(source, metrics, pin(source));
    }

    public void validateInputs(ResolvedSource source, Map<String, Object> metrics, ProcessingContext context) {
        if (!source.mapping().containsKey("processingGeneration")) return;
        var snapshot = snapshots.get(source.dataSourceId());
        if (snapshot == null || context == null || snapshot.generation() != context.generation()
                || !snapshot.digest().equals(context.digest())) unavailable();
        final Map<String, Map<String, Object>> configured;
        try { configured = com.voltcanary.common.events.telemetry.ConfiguredFields.values(metrics); }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid configured numeric input", invalid);
        }
        for (var binding : snapshot.bindings()) {
            for (var input : binding.contract().inputs().entrySet()) {
                String path = binding.inputPaths().get(input.getKey());
                Object value = metrics.get(path);
                if (value == null && configured.containsKey(path)) value = configured.get(path).get("value");
                if (value == null) continue; // Missing aliases produce explicit per-variable outcomes in telemetry.
                var contract = input.getValue();
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || contract.minimum() != null && number.doubleValue() < contract.minimum()
                        || contract.maximum() != null && number.doubleValue() > contract.maximum()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid configured numeric input");
                }
            }
        }
    }
}
