package com.voltcanary.device.event;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import com.voltcanary.device.config.DeviceKafkaProperties;
import com.voltcanary.device.domain.DataSource;
import com.voltcanary.device.domain.DataSourceProtocol;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SourceRuntimeConfigEventPublisher {

    public static final String TOPIC = "source.runtime-config.v1";
    private static final Logger log = LoggerFactory.getLogger(SourceRuntimeConfigEventPublisher.class);
    private static final String VERSION = "v1";

    private final DeviceOutboxService outboxService;
    private final boolean publishEnabled;

    public SourceRuntimeConfigEventPublisher(DeviceOutboxService outboxService, DeviceKafkaProperties kafkaProperties) {
        this.outboxService = outboxService;
        this.publishEnabled = kafkaProperties.isPublishEnabled();
    }

    public void publish(UUID accountId, DataSource source) {
        if (!publishEnabled) {
            return;
        }
        Instant occurredAt = Instant.now();
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
                Map.of(),
                Map.of(),
                credentialReferences(source),
                source.getTokenPrefix(),
                source.getIngestionTokenHash(),
                "default",
                configVersion(source),
                occurredAt,
                null,
                "device-service");
        log.info(
                "Publishing {} dataSourceId={} sourceId={} status={} version={}",
                TOPIC,
                source.getId(),
                source.getSourceId(),
                source.getStatus(),
                event.configVersion());
        outboxService.enqueue(event.eventId().toString(), TOPIC, source.getId().toString(), event);
    }

    private Map<String, String> credentialReferences(DataSource source) {
        if (source.getCredentialsRef() == null || source.getCredentialsRef().isBlank()) {
            return Map.of();
        }
        return Map.of("ingestionToken", source.getCredentialsRef());
    }

    private long configVersion(DataSource source) {
        Instant updatedAt = source.getUpdatedAt();
        if (updatedAt == null) {
            return 0L;
        }
        return Math.addExact(Math.multiplyExact(updatedAt.getEpochSecond(), 1_000_000_000L), updatedAt.getNano());
    }

    private String adapterType(DataSourceProtocol protocol) {
        return switch (protocol) {
            case MQTT -> "GENERIC_MQTT";
            case CSV -> "CSV_IMPORT";
            case HTTP_POLLING -> "GENERIC_HTTP_POLLING";
            case WEBHOOK -> "WEBHOOK";
            case VENDOR_API, HOME_ASSISTANT -> "VENDOR";
            default -> "GENERIC_HTTP_PUSH";
        };
    }

    private String protocol(DataSourceProtocol protocol) {
        return switch (protocol) {
            case HTTP_POLLING, VENDOR_API, HOME_ASSISTANT -> "HTTP";
            default -> protocol.name();
        };
    }

    private String transport(DataSourceProtocol protocol) {
        return switch (protocol) {
            case MQTT -> "MQTT";
            case CSV -> "FILE";
            default -> "HTTP";
        };
    }
}
