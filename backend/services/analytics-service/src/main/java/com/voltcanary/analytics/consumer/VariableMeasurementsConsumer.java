package com.voltcanary.analytics.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.variable.VariableRuleEvaluation;
import com.voltcanary.common.events.telemetry.MeasurementsAcceptedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VariableMeasurementsConsumer {

    private final ObjectMapper mapper;
    private final VariableRuleEvaluation evaluation;

    @KafkaListener(
        topics = "telemetry.measurements.accepted.v1",
        groupId = "${spring.kafka.consumer.group-id}-variables",
        containerFactory = "variableKafkaListenerContainerFactory",
        autoStartup = "${app.variables.consume-enabled:false}"
    )
    public void consume(String json) throws JsonProcessingException {
        evaluation.evaluate(mapper.readValue(json, MeasurementsAcceptedEvent.class));
    }
}
