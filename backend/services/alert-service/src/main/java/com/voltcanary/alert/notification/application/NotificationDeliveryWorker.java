package com.voltcanary.alert.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.notification.config.AlertNotificationProperties;
import com.voltcanary.alert.notification.domain.NotificationAttempt;
import com.voltcanary.alert.notification.domain.NotificationChannel;
import com.voltcanary.alert.notification.port.AlertNotificationRequest;
import com.voltcanary.alert.notification.provider.NotificationDeliveryCommand;
import com.voltcanary.alert.notification.provider.NotificationDeliveryResult;
import com.voltcanary.alert.notification.provider.NotificationProviderRegistry;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.alert.notification.repository.NotificationChannelRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class NotificationDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);
    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {
    };

    private final AlertRepository alertRepository;
    private final NotificationChannelRepository channelRepository;
    private final NotificationAttemptService attemptService;
    private final NotificationPreferenceService preferenceService;
    private final AlertNotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final NotificationProviderRegistry providerRegistry;
    private final MeterRegistry meterRegistry;
    private final String workerId = "alert-notification-" + UUID.randomUUID();

    public NotificationDeliveryWorker(
            AlertRepository alertRepository,
            NotificationChannelRepository channelRepository,
            NotificationAttemptService attemptService,
            NotificationPreferenceService preferenceService,
            AlertNotificationProperties properties,
            ObjectMapper objectMapper,
            NotificationProviderRegistry providerRegistry,
            MeterRegistry meterRegistry) {
        this.alertRepository = alertRepository;
        this.channelRepository = channelRepository;
        this.attemptService = attemptService;
        this.preferenceService = preferenceService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.providerRegistry = providerRegistry;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(
            initialDelayString = "${app.alert.notifications.initial-delay-ms:5000}",
            fixedDelayString = "${app.alert.notifications.scan-interval-ms:5000}")
    public void processScheduled() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        processAvailable();
    }

    public void processAvailable() {
        if (!properties.isEnabled()) {
            return;
        }
        int limit = properties.getClaimBatchSize();
        for (int index = 0; index < limit; index++) {
            Optional<NotificationAttempt> claimed = attemptService.claimNext(
                    workerId,
                    properties.getLeaseDuration(),
                    limit);
            if (claimed.isEmpty()) {
                return;
            }
            deliver(claimed.get());
        }
    }

    @Async("alertNotificationExecutor")
    public void processAvailableAsync() {
        processAvailable();
    }

    private void deliver(NotificationAttempt attempt) {
        Alert alert = alertRepository.findById(attempt.getAlertId()).orElse(null);
        if (alert == null || alert.getAccountId() == null) {
            attemptService.markFailed(attempt.getId(), "ALERT_NOT_FOUND", "Alert is missing or has no account");
            return;
        }
        NotificationChannel channel = channelRepository.findById(attempt.getChannelId()).orElse(null);
        if (channel == null) {
            attemptService.markFailed(attempt.getId(), "CHANNEL_NOT_FOUND", "Notification channel is missing");
            return;
        }
        AlertNotificationRequest request = AlertNotificationRequest.fromDeliveryAttempt(
                alert,
                attempt.getDeliveryEventType(),
                attempt.getIdempotencyKey());
        if (!channel.isEnabled()) {
            attemptService.markDisabled(attempt.getId(), "Notification channel is disabled");
            return;
        }
        if (!preferenceService.shouldDeliver(request, channel.getChannelType())) {
            attemptService.markDisabled(attempt.getId(), "Suppressed by notification preferences");
            meterRegistry.counter(
                    "alert.notifications.deliveries",
                    "channelType", safeChannelType(channel),
                    "status", "suppressed").increment();
            return;
        }
        NotificationDeliveryResult result = providerRegistry.find(channel.getChannelType())
                .map(adapter -> adapter.deliver(command(attempt, channel, request)))
                .orElseGet(() -> NotificationDeliveryResult.permanentFailure(
                        "UNSUPPORTED_CHANNEL_TYPE",
                        "No notification provider adapter registered for " + safeChannelType(channel)));
        applyResult(attempt, channel, result);
    }

    private NotificationDeliveryCommand command(
            NotificationAttempt attempt,
            NotificationChannel channel,
            AlertNotificationRequest request) {
        return new NotificationDeliveryCommand(
                attempt.getId(),
                channel.getId(),
                safeChannelType(channel),
                attempt.getIdempotencyKey(),
                attempt.getDeliveryEventType(),
                readConfig(channel),
                channel.getCredentialsRef(),
                request);
    }

    private void applyResult(
            NotificationAttempt attempt,
            NotificationChannel channel,
            NotificationDeliveryResult result) {
        String channelType = safeChannelType(channel);
        switch (result.status()) {
            case SENT -> {
                attemptService.markSent(attempt.getId(), providerSummary(result));
                meterRegistry.counter("alert.notifications.deliveries", "channelType", channelType, "status", "sent").increment();
            }
            case DISABLED -> {
                attemptService.markDisabled(attempt.getId(), result.providerResponseSummary());
                meterRegistry.counter("alert.notifications.deliveries", "channelType", channelType, "status", "disabled").increment();
            }
            case RETRYABLE_FAILURE -> {
                if (canRetry(attempt)) {
                    markRetrying(attempt, result.errorCode(), result.providerResponseSummary());
                    return;
                }
                markFailed(attempt, channel, result);
            }
            case PERMANENT_FAILURE -> markFailed(attempt, channel, result);
        }
    }

    private void markFailed(NotificationAttempt attempt, NotificationChannel channel, NotificationDeliveryResult result) {
        attemptService.markFailed(attempt.getId(), result.errorCode(), result.providerResponseSummary());
        meterRegistry.counter("alert.notifications.deliveries", "channelType", safeChannelType(channel), "status", "failed").increment();
        log.warn(
                "alertNotificationDeliveryFailed alertId={} channelId={} channelType={} reason={}",
                attempt.getAlertId(),
                channel.getId(),
                safeChannelType(channel),
                result.errorCode());
    }

    private Map<String, Object> readConfig(NotificationChannel channel) {
        try {
            return objectMapper.readValue(
                    channel.getConfigJson() == null || channel.getConfigJson().isBlank() ? "{}" : channel.getConfigJson(),
                    CONFIG_TYPE);
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private String safeChannelType(NotificationChannel channel) {
        return channel.getChannelType() == null || channel.getChannelType().isBlank()
                ? "UNKNOWN"
                : channel.getChannelType();
    }

    private String providerSummary(NotificationDeliveryResult result) {
        if (result.providerMessageId() == null || result.providerMessageId().isBlank()) {
            return result.providerResponseSummary();
        }
        return result.providerResponseSummary() + " messageId=" + result.providerMessageId();
    }

    private boolean canRetry(NotificationAttempt attempt) {
        return attempt.getRetryCount() + 1 < properties.getMaxAttempts();
    }

    private void markRetrying(NotificationAttempt attempt, String errorCode, String providerResponseSummary) {
        java.time.Instant nextAttemptAt = java.time.Instant.now().plus(properties.getRetryInterval());
        attemptService.markRetrying(attempt.getId(), errorCode, providerResponseSummary, nextAttemptAt);
        meterRegistry.counter(
                "alert.notifications.deliveries",
                "channelType", attempt.getChannelType(),
                "status", "retrying").increment();
    }
}
