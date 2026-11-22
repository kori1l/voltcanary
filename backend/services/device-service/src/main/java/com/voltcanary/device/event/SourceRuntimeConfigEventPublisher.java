package com.voltcanary.device.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import com.voltcanary.device.config.DeviceKafkaProperties;
import com.voltcanary.device.domain.DataSource;
import com.voltcanary.device.domain.DataSourceProtocol;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SourceRuntimeConfigEventPublisher {

    public static final String TOPIC = "source.runtime-config.v1";
    public static final String BINDING_SCHEMA_TOPIC = "source.binding-schema.v1";
    private static final String VERSION = "v1";
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final DeviceOutboxService outboxService;
    private final boolean publishEnabled;
    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.voltcanary.device.configurable.port.SourceBindingCatalog bindings;

    public SourceRuntimeConfigEventPublisher(
        DeviceOutboxService outboxService,
        DeviceKafkaProperties kafkaProperties,
        ObjectMapper objectMapper,
        org.springframework.jdbc.core.JdbcTemplate jdbc,
        com.voltcanary.device.configurable.port.SourceBindingCatalog bindings
    ) {
        this.outboxService = outboxService;
        this.publishEnabled = kafkaProperties.isPublishEnabled();
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.bindings = bindings;
    }

    public void publish(UUID accountId, DataSource source) {
        bindings.record(accountId, source);
        if (!publishEnabled) {
            return;
        }
        Instant occurredAt = Instant.now();
        var publication = bindings.publication(accountId, source.getId());
        SourceRuntimeConfigEvent event = new SourceRuntimeConfigEvent(
            UUID.randomUUID(),
            VERSION,
            source.getSourceId(),
            source.getId(),
            accountId,
            source.getDeviceId(),
            adapterType(source.getProtocol()),
            protocol(source.getProtocol()),
            transport(source.getProtocol()),
            source.getStatus().name(),
            "v1",
            "v1",
            processingMapping(source, publication),
            objectMap(source.getAdapterConfigJson()),
            credentialReferences(source),
            source.getTokenPrefix(),
            source.getIngestionTokenHash(),
            homeRegion(source),
            configVersion(source),
            occurredAt,
            null,
            "device-service"
        );
        log.info(
            "Publishing {} dataSourceId={} sourceId={} status={} version={}",
            TOPIC,
            source.getId(),
            source.getSourceId(),
            source.getStatus(),
            event.configVersion()
        );
        outboxService.enqueue(event.eventId().toString(), TOPIC, source.getId().toString(), event);
        publication.ifPresent(schema ->
            outboxService.enqueue(
                schema.eventId().toString(),
                BINDING_SCHEMA_TOPIC,
                source.getId() + ":" + schema.pin().schemaRevision(),
                schema.envelope()
            )
        );
    }

    private Map<String, String> credentialReferences(DataSource source) {
        Map<String, String> configuredReferences = stringMap(source.getCredentialReferencesJson());
        if (source.getCredentialsRef() == null || source.getCredentialsRef().isBlank()) {
            return configuredReferences;
        }
        java.util.LinkedHashMap<String, String> references = new java.util.LinkedHashMap<>(
            configuredReferences
        );
        references.putIfAbsent("ingestionToken", source.getCredentialsRef());
        return Map.copyOf(references);
    }

    public Map<String, Object> processingMapping(DataSource source) {
        var account = jdbc.queryForList(
            "SELECT account_id FROM devices WHERE id=?",
            UUID.class,
            source.getDeviceId()
        );
        var publication = account.isEmpty()
            ? java.util.Optional.<com.voltcanary.common.events.device.SourceBindingSchemaEvent>empty()
            : bindings.publication(account.getFirst(), source.getId());
        return processingMapping(source, publication);
    }

    private Map<String, Object> processingMapping(
        DataSource source,
        java.util.Optional<com.voltcanary.common.events.device.SourceBindingSchemaEvent> publication
    ) {
        var mapping = new java.util.LinkedHashMap<String, Object>(
            objectMap(source.getMappingJson())
        );
        mapping.remove("processingGeneration");
        mapping.remove("processingDigest");
        mapping.remove("bindingSchemaPin");
        publication.ifPresent(schema ->
            mapping.put("bindingSchemaPin", objectMapper.convertValue(schema.pin(), OBJECT_MAP))
        );
        jdbc.query(
            "select generation,digest from source_processing_bundles where data_source_id=? order by generation desc limit 1",
            (row, index) -> {
                mapping.put("processingGeneration", row.getLong("generation"));
                mapping.put("processingDigest", row.getString("digest"));
                return row.getLong("generation");
            },
            source.getId()
        );
        return Map.copyOf(mapping);
    }

    private String homeRegion(DataSource source) {
        if (source.getHomeRegion() == null || source.getHomeRegion().isBlank()) {
            return "default";
        }
        return source.getHomeRegion().trim();
    }

    private Map<String, Object> objectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, OBJECT_MAP);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid source runtime config JSON", exception);
        }
    }

    private Map<String, String> stringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid source credential references JSON", exception);
        }
    }

    private long configVersion(DataSource source) {
        Instant updatedAt = source.getUpdatedAt();
        if (updatedAt == null) {
            return 0L;
        }
        return Math.addExact(
            Math.multiplyExact(updatedAt.getEpochSecond(), 1_000_000_000L),
            updatedAt.getNano()
        );
    }

    private String adapterType(DataSourceProtocol protocol) {
        return switch (protocol) {
            case MQTT -> "GENERIC_MQTT";
            case CSV -> "CSV_IMPORT";
            case HTTP_POLLING -> "GENERIC_HTTP_POLLING";
            case MODBUS_TCP -> "GENERIC_MODBUS_TCP";
            case OPC_UA -> "GENERIC_OPC_UA";
            case WEBHOOK -> "WEBHOOK";
            case VENDOR_API, HOME_ASSISTANT -> "VENDOR";
            default -> "GENERIC_HTTP_PUSH";
        };
    }

    private String protocol(DataSourceProtocol protocol) {
        return switch (protocol) {
            case HTTP_POLLING, VENDOR_API, HOME_ASSISTANT -> "HTTP";
            case MODBUS_TCP -> "MODBUS_TCP";
            case OPC_UA -> "OPC_UA";
            default -> protocol.name();
        };
    }

    private String transport(DataSourceProtocol protocol) {
        return switch (protocol) {
            case MQTT -> "MQTT";
            case CSV -> "FILE";
            case MODBUS_TCP, OPC_UA -> "TCP";
            default -> "HTTP";
        };
    }
}
