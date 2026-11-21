package com.voltcanary.alert.deduplication;

import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.repository.AlertRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlertDeduplicationService {

    private static final DateTimeFormatter HOUR_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final AlertRepository alertRepository;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public String key(UUID deviceId, String alertType, AlertSeverity severity, Instant occurredAt) {
        return deviceId + ":" + alertType + ":" + severity + ":" + HOUR_FORMATTER.format(occurredAt == null ? Instant.now() : occurredAt);
    }

    public Optional<Alert> findBySourceEventId(UUID sourceEventId) {
        if (sourceEventId == null) {
            return Optional.empty();
        }
        return alertRepository.findFirstBySourceEventId(sourceEventId);
    }

    public Optional<Alert> findByDeduplicationKey(String deduplicationKey) {
        return alertRepository.findFirstByDeduplicationKeyOrderByCreatedAtDesc(deduplicationKey);
    }

    public Optional<Alert> findActiveInWindow(UUID deviceId, String alertType, AlertSeverity severity, Instant occurredAt) {
        Instant windowStart = (occurredAt == null ? Instant.now() : occurredAt).truncatedTo(ChronoUnit.HOURS);
        return alertRepository.findFirstByDeviceIdAndAlertTypeAndSeverityAndStatusNotAndCreatedAtBetweenOrderByCreatedAtDesc(
                deviceId,
                alertType,
                severity,
                AlertStatus.RESOLVED,
                windowStart,
                windowStart.plus(1, ChronoUnit.HOURS).minusNanos(1));
    }

    public String reopenedKey(String deduplicationKey, UUID sourceEventId) {
        return deduplicationKey + ":reopen:" + (sourceEventId == null ? UUID.randomUUID() : sourceEventId);
    }

    public String anomalyKey(com.voltcanary.common.events.analytics.AnomalyDetectedEvent event, AlertSeverity severity) {
        if (event.variableEvidence() == null) return key(event.deviceId(), event.type(), severity, event.detectedAt());
        return variablePrefix(event, severity) + event.eventId();
    }

    public Optional<Alert> activeAnomaly(com.voltcanary.common.events.analytics.AnomalyDetectedEvent event, AlertSeverity severity) {
        if (event.variableEvidence() == null) return findActiveInWindow(event.deviceId(), event.type(), severity, event.detectedAt());
        return alertRepository.findFirstByDeduplicationKeyStartingWithAndStatusNotAndCreatedAtBetweenOrderByCreatedAtDesc(
                variablePrefix(event, severity), AlertStatus.RESOLVED,
                event.detectedAt().minusSeconds(event.variableEvidence().deduplicationWindowSeconds()), event.detectedAt());
    }

    private String variablePrefix(com.voltcanary.common.events.analytics.AnomalyDetectedEvent event, AlertSeverity severity) {
        return "variable:" + event.deviceId() + ":" + event.ruleId() + ":" + event.variableEvidence().dataSourceId() + ":" + severity + ":";
    }

    public Optional<Alert> findExisting(UUID sourceEventId, String deduplicationKey) {
        return findBySourceEventId(sourceEventId)
                .or(() -> findByDeduplicationKey(deduplicationKey));
    }

    public Object lockFor(String deduplicationKey) {
        return locks.computeIfAbsent(deduplicationKey, ignored -> new Object());
    }
}
