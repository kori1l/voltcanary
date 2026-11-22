package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertEvent;
import com.voltcanary.alert.repository.AlertEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertEventWrites {
    private final AlertEventRepository alertEventRepository;
    private final ObjectMapper objectMapper;
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public UUID saveEvent(Alert alert, String eventType, UUID actorId, String reason, Object details) {
        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        AlertEvent event = new AlertEvent();
        event.setId(eventId);
        event.setAlertId(alert.getId());
        event.setEventType(eventType);
        event.setActorId(actorId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("deviceId", alert.getDeviceId());
        context.put("sourceEventId", alert.getSourceEventId());
        context.put("anomalyId", alert.getAnomalyId());
        context.put("riskScoreId", alert.getRiskScoreId());
        context.put("ruleId", alert.getRuleId());
        context.put("calculationWindowStart", alert.getCalculationWindowStart());
        context.put("calculationWindowEnd", alert.getCalculationWindowEnd());
        context.put("calculationInputHash", alert.getCalculationInputHash());
        context.put("details", details == null ? Map.of() : details);
        event.setPayloadJson(writeJson(new AlertEventPayload(
                eventId,
                alert.getId(),
                actorId,
                eventType,
                createdAt,
                alert.getStatus().name(),
                reason,
                context)));
        event.setCreatedAt(createdAt);
        alertEventRepository.save(event);
        return eventId;
    }
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write alert event JSON", e);
        }
    }
    private record AlertEventPayload(
            UUID eventId,
            UUID alertId,
            UUID actorId,
            String action,
            Instant timestamp,
            String status,
            String reason,
            Map<String, Object> context) {
    }


}
