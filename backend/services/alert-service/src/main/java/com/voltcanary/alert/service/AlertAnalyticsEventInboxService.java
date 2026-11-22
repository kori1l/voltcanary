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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertAnalyticsEventInboxService {

    private final jakarta.persistence.EntityManager entityManager;
    private final AlertAnalyticsEventInboxRepository repository;
    private final AlertReconciliationProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final DeviceHistoryDeletion deletions;

    @Transactional
    public AlertAnalyticsEventInboxEntry recordReceived(
        String sourceName,
        UUID eventId,
        String eventType,
        Object event
    ) {
        var payload = objectMapper.valueToTree(event);
        if (
            payload.hasNonNull("deviceId") &&
            !deletions.writable(UUID.fromString(payload.get("deviceId").asText()))
        ) return null;
        if (eventId == null) {
            AlertAnalyticsEventInboxEntry entry = newEntry(
                sourceName,
                UUID.randomUUID(),
                eventType,
                event
            );
            return repository.saveAndFlush(entry);
        }
        return repository
            .findBySourceNameAndEventId(sourceName, eventId)
            .orElseGet(() -> saveNew(sourceName, eventId, eventType, event));
    }

    @Transactional
    public AlertAnalyticsEventInboxEntry markProcessing(UUID id) {
        var entry = locked(id);
        if (entry == null) return null;
        Instant now = Instant.now();
        if (
            entry.getStatus() == AlertAnalyticsEventInboxStatus.PROCESSED ||
            entry.getStatus() == AlertAnalyticsEventInboxStatus.NO_ALERT
        ) return null;
        if (
            entry.getStatus() == AlertAnalyticsEventInboxStatus.PROCESSING &&
            entry.getProcessingStartedAt() != null &&
            entry.getProcessingStartedAt().isAfter(now.minus(properties.getProcessingTimeout()))
        ) return null;
        entry.setStatus(AlertAnalyticsEventInboxStatus.PROCESSING);
        entry.setAttempts(entry.getAttempts() + 1);
        entry.setProcessingStartedAt(now);
        entry.setUpdatedAt(now);
        entry.setLastError(null);
        repository.saveAndFlush(entry);
        entityManager.refresh(entry);
        entityManager.detach(entry);
        return entry;
    }

    @Transactional
    public boolean markProcessed(AlertAnalyticsEventInboxEntry claim, Alert alert) {
        var entry = owned(claim);
        if (entry == null) return false;
        Instant now = Instant.now();
        entry.setStatus(
            alert == null
                ? AlertAnalyticsEventInboxStatus.NO_ALERT
                : AlertAnalyticsEventInboxStatus.PROCESSED
        );
        entry.setAlertId(alert == null ? null : alert.getId());
        entry.setProcessingStartedAt(null);
        entry.setProcessedAt(now);
        entry.setUpdatedAt(now);
        entry.setLastError(null);
        repository.save(entry);
        meterRegistry
            .counter(
                "alert.analytics.inbox.processed",
                "eventType",
                entry.getEventType(),
                "status",
                entry.getStatus().name()
            )
            .increment();
        return true;
    }

    @Transactional
    public void markFailed(AlertAnalyticsEventInboxEntry claim, RuntimeException exception) {
        var entry = owned(claim);
        if (entry == null) return;
        Instant now = Instant.now();
        entry.setStatus(AlertAnalyticsEventInboxStatus.FAILED);
        entry.setProcessingStartedAt(null);
        entry.setNextAttemptAt(now.plus(properties.getRetryDelay()));
        entry.setUpdatedAt(now);
        entry.setLastError(exception.getClass().getSimpleName());
        repository.save(entry);
        meterRegistry
            .counter("alert.analytics.inbox.failed", "eventType", entry.getEventType())
            .increment();
    }

    private AlertAnalyticsEventInboxEntry owned(AlertAnalyticsEventInboxEntry claim) {
        int attempt = claim.getAttempts();
        Instant started = claim.getProcessingStartedAt();
        var entry = locked(claim.getId());
        if (
            entry == null ||
            entry.getStatus() != AlertAnalyticsEventInboxStatus.PROCESSING ||
            entry.getAttempts() != attempt ||
            !java.util.Objects.equals(started, entry.getProcessingStartedAt()) ||
            started == null ||
            !started.isAfter(Instant.now().minus(properties.getProcessingTimeout()))
        ) return null;
        return entry;
    }

    private AlertAnalyticsEventInboxEntry locked(UUID id) {
        var snapshot = repository.findById(id).orElse(null);
        if (snapshot == null || !writable(snapshot)) return null;
        var actual = repository.lockById(id).orElse(null);
        if (actual != null) entityManager.refresh(actual);
        return actual;
    }

    private boolean writable(AlertAnalyticsEventInboxEntry entry) {
        try {
            var payload = objectMapper.readTree(entry.getPayloadJson());
            return (
                !payload.hasNonNull("deviceId") ||
                deletions.writable(UUID.fromString(payload.get("deviceId").asText()))
            );
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid persisted event", invalid);
        }
    }

    @Transactional(readOnly = true)
    public List<AlertAnalyticsEventInboxEntry> findDueForReconciliation() {
        Instant now = Instant.now();
        return repository.findDue(
            List.of(AlertAnalyticsEventInboxStatus.RECEIVED, AlertAnalyticsEventInboxStatus.FAILED),
            AlertAnalyticsEventInboxStatus.PROCESSING,
            now,
            now.minus(properties.getProcessingTimeout()),
            PageRequest.of(0, Math.max(1, properties.getBatchSize()))
        );
    }

    private AlertAnalyticsEventInboxEntry saveNew(
        String sourceName,
        UUID eventId,
        String eventType,
        Object event
    ) {
        // The device fence serializes accepted events. Unexpected conflicts must roll back;
        // a later delivery can read the winner in a fresh transaction.
        return repository.saveAndFlush(newEntry(sourceName, eventId, eventType, event));
    }

    private AlertAnalyticsEventInboxEntry newEntry(
        String sourceName,
        UUID eventId,
        String eventType,
        Object event
    ) {
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
            throw new IllegalArgumentException(
                "Failed to serialize analytics alert event",
                exception
            );
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 1024 ? value.substring(0, 1024) : value;
    }
}
