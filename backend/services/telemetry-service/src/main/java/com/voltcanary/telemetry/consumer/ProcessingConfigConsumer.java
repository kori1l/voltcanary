package com.voltcanary.telemetry.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.telemetry.variable.ProcessingBundles;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcessingConfigConsumer {

    private final ProcessingBundles bundles;
    private final ObjectMapper mapper;

    @KafkaListener(
        topics = "source.processing-config.v1",
        groupId = "${spring.kafka.consumer.group-id}-processing",
        containerFactory = "processingKafkaListenerContainerFactory",
        autoStartup = "${app.variables.consume-enabled:false}"
    )
    public void consume(String json) throws JsonProcessingException {
        bundles.apply(mapper.readValue(json, SourceProcessingConfigEvent.class));
    }
}
