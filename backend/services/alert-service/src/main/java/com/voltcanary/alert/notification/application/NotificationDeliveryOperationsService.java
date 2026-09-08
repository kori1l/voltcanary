package com.voltcanary.alert.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.notification.domain.NotificationAttempt;
import com.voltcanary.alert.notification.domain.NotificationAttemptStatus;
import com.voltcanary.alert.notification.domain.NotificationChannel;
import com.voltcanary.alert.notification.dto.NotificationAttemptResponse;
import com.voltcanary.alert.notification.dto.NotificationChannelDiagnosticsResponse;
import com.voltcanary.alert.notification.dto.NotificationTestDeliveryRequest;
import com.voltcanary.alert.notification.provider.NotificationProviderRegistry;
import com.voltcanary.alert.notification.repository.NotificationAttemptRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationDeliveryOperationsService {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };
    private static final Set<String> SECRET_KEY_PARTS = Set.of("secret", "token", "password", "credential", "api_key", "apikey");
    private static final Pattern SECRET_VALUE_PATTERN = Pattern.compile(
            "(?i)(secret|token|password|credential|api[_-]?key)(\\s*[:=]\\s*)\\S+");
    private static final Collection<NotificationAttemptStatus> FAILURE_STATUSES = Set.of(
            NotificationAttemptStatus.FAILED,
            NotificationAttemptStatus.RETRYING);
    private static final Collection<NotificationAttemptStatus> WAITING_STATUSES = Set.of(
            NotificationAttemptStatus.PENDING,
            NotificationAttemptStatus.RETRYING);

    private final NotificationChannelService channelService;
    private final NotificationAttemptService attemptService;
    private final NotificationAttemptRepository attemptRepository;
    private final NotificationDeliveryWorker worker;
    private final NotificationProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public NotificationDeliveryOperationsService(
            NotificationChannelService channelService,
            NotificationAttemptService attemptService,
            NotificationAttemptRepository attemptRepository,
            NotificationDeliveryWorker worker,
            NotificationProviderRegistry providerRegistry,
            ObjectMapper objectMapper) {
        this.channelService = channelService;
        this.attemptService = attemptService;
        this.attemptRepository = attemptRepository;
        this.worker = worker;
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    public NotificationAttemptResponse testDelivery(
            UUID channelId,
            String userId,
            String accountId,
            NotificationTestDeliveryRequest request) {
        NotificationChannel channel = channelService.loadOwned(channelId, userId, accountId);
        Map<String, Object> sample = request == null || request.sample() == null ? Map.of() : request.sample();
        validateNoSecretKeys(sample);
        NotificationAttempt attempt = attemptService.createTestAttempt(channel, sample);
        if (!channel.isEnabled()) {
            return attemptService.toResponse(attempt);
        }
        return attemptService.toResponse(worker.processAttemptNow(attempt.getId()));
    }

    @Transactional(readOnly = true)
    public NotificationChannelDiagnosticsResponse diagnostics(UUID channelId, String userId, String accountId) {
        NotificationChannel channel = channelService.loadOwned(channelId, userId, accountId);
        UUID accountScope = channel.getAccountId();
        NotificationAttempt lastFailure = attemptRepository
                .findFirstByChannelIdAndAccountIdAndStatusInOrderByUpdatedAtDesc(channelId, accountScope, FAILURE_STATUSES)
                .orElse(null);
        NotificationAttempt lastTest = attemptRepository
                .findFirstByChannelIdAndAccountIdAndDeliveryEventTypeOrderByCreatedAtDesc(
                        channelId,
                        accountScope,
                        "NOTIFICATION_TEST")
                .orElse(null);
        Instant oldestWaiting = attemptRepository
                .findOldestCreatedAtByChannelAndStatus(channelId, accountScope, WAITING_STATUSES)
                .orElse(null);

        return new NotificationChannelDiagnosticsResponse(
                channel.getId(),
                accountScope,
                channel.getChannelType(),
                channel.isEnabled(),
                providerRegistry.hasAdapter(channel.getChannelType()),
                attemptRepository
                        .findFirstByChannelIdAndAccountIdAndStatusOrderBySentAtDesc(
                                channelId,
                                accountScope,
                                NotificationAttemptStatus.SENT)
                        .map(NotificationAttempt::getSentAt)
                        .orElse(null),
                lastFailure == null ? null : lastFailure.getUpdatedAt(),
                lastFailure == null ? null : lastFailure.getLastErrorCode(),
                lastFailure == null ? null : redactedSummary(lastFailure.getProviderResponseSummary()),
                attemptRepository.countByChannelIdAndAccountIdAndStatus(channelId, accountScope, NotificationAttemptStatus.RETRYING),
                attemptRepository.countByChannelIdAndAccountIdAndStatus(channelId, accountScope, NotificationAttemptStatus.FAILED),
                attemptRepository.countByChannelIdAndAccountIdAndStatus(channelId, accountScope, NotificationAttemptStatus.PENDING),
                oldestWaiting == null ? null : Duration.between(oldestWaiting, Instant.now()).toSeconds(),
                lastTest == null ? null : lastTest.getId(),
                lastTest == null ? null : lastTest.getStatus().name(),
                lastTest == null ? 0 : lastTest.getRetryCount(),
                lastTest == null ? 0 : Math.max(1, lastTest.getMaxAttempts()),
                lastTest == null ? null : lastTest.getNextAttemptAt(),
                lastTest != null && lastTest.isRetryExhausted());
    }

    private void validateNoSecretKeys(Map<String, Object> sample) {
        validateObject(sample);
        assertJsonSerializable(sample);
    }

    @SuppressWarnings("unchecked")
    private void validateValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            validateObject((Map<String, Object>) nested);
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                validateValue(item);
            }
        }
    }

    private void validateObject(Map<String, Object> sample) {
        for (Map.Entry<String, Object> entry : sample.entrySet()) {
            String normalized = entry.getKey().toLowerCase(Locale.ROOT);
            if (SECRET_KEY_PARTS.stream().anyMatch(normalized::contains)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notification test payload must not contain secrets");
            }
            validateValue(entry.getValue());
        }
    }

    private void assertJsonSerializable(Map<String, Object> sample) {
        try {
            objectMapper.readValue(objectMapper.writeValueAsString(sample), PAYLOAD_TYPE);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification test payload", exception);
        }
    }

    private String redactedSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String redacted = SECRET_VALUE_PATTERN.matcher(summary.trim()).replaceAll("$1$2[redacted]");
        return redacted.length() > 500 ? redacted.substring(0, 500) : redacted;
    }
}
