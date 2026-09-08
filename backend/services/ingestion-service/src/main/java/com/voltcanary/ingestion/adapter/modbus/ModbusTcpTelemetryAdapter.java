package com.voltcanary.ingestion.adapter.modbus;

import com.voltcanary.ingestion.adapter.api.AdapterCapabilities;
import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.AdapterManifest;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.PollingTelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.IngestionContext;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ModbusTcpTelemetryAdapter implements PollingTelemetryAdapter {

    private static final AdapterManifest MANIFEST = new AdapterManifest(
            "generic-modbus-tcp",
            AdapterType.GENERIC_MODBUS_TCP,
            "Generic Modbus TCP",
            TransportType.TCP,
            ProtocolType.MODBUS_TCP,
            AdapterCapabilities.of(AdapterCapability.POLLING),
            "v1");

    private final ModbusTcpClientOperations client;
    private final ModbusRegisterValueMapper mapper;
    private final Clock clock;

    public ModbusTcpTelemetryAdapter(ModbusTcpClientOperations client, ModbusRegisterValueMapper mapper, Clock clock) {
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public AdapterManifest manifest() {
        return MANIFEST;
    }

    @Override
    public List<IncomingTelemetryMessage> poll(SourceRuntimeConfig sourceRuntimeConfig) {
        ModbusTcpAdapterConfig config = ModbusTcpAdapterConfig.from(sourceRuntimeConfig);
        Instant receivedAt = clock.instant();
        Map<String, Object> metrics = mapper.map(config.registers(), client.read(config));
        return List.of(new IncomingTelemetryMessage(
                sourceRuntimeConfig.sourceId(),
                sourceRuntimeConfig.deviceId(),
                sourceRuntimeConfig.accountId(),
                occurredAt(config, receivedAt),
                receivedAt,
                metrics,
                origin(config),
                context(sourceRuntimeConfig, config),
                false));
    }

    private Instant occurredAt(ModbusTcpAdapterConfig config, Instant receivedAt) {
        return switch (config.timestampPolicy()) {
            case POLL_TIME -> receivedAt;
        };
    }

    private TelemetryOrigin origin(ModbusTcpAdapterConfig config) {
        return new TelemetryOrigin(
                MANIFEST.adapterType(),
                MANIFEST.protocol(),
                MANIFEST.transport(),
                MANIFEST.adapterId(),
                Map.of("endpointHost", config.host(), "endpointPort", Integer.toString(config.port())));
    }

    private IngestionContext context(SourceRuntimeConfig sourceRuntimeConfig, ModbusTcpAdapterConfig config) {
        Map<String, String> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "mappingSchemaVersion", sourceRuntimeConfig.mappingSchemaVersion());
        putIfPresent(metadata, "adapterConfigSchemaVersion", sourceRuntimeConfig.adapterConfigSchemaVersion());
        metadata.put("configVersion", Long.toString(sourceRuntimeConfig.version()));
        metadata.put("timestampPolicy", config.timestampPolicy().name());
        for (ModbusRegisterMapping mapping : config.registers()) {
            if (mapping.unit() != null && !mapping.unit().isBlank()) {
                metadata.put("unit." + mapping.metric(), mapping.unit());
            }
        }
        return new IngestionContext(null, null, null, metadata);
    }

    private void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }
}
