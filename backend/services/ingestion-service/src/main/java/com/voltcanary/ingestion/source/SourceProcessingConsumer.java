package com.voltcanary.ingestion.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SourceProcessingConsumer {
    private final SourceProcessingProjection projection;
    private final ObjectMapper mapper;

    public SourceProcessingConsumer(SourceProcessingProjection projection, ObjectMapper mapper) {
        this.projection = projection;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "source.processing-config.v1",
            groupId = "#{@sourceRuntimeConfigConsumerGroupId + '-processing'}",
            containerFactory = "processingKafkaListenerContainerFactory",
            autoStartup = "${app.variables.consume-enabled:false}")
    public void consume(String json) throws JsonProcessingException {
        projection.apply(mapper.readValue(json, SourceProcessingConfigEvent.class));
    }
}
