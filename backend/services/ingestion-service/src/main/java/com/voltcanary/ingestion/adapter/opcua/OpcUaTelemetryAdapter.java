package com.voltcanary.ingestion.adapter.opcua;

import com.voltcanary.ingestion.adapter.api.AdapterCapabilities;
import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.AdapterManifest;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.SubscriptionTelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.SubscriptionTelemetrySink;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.IngestionContext;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class OpcUaTelemetryAdapter implements SubscriptionTelemetryAdapter {

    private static final AdapterManifest MANIFEST = new AdapterManifest(
            "generic-opc-ua",
            AdapterType.GENERIC_OPC_UA,
            "Generic OPC UA",
            TransportType.TCP,
            ProtocolType.OPC_UA,
            AdapterCapabilities.of(AdapterCapability.SUBSCRIPTION),
            "v1");

    private final OpcUaClientOperations client;
    private final OpcUaValueMapper mapper;
    private final Clock clock;
    private final Map<String, OpcUaSubscriptionHandle> subscriptions = new ConcurrentHashMap<>();

    public OpcUaTelemetryAdapter(OpcUaClientOperations client, OpcUaValueMapper mapper, Clock clock) {
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public AdapterManifest manifest() {
        return MANIFEST;
    }

    @Override
    public void startSubscription(SourceRuntimeConfig sourceRuntimeConfig, SubscriptionTelemetrySink sink) {
        OpcUaAdapterConfig config = OpcUaAdapterConfig.from(sourceRuntimeConfig);
        stopSubscription(sourceRuntimeConfig);
        OpcUaSubscriptionHandle handle = client.subscribe(config, new SinkForwardingValueChangeHandler(
                sourceRuntimeConfig,
                config,
                sink));
        subscriptions.put(sourceRuntimeConfig.sourceId(), handle);
    }

    @Override
    public void stopSubscription(SourceRuntimeConfig sourceRuntimeConfig) {
        OpcUaSubscriptionHandle handle = subscriptions.remove(sourceRuntimeConfig.sourceId());
        if (handle != null) {
            handle.close();
        }
    }

    private IncomingTelemetryMessage message(
            SourceRuntimeConfig sourceRuntimeConfig,
            OpcUaAdapterConfig config,
            OpcUaNodeValue value) {
        Instant receivedAt = clock.instant();
        Instant occurredAt = switch (config.timestampPolicy()) {
            case SOURCE_TIME -> value.sourceTimestamp() == null ? receivedAt : value.sourceTimestamp();
            case SERVER_TIME -> value.serverTimestamp() == null ? receivedAt : value.serverTimestamp();
            case RECEIVE_TIME -> receivedAt;
        };
        Object metricValue = mapper.map(value.mapping(), value.value());
        return new IncomingTelemetryMessage(
                sourceRuntimeConfig.sourceId(),
                sourceRuntimeConfig.deviceId(),
                sourceRuntimeConfig.accountId(),
                occurredAt,
                receivedAt,
                Map.of(value.mapping().metric(), metricValue),
                origin(config),
                context(sourceRuntimeConfig, config, value.mapping()),
                false);
    }

    private TelemetryOrigin origin(OpcUaAdapterConfig config) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (config.endpoint() != null) {
            metadata.put("endpointHost", config.endpoint().getHost());
            metadata.put("endpointPort", Integer.toString(config.endpoint().getPort()));
        }
        if (config.endpointReference() != null) {
            metadata.put("endpointReference", config.endpointReference());
        }
        metadata.put("securityPolicy", config.securityPolicy());
        return new TelemetryOrigin(
                MANIFEST.adapterType(),
                MANIFEST.protocol(),
                MANIFEST.transport(),
                MANIFEST.adapterId(),
                metadata);
    }

    private IngestionContext context(
            SourceRuntimeConfig sourceRuntimeConfig,
            OpcUaAdapterConfig config,
            OpcUaNodeMapping mapping) {
        Map<String, String> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "mappingSchemaVersion", sourceRuntimeConfig.mappingSchemaVersion());
        putIfPresent(metadata, "adapterConfigSchemaVersion", sourceRuntimeConfig.adapterConfigSchemaVersion());
        metadata.put("configVersion", Long.toString(sourceRuntimeConfig.version()));
        metadata.put("timestampPolicy", config.timestampPolicy().name());
        metadata.put("publishingIntervalMs", Long.toString(config.publishingInterval().toMillis()));
        metadata.put("samplingIntervalMs", Long.toString(config.samplingInterval().toMillis()));
        if (mapping.unit() != null) {
            metadata.put("unit." + mapping.metric(), mapping.unit());
        }
        return new IngestionContext(null, null, null, metadata);
    }

    private void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private final class SinkForwardingValueChangeHandler implements OpcUaValueChangeHandler {
        private final SourceRuntimeConfig sourceRuntimeConfig;
        private final OpcUaAdapterConfig config;
        private final SubscriptionTelemetrySink sink;

        private SinkForwardingValueChangeHandler(
                SourceRuntimeConfig sourceRuntimeConfig,
                OpcUaAdapterConfig config,
                SubscriptionTelemetrySink sink) {
            this.sourceRuntimeConfig = sourceRuntimeConfig;
            this.config = config;
            this.sink = sink;
        }

        @Override
        public void onValue(OpcUaNodeValue value) {
            try {
                sink.accept(message(sourceRuntimeConfig, config, value));
            } catch (RuntimeException exception) {
                sink.reject(sourceRuntimeConfig, exception);
            }
        }

        @Override
        public void onFailure(RuntimeException exception) {
            sink.reject(sourceRuntimeConfig, exception);
        }
    }
}
