package com.voltcanary.device.variable.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.device.event.DeviceOutboxService;
import com.voltcanary.device.variable.adapter.VariableBindingRows;
import com.voltcanary.device.variable.port.VariableStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VariableBundles {

    private final VariableBindingRows rows;
    private final VariableStore variables;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DeviceOutboxService outbox;
    private final com.voltcanary.device.repository.DataSourceRepository sources;
    private final com.voltcanary.device.event.SourceRuntimeConfigEventPublisher runtime;

    /** Caller holds the source row lock in the same transaction. */
    public SourceProcessingConfigEvent rebuild(VariableBindingRows.Source source) {
        List<SourceProcessingConfigEvent.Binding> bindings = rows
            .bindings(source.sourceId())
            .stream()
            .filter(binding -> binding.enabled())
            .map(binding ->
                new SourceProcessingConfigEvent.Binding(
                    variables.revision(binding.variableId(), binding.revision()).contract(),
                    normalizedPaths(source, binding.inputPaths())
                )
            )
            .toList();
        long nodes = bindings
            .stream()
            .mapToInt(binding -> count(binding.contract().expression()))
            .sum();
        if (bindings.size() > 32 || nodes > 4096) throw new IllegalArgumentException(
            "Source calculation budget exceeded"
        );
        Long latest = jdbc.queryForObject(
            "select max(generation) from source_processing_bundles where data_source_id=?",
            Long.class,
            source.sourceId()
        );
        long generation = latest == null ? 1 : latest + 1;
        boolean enabled = source.active() && !bindings.isEmpty();
        String digest = digest(List.of(source.sourceId(), generation, enabled, bindings));
        SourceProcessingConfigEvent event = new SourceProcessingConfigEvent(
            UUID.randomUUID(),
            "v1",
            source.accountId(),
            source.deviceId(),
            source.sourceId(),
            source.externalId(),
            generation,
            digest,
            enabled,
            bindings,
            Instant.now(),
            "device-service"
        );
        jdbc.update(
            """
            insert into source_processing_bundles
            (data_source_id,generation,account_id,device_id,digest,bundle_json,created_at) values(?,?,?,?,?,?,?)
            """,
            source.sourceId(),
            generation,
            source.accountId(),
            source.deviceId(),
            digest,
            json(event),
            Timestamp.from(event.occurredAt())
        );
        // Durable intent is retained even when the Kafka worker is disabled in local/test mode.
        outbox.enqueue(
            "processing:" + source.sourceId() + ":" + generation,
            "source.processing-config.v1",
            source.sourceId().toString(),
            event
        );
        var entity = sources.findById(source.sourceId()).orElseThrow();
        entity.setUpdatedAt(Instant.now());
        runtime.publish(source.accountId(), sources.saveAndFlush(entity));
        return event;
    }

    private int count(com.voltcanary.common.events.telemetry.VariableExpression expression) {
        return expression == null ? 0 : 1 + count(expression.left()) + count(expression.right());
    }

    private java.util.Map<String, String> normalizedPaths(
        VariableBindingRows.Source source,
        java.util.Map<String, String> paths
    ) {
        var mapping = com.voltcanary.device.telemetry.SourceRuntimeJson.objectMap(
            mapper,
            source.mappingJson()
        );
        if (!mapping.containsKey("telemetryPolicy")) return paths;
        var policy = mapper.convertValue(
            mapping.get("telemetryPolicy"),
            com.voltcanary.common.events.telemetry.TelemetryFieldPolicy.class
        );
        var normalized = new java.util.LinkedHashMap<String, String>();
        paths.forEach((alias, path) ->
            normalized.put(
                alias,
                policy
                    .fields()
                    .stream()
                    .filter(field -> field.path().equals(path))
                    .map(field -> field.key())
                    .findFirst()
                    .orElse(path)
            )
        );
        return java.util.Map.copyOf(normalized);
    }

    private String json(Object value) {
        try {
            return mapper
                .writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot serialize processing bundle", failure);
        }
    }

    private String digest(Object value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    json(value).getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot hash processing bundle", failure);
        }
    }
}
