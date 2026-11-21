package com.voltcanary.ingestion.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SourceProcessingConsumer {
    private final SourceProcessingProjection projection;
    private final ObjectMapper mapper;

    @KafkaListener(topics = "source.processing-config.v1",
            groupId = "#{@sourceRuntimeConfigConsumerGroupId + '-processing'}",
            containerFactory = "processingKafkaListenerContainerFactory",
            autoStartup = "${app.variables.consume-enabled:false}")
    public void consume(String json) throws JsonProcessingException {
        projection.apply(mapper.readValue(json, SourceProcessingConfigEvent.class));
    }
}
