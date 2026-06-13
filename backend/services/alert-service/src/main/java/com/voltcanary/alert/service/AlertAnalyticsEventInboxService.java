package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.config.AlertReconciliationProperties;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertAnalyticsEventInboxEntry;
import com.voltcanary.alert.domain.AlertAnalyticsEventInboxStatus;
import com.voltcanary.alert.repository.AlertAnalyticsEventInboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertAnalyticsEventInboxService {

    private final AlertAnalyticsEventInboxRepository repository;
    private final AlertReconciliationProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public AlertAnalyticsEventInboxService(
            AlertAnalyticsEventInboxRepository repository,
            AlertReconciliationProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public AlertAnalyticsEventInboxEntry recordReceived(String sourceName, UUID eventId, String eventType, Object event) {
        if (eventId == null) {
            AlertAnalyticsEventInboxEntry entry = newEntry(sourceName, UUID.randomUUID(), eventType, event);
            return repository.saveAndFlush(entry);
        }
        return repository.findBySourceNameAndEventId(sourceName, eventId)
                .orElseGet(() -> saveNew(sourceName, eventId, eventType, event));
    }

    @Transactional
    public AlertAnalyticsEventInboxEntry markProcessing(UUID id) {
        AlertAnalyticsEventInboxEntry entry = repository.findById(id).orElseThrow();
        Instant now = Instant.now();
        entry.setStatus(AlertAnalyticsEventInboxStatus.PROCESSING);
        entry.setAttempts(entry.getAttempts() + 1);
        entry.setProcessingStartedAt(now);
        entry.setUpdatedAt(now);
        entry.setLastError(null);
        return repository.saveAndFlush(entry);
    }

    @Transactional
    public void markProcessed(UUID id, Alert alert) {
        AlertAnalyticsEventInboxEntry entry = repository.findById(id).orElseThrow();
        Instant now = Instant.now();
        entry.setStatus(alert == null ? AlertAnalyticsEventInboxStatus.NO_ALERT : AlertAnalyticsEventInboxStatus.PROCESSED);
        entry.setAlertId(alert == null ? null : alert.getId());
        entry.setProcessingStartedAt(null);
        entry.setProcessedAt(now);
        entry.setUpdatedAt(now);
        entry.setLastError(null);
        repository.save(entry);
        meterRegistry.counter(
                "alert.analytics.inbox.processed",
                "eventType", entry.getEventType(),
                "status", entry.getStatus().name()).increment();
    }

    @Transactional
    public void markFailed(UUID id, RuntimeException exception) {
        AlertAnalyticsEventInboxEntry entry = repository.findById(id).orElseThrow();
        Instant now = Instant.now();
        entry.setStatus(AlertAnalyticsEventInboxStatus.FAILED);
        entry.setProcessingStartedAt(null);
        entry.setNextAttemptAt(now.plus(properties.getRetryDelay()));
        entry.setUpdatedAt(now);
        entry.setLastError(truncate(exception.getClass().getSimpleName() + ": " + exception.getMessage()));
        repository.save(entry);
        meterRegistry.counter("alert.analytics.inbox.failed", "eventType", entry.getEventType()).increment();
    }

    @Transactional(readOnly = true)
    public List<AlertAnalyticsEventInboxEntry> findDueForReconciliation() {
        Instant now = Instant.now();
        return repository.findDue(
                List.of(AlertAnalyticsEventInboxStatus.RECEIVED, AlertAnalyticsEventInboxStatus.FAILED),
                AlertAnalyticsEventInboxStatus.PROCESSING,
                now,
                now.minus(properties.getProcessingTimeout()),
                PageRequest.of(0, Math.max(1, properties.getBatchSize())));
    }

    private AlertAnalyticsEventInboxEntry saveNew(String sourceName, UUID eventId, String eventType, Object event) {
        try {
            return repository.saveAndFlush(newEntry(sourceName, eventId, eventType, event));
        } catch (DataIntegrityViolationException ignored) {
            return repository.findBySourceNameAndEventId(sourceName, eventId).orElseThrow();
        }
    }

    private AlertAnalyticsEventInboxEntry newEntry(String sourceName, UUID eventId, String eventType, Object event) {
        Instant now = Instant.now();
        AlertAnalyticsEventInboxEntry entry = new AlertAnalyticsEventInboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setSourceName(sourceName);
        entry.setEventId(eventId);
        entry.setEventType(eventType);
        entry.setPayloadJson(writeJson(event));
        entry.setStatus(AlertAnalyticsEventInboxStatus.RECEIVED);
        entry.setNextAttemptAt(now);
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        return entry;
    }

    private String writeJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize analytics alert event", exception);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 1024 ? value.substring(0, 1024) : value;
    }
}
