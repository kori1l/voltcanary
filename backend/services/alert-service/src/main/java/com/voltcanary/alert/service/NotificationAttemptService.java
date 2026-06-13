package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.NotificationAttempt;
import com.voltcanary.alert.domain.NotificationAttemptStatus;
import com.voltcanary.alert.domain.NotificationChannel;
import com.voltcanary.alert.dto.NotificationAttemptResponse;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.alert.repository.NotificationAttemptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationAttemptService {

    private final NotificationAttemptRepository attemptRepository;
    private final AlertRepository alertRepository;
    private final AlertAccessService alertAccessService;
    private final MeterRegistry meterRegistry;

    public NotificationAttemptService(
            NotificationAttemptRepository attemptRepository,
            AlertRepository alertRepository,
            AlertAccessService alertAccessService,
            MeterRegistry meterRegistry) {
        this.attemptRepository = attemptRepository;
        this.alertRepository = alertRepository;
        this.alertAccessService = alertAccessService;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(readOnly = true)
    public List<NotificationAttemptResponse> listForAlert(
            UUID alertId,
            String userId,
            String role,
            String correlationId) {
        Alert alert = loadAlert(alertId);
        alertAccessService.requireReadAccess(alert.getDeviceId(), userId, role, correlationId);
        return attemptRepository.findByAlertIdOrderByCreatedAtDesc(alertId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public NotificationAttempt createPendingAttempt(Alert alert, NotificationChannel channel) {
        return createPendingAttempt(alert, channel, "MANUAL");
    }

    @Transactional
    public NotificationAttempt createPendingAttempt(Alert alert, NotificationChannel channel, String deliveryEventType) {
        String normalizedEventType = normalizeRequired(deliveryEventType, 100);
        String idempotencyKey = idempotencyKey(alert.getId(), channel.getId(), normalizedEventType);
        Optional<NotificationAttempt> existing = attemptRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Instant.now();
        NotificationAttempt attempt = new NotificationAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setAlertId(alert.getId());
        attempt.setChannelId(channel.getId());
        attempt.setAccountId(channel.getAccountId());
        attempt.setChannelType(channel.getChannelType());
        attempt.setDeliveryEventType(normalizedEventType);
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setStatus(channel.isEnabled() ? NotificationAttemptStatus.PENDING : NotificationAttemptStatus.DISABLED);
        attempt.setRetryCount(0);
        attempt.setNextAttemptAt(now);
        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);
        NotificationAttempt saved = attemptRepository.save(attempt);
        recordAttempt(saved);
        return saved;
    }

    @Transactional
    public Optional<NotificationAttempt> claimNext(String workerId, Duration leaseDuration, int batchSize) {
        Instant now = Instant.now();
        Instant lockExpiredBefore = now.minus(leaseDuration);
        List<NotificationAttempt> candidates = attemptRepository.findClaimCandidates(
                List.of(NotificationAttemptStatus.PENDING, NotificationAttemptStatus.RETRYING),
                NotificationAttemptStatus.PROCESSING,
                now,
                lockExpiredBefore,
                PageRequest.of(0, Math.max(1, batchSize)));
        for (NotificationAttempt candidate : candidates) {
            int claimed = attemptRepository.claim(
                    candidate.getId(),
                    List.of(NotificationAttemptStatus.PENDING, NotificationAttemptStatus.RETRYING),
                    NotificationAttemptStatus.PROCESSING,
                    now,
                    workerId,
                    lockExpiredBefore);
            if (claimed == 1) {
                return attemptRepository.findById(candidate.getId());
            }
        }
        return Optional.empty();
    }

    @Transactional
    public NotificationAttempt markRetrying(UUID attemptId, String errorCode, String providerResponseSummary) {
        return markRetrying(attemptId, errorCode, providerResponseSummary, Instant.now());
    }

    @Transactional
    public NotificationAttempt markRetrying(UUID attemptId, String errorCode, String providerResponseSummary, Instant nextAttemptAt) {
        NotificationAttempt attempt = loadAttempt(attemptId);
        attempt.setStatus(NotificationAttemptStatus.RETRYING);
        attempt.setRetryCount(attempt.getRetryCount() + 1);
        attempt.setLastErrorCode(normalize(errorCode, 100));
        attempt.setProviderResponseSummary(normalize(providerResponseSummary, 1000));
        attempt.setNextAttemptAt(nextAttemptAt == null ? Instant.now() : nextAttemptAt);
        clearLease(attempt);
        attempt.setUpdatedAt(Instant.now());
        recordAttempt(attempt);
        return attempt;
    }

    @Transactional
    public NotificationAttempt markSent(UUID attemptId, String providerResponseSummary) {
        NotificationAttempt attempt = loadAttempt(attemptId);
        Instant now = Instant.now();
        attempt.setStatus(NotificationAttemptStatus.SENT);
        attempt.setLastErrorCode(null);
        attempt.setProviderResponseSummary(normalize(providerResponseSummary, 1000));
        attempt.setSentAt(now);
        clearLease(attempt);
        attempt.setUpdatedAt(now);
        recordAttempt(attempt);
        return attempt;
    }

    @Transactional
    public NotificationAttempt markFailed(UUID attemptId, String errorCode, String providerResponseSummary) {
        NotificationAttempt attempt = loadAttempt(attemptId);
        attempt.setStatus(NotificationAttemptStatus.FAILED);
        attempt.setLastErrorCode(normalize(errorCode, 100));
        attempt.setProviderResponseSummary(normalize(providerResponseSummary, 1000));
        clearLease(attempt);
        attempt.setUpdatedAt(Instant.now());
        recordAttempt(attempt);
        return attempt;
    }

    @Transactional
    public NotificationAttempt markDisabled(UUID attemptId, String providerResponseSummary) {
        NotificationAttempt attempt = loadAttempt(attemptId);
        attempt.setStatus(NotificationAttemptStatus.DISABLED);
        attempt.setProviderResponseSummary(normalize(providerResponseSummary, 1000));
        clearLease(attempt);
        attempt.setUpdatedAt(Instant.now());
        recordAttempt(attempt);
        return attempt;
    }

    private Alert loadAlert(UUID alertId) {
        return alertRepository.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
    }

    private NotificationAttempt loadAttempt(UUID attemptId) {
        return attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification attempt not found"));
    }

    private void recordAttempt(NotificationAttempt attempt) {
        meterRegistry.counter(
                "alert.notifications.attempts",
                "status", attempt.getStatus().name(),
                "channelType", attempt.getChannelType()).increment();
    }

    private String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private String normalizeRequired(String value, int maxLength) {
        String normalized = normalize(value, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException("deliveryEventType is required");
        }
        return normalized;
    }

    private String idempotencyKey(UUID alertId, UUID channelId, String deliveryEventType) {
        return alertId + ":" + channelId + ":" + deliveryEventType;
    }

    private void clearLease(NotificationAttempt attempt) {
        attempt.setLockedAt(null);
        attempt.setLockedBy(null);
    }

    private NotificationAttemptResponse toResponse(NotificationAttempt attempt) {
        return new NotificationAttemptResponse(
                attempt.getId(),
                attempt.getAlertId(),
                attempt.getChannelId(),
                attempt.getAccountId(),
                attempt.getChannelType(),
                attempt.getStatus().name(),
                attempt.getRetryCount(),
                attempt.getLastErrorCode(),
                attempt.getProviderResponseSummary(),
                attempt.getCreatedAt(),
                attempt.getUpdatedAt(),
                attempt.getSentAt());
    }
}
