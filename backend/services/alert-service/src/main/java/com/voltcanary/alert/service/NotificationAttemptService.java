package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.NotificationAttempt;
import com.voltcanary.alert.domain.NotificationAttemptStatus;
import com.voltcanary.alert.domain.NotificationChannel;
import com.voltcanary.alert.dto.NotificationAttemptResponse;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.alert.repository.NotificationAttemptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
        Instant now = Instant.now();
        NotificationAttempt attempt = new NotificationAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setAlertId(alert.getId());
        attempt.setChannelId(channel.getId());
        attempt.setAccountId(channel.getAccountId());
        attempt.setChannelType(channel.getChannelType());
        attempt.setStatus(channel.isEnabled() ? NotificationAttemptStatus.PENDING : NotificationAttemptStatus.DISABLED);
        attempt.setRetryCount(0);
        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);
        NotificationAttempt saved = attemptRepository.save(attempt);
        recordAttempt(saved);
        return saved;
    }

    @Transactional
    public NotificationAttempt markRetrying(UUID attemptId, String errorCode, String providerResponseSummary) {
        NotificationAttempt attempt = loadAttempt(attemptId);
        attempt.setStatus(NotificationAttemptStatus.RETRYING);
        attempt.setRetryCount(attempt.getRetryCount() + 1);
        attempt.setLastErrorCode(normalize(errorCode, 100));
        attempt.setProviderResponseSummary(normalize(providerResponseSummary, 1000));
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
        attempt.setUpdatedAt(Instant.now());
        recordAttempt(attempt);
        return attempt;
    }

    @Transactional
    public NotificationAttempt markDisabled(UUID attemptId, String providerResponseSummary) {
        NotificationAttempt attempt = loadAttempt(attemptId);
        attempt.setStatus(NotificationAttemptStatus.DISABLED);
        attempt.setProviderResponseSummary(normalize(providerResponseSummary, 1000));
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
