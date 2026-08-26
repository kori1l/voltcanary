package com.voltcanary.ingestion.adapter.mqtt;

import com.voltcanary.ingestion.adapter.api.AdapterCapabilities;
import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.AdapterManifest;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.InboundTelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import org.springframework.stereotype.Component;

@Component
public class MqttTelemetryAdapter implements InboundTelemetryAdapter<MqttAdapterRequest> {

    private static final AdapterManifest MANIFEST = new AdapterManifest(
            "generic-mqtt",
            AdapterType.GENERIC_MQTT,
            "Generic MQTT",
            TransportType.MQTT,
            ProtocolType.MQTT,
            AdapterCapabilities.of(AdapterCapability.INBOUND),
            "v1");

    private final MqttTopicResolver topicResolver;
    private final MqttPayloadMapper payloadMapper;

    public MqttTelemetryAdapter(MqttTopicResolver topicResolver, MqttPayloadMapper payloadMapper) {
        this.topicResolver = topicResolver;
        this.payloadMapper = payloadMapper;
    }

    @Override
    public AdapterManifest manifest() {
        return MANIFEST;
    }

    @Override
    public IncomingTelemetryMessage map(MqttAdapterRequest request, SourceRuntimeConfig sourceRuntimeConfig) {
        String sourceId = topicResolver.sourceId(request.topic())
                .orElseThrow(() -> new UnsupportedMqttTopicException(request.topic()));
        return payloadMapper.map(sourceId, request.topic(), request.payload());
    }
}
