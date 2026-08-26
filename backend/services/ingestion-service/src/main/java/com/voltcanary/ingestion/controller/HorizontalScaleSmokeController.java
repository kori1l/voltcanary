package com.voltcanary.ingestion.controller;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import com.voltcanary.ingestion.connector.ConnectorLeaseManager;
import com.voltcanary.ingestion.connector.ConnectorRuntimeProperties;
import com.voltcanary.ingestion.connector.ConnectorSupervisor;
import com.voltcanary.ingestion.connector.ConnectorRuntimeState;
import com.voltcanary.ingestion.source.SourceRegistryCache;
import com.voltcanary.ingestion.source.SourceRuntimeConfigConsumer;
import com.voltcanary.ingestion.source.SourceRuntimeConfigStore;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ingestion/horizontal-scale-smoke")
@ConditionalOnProperty(
        prefix = "app.ingestion.horizontal-scale-smoke",
        name = "enabled",
        havingValue = "true")
public class HorizontalScaleSmokeController {

    private final KafkaTemplate<String, SourceRuntimeConfigEvent> kafkaTemplate;
    private final SourceRegistryCache cache;
    private final SourceRuntimeConfigStore configStore;
    private final ConnectorSupervisor supervisor;
    private final ConnectorLeaseManager leaseManager;
    private final ConnectorRuntimeProperties properties;

    public HorizontalScaleSmokeController(
            KafkaTemplate<String, SourceRuntimeConfigEvent> kafkaTemplate,
            SourceRegistryCache cache,
            SourceRuntimeConfigStore configStore,
            ConnectorSupervisor supervisor,
            ConnectorLeaseManager leaseManager,
            ConnectorRuntimeProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.cache = cache;
        this.configStore = configStore;
        this.supervisor = supervisor;
        this.leaseManager = leaseManager;
        this.properties = properties;
    }

    @PostMapping("/source-runtime-configs")
    public Map<String, Object> publishSourceRuntimeConfig(@RequestBody SmokeSourceRuntimeConfigRequest request) {
        SourceRuntimeConfigEvent event = request.toEvent();
        kafkaTemplate.send(SourceRuntimeConfigConsumer.TOPIC, event.dataSourceId().toString(), event);
        return Map.of(
                "status", "published",
                "topic", SourceRuntimeConfigConsumer.TOPIC,
                "dataSourceId", event.dataSourceId(),
                "sourceId", event.sourceId(),
                "configVersion", event.configVersion());
    }

    @GetMapping("/sources/{dataSourceId}")
    public Map<String, Object> sourceState(@PathVariable("dataSourceId") UUID dataSourceId) {
        var source = cache.getByDataSourceId(dataSourceId);
        var config = configStore.get(dataSourceId);
        ConnectorRuntimeState connectorState = supervisor.state(dataSourceId);
        return Map.of(
                "instanceId", properties.getInstanceId(),
                "knownSource", source.isPresent(),
                "knownRuntimeConfig", config.isPresent(),
                "sourceId", source.map(value -> value.externalId()).orElse(""),
                "connectorState", connectorState.name(),
                "ownsConnectorLease", ownsConnectorLease(dataSourceId),
                "configVersion", config.map(value -> value.version()).orElse(0L));
    }

    private boolean ownsConnectorLease(UUID dataSourceId) {
        try {
            return leaseManager.current(dataSourceId).isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public record SmokeSourceRuntimeConfigRequest(
            String sourceId,
            UUID dataSourceId,
            UUID accountId,
            UUID deviceId,
            String adapterType,
            String protocol,
            String transport,
            String status,
            String mappingSchemaVersion,
            String adapterConfigSchemaVersion,
            Map<String, Object> mapping,
            Map<String, Object> adapterConfig,
            Map<String, String> credentialReferences,
            String tokenPrefix,
            String tokenHash,
            String homeRegion,
            long configVersion,
            String correlationId) {

        SourceRuntimeConfigEvent toEvent() {
            return new SourceRuntimeConfigEvent(
                    UUID.randomUUID(),
                    "v1",
                    sourceId,
                    dataSourceId,
                    accountId,
                    deviceId,
                    adapterType,
                    protocol,
                    transport,
                    status,
                    mappingSchemaVersion,
                    adapterConfigSchemaVersion,
                    mapping,
                    adapterConfig,
                    credentialReferences,
                    tokenPrefix,
                    tokenHash,
                    homeRegion,
                    configVersion,
                    Instant.now(),
                    correlationId,
                    "horizontal-scale-smoke");
        }
    }
}
