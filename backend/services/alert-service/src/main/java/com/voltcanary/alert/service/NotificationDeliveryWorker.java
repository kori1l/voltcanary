package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.config.AlertNotificationProperties;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.NotificationAttempt;
import com.voltcanary.alert.domain.NotificationChannel;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.alert.repository.NotificationChannelRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final String workerId = "alert-notification-" + UUID.randomUUID();

    public NotificationDeliveryWorker(
            AlertRepository alertRepository,
            NotificationChannelRepository channelRepository,
            NotificationAttemptService attemptService,
            NotificationPreferenceService preferenceService,
            AlertNotificationProperties properties,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry) {
        this.alertRepository = alertRepository;
        this.channelRepository = channelRepository;
        this.attemptService = attemptService;
        this.preferenceService = preferenceService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .requestFactory(requestFactory(properties))
                .build();
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
        if (!channel.isEnabled()) {
            attemptService.markDisabled(attempt.getId(), "Notification channel is disabled");
            return;
        }
        if (!preferenceService.shouldDeliver(alert, channel.getChannelType())) {
            attemptService.markDisabled(attempt.getId(), "Suppressed by notification preferences");
            meterRegistry.counter(
                    "alert.notifications.deliveries",
                    "channelType", safeChannelType(channel),
                    "status", "suppressed").increment();
            return;
        }
        String url = deliveryUrl(channel);
        if (url == null) {
            attemptService.markFailed(attempt.getId(), "CONFIG_MISSING_URL", channel.getChannelType() + " channel requires config.url");
            return;
        }
        deliverHttp(alert, channel, attempt, url);
    }

    private void deliverHttp(Alert alert, NotificationChannel channel, NotificationAttempt attempt, String url) {
        Map<String, Object> payload = deliveryPayload(alert, channel, attempt);
        String channelType = safeChannelType(channel);
        try {
            HttpStatusCode status = restClient.post()
                    .uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", attempt.getIdempotencyKey())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            attemptService.markSent(attempt.getId(), "HTTP " + status.value());
            meterRegistry.counter("alert.notifications.deliveries", "channelType", channelType, "status", "sent").increment();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is5xxServerError() && canRetry(attempt)) {
                markRetrying(attempt, "HTTP_" + exception.getStatusCode().value(), responseSummary(exception));
                return;
            }
            attemptService.markFailed(attempt.getId(), "HTTP_" + exception.getStatusCode().value(), responseSummary(exception));
            meterRegistry.counter("alert.notifications.deliveries", "channelType", channelType, "status", "failed").increment();
        } catch (Exception exception) {
            if (canRetry(attempt)) {
                markRetrying(attempt, exception.getClass().getSimpleName(), exception.getMessage());
                return;
            }
            attemptService.markFailed(attempt.getId(), exception.getClass().getSimpleName(), exception.getMessage());
            meterRegistry.counter("alert.notifications.deliveries", "channelType", channelType, "status", "failed").increment();
            log.warn(
                    "alertNotificationDeliveryFailed alertId={} channelId={} channelType={} reason={}",
                    alert.getId(),
                    channel.getId(),
                    channelType,
                    exception.getClass().getSimpleName());
        }
    }

    private String deliveryUrl(NotificationChannel channel) {
        Object url = readConfig(channel).get("url");
        if (url instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return null;
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

    private Map<String, Object> deliveryPayload(Alert alert, NotificationChannel channel, NotificationAttempt attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", attempt.getDeliveryEventType());
        payload.put("idempotencyKey", attempt.getIdempotencyKey());
        payload.put("channelType", safeChannelType(channel));
        payload.put("alertId", alert.getId());
        payload.put("accountId", alert.getAccountId());
        payload.put("deviceId", alert.getDeviceId());
        payload.put("sourceEventId", alert.getSourceEventId());
        payload.put("anomalyId", alert.getAnomalyId());
        payload.put("riskScoreId", alert.getRiskScoreId());
        payload.put("ruleId", alert.getRuleId());
        payload.put("calculationWindowStart", alert.getCalculationWindowStart());
        payload.put("calculationWindowEnd", alert.getCalculationWindowEnd());
        payload.put("calculationInputHash", alert.getCalculationInputHash());
        payload.put("alertType", alert.getAlertType());
        payload.put("severity", alert.getSeverity().name());
        payload.put("status", alert.getStatus().name());
        payload.put("title", alert.getTitle());
        payload.put("description", alert.getDescription());
        payload.put("createdAt", alert.getCreatedAt());
        payload.put("channelId", channel.getId());
        payload.put("sentAt", Instant.now());
        return payload;
    }

    private String safeChannelType(NotificationChannel channel) {
        return channel.getChannelType() == null || channel.getChannelType().isBlank()
                ? "UNKNOWN"
                : channel.getChannelType();
    }

    private String responseSummary(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "HTTP " + exception.getStatusCode().value();
        }
        return "HTTP " + exception.getStatusCode().value() + ": " + body;
    }

    private boolean canRetry(NotificationAttempt attempt) {
        return attempt.getRetryCount() + 1 < properties.getMaxAttempts();
    }

    private void markRetrying(NotificationAttempt attempt, String errorCode, String providerResponseSummary) {
        Instant nextAttemptAt = Instant.now().plus(properties.getRetryInterval());
        attemptService.markRetrying(attempt.getId(), errorCode, providerResponseSummary, nextAttemptAt);
        meterRegistry.counter(
                "alert.notifications.deliveries",
                "channelType", attempt.getChannelType(),
                "status", "retrying").increment();
    }

    private SimpleClientHttpRequestFactory requestFactory(AlertNotificationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        return factory;
    }
}
