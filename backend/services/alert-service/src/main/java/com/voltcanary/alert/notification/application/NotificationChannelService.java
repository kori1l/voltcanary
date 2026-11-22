package com.voltcanary.alert.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.notification.domain.NotificationChannel;
import com.voltcanary.alert.notification.dto.NotificationChannelRequest;
import com.voltcanary.alert.notification.dto.NotificationChannelResponse;
import com.voltcanary.alert.notification.provider.NotificationProviderRegistry;
import com.voltcanary.alert.notification.repository.NotificationChannelRepository;
import com.voltcanary.alert.service.AlertAccessService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class NotificationChannelService {

    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {};
    private static final Set<String> SUPPORTED_TYPES = Set.of(
        "EMAIL",
        "WEBHOOK",
        "TELEGRAM",
        "SLACK",
        "SMS",
        "PUSH"
    );
    private static final Set<String> SECRET_KEY_PARTS = Set.of(
        "secret",
        "token",
        "password",
        "credential",
        "api_key",
        "apikey"
    );

    private final NotificationChannelRepository repository;
    private final ObjectMapper objectMapper;
    private final NotificationProviderRegistry providerRegistry;
    private final AlertAccessService access;

    @Transactional(readOnly = true)
    public java.util.List<NotificationChannelResponse> list(String userId, String accountId) {
        UUID scope = accountScope(userId, accountId);
        return repository
            .findByAccountIdOrderByCreatedAtDesc(scope)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public NotificationChannelResponse create(
        String userId,
        String accountId,
        NotificationChannelRequest request
    ) {
        UUID scope = accountScope(userId, accountId);
        Instant now = Instant.now();
        NotificationChannel channel = new NotificationChannel();
        channel.setId(UUID.randomUUID());
        channel.setAccountId(scope);
        applyRequest(channel, request, true);
        channel.setCreatedAt(now);
        channel.setUpdatedAt(now);
        return toResponse(repository.save(channel));
    }

    @Transactional
    public NotificationChannelResponse update(
        UUID id,
        String userId,
        String accountId,
        NotificationChannelRequest request
    ) {
        NotificationChannel channel = loadOwned(id, userId, accountId);
        applyRequest(channel, request, false);
        channel.setUpdatedAt(Instant.now());
        return toResponse(channel);
    }

    @Transactional
    public NotificationChannelResponse setEnabled(
        UUID id,
        String userId,
        String accountId,
        boolean enabled
    ) {
        NotificationChannel channel = loadOwned(id, userId, accountId);
        channel.setEnabled(enabled);
        channel.setUpdatedAt(Instant.now());
        return toResponse(channel);
    }

    @Transactional
    public void delete(UUID id, String userId, String accountId) {
        repository.delete(loadOwned(id, userId, accountId));
    }

    NotificationChannel loadOwned(UUID id, String userId, String accountId) {
        return repository
            .findByIdAndAccountId(id, accountScope(userId, accountId))
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification channel not found")
            );
    }

    private void applyRequest(
        NotificationChannel channel,
        NotificationChannelRequest request,
        boolean create
    ) {
        if (request == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Notification channel request is required"
            );
        }
        if (request.name() != null) {
            channel.setName(nonBlank(request.name(), "name", 255));
        } else if (create) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String originalType = channel.getChannelType();
        String originalCredentials = channel.getCredentialsRef();
        if (request.channelType() != null) {
            String type = nonBlank(request.channelType(), "channelType", 100).toUpperCase(
                Locale.ROOT
            );
            if (!SUPPORTED_TYPES.contains(type)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported notification channel type"
                );
            }
            channel.setChannelType(type);
        } else if (create) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelType is required");
        }
        if (request.config() != null) {
            validateConfig(channel.getChannelType(), request.config());
            channel.setConfigJson(writeConfig(request.config()));
        } else if (create) {
            channel.setConfigJson("{}");
        }
        if (request.credentialsRef() != null) {
            channel.setCredentialsRef(optionalRef(request.credentialsRef()));
        }
        if (
            !create &&
            request.config() == null &&
            (!java.util.Objects.equals(originalType, channel.getChannelType()) ||
                !java.util.Objects.equals(originalCredentials, channel.getCredentialsRef()))
        ) {
            var config = new java.util.LinkedHashMap<>(readConfig(channel.getConfigJson()));
            if (config.remove("configurableRuleIds") != null) channel.setConfigJson(
                writeConfig(config)
            );
        }
        if (request.enabled() != null) {
            channel.setEnabled(request.enabled());
        } else if (create) {
            channel.setEnabled(true);
        }
    }

    UUID accountScope(String userId, String accountId) {
        return access.ownAccount(userId);
    }

    private String nonBlank(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        if (trimmed.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is too long");
        }
        return trimmed;
    }

    private String optionalRef(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "credentialsRef is too long");
        }
        return trimmed;
    }

    private void validateConfig(String channelType, Map<String, Object> config) {
        validateConfigObject(config);
        if (
            config.containsKey("configurableRuleIds") &&
            (!(config.get("configurableRuleIds") instanceof java.util.List<?> rules) ||
                rules.size() > 100 ||
                rules.stream().anyMatch(value -> !isRuleId(value)))
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "configurableRuleIds must contain canonical rule UUIDs"
            );
        }
        providerRegistry.find(channelType).ifPresent(adapter -> adapter.validateConfig(config));
    }

    private static boolean isRuleId(Object value) {
        if (!(value instanceof String id)) return false;
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void validateConfigObject(Map<String, Object> config) {
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            String normalized = key.toLowerCase(Locale.ROOT);
            if (SECRET_KEY_PARTS.stream().anyMatch(normalized::contains)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "notification channel config must not contain secrets; use credentialsRef"
                );
            }
            validateConfigValue(entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private void validateConfigValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            validateConfigObject((Map<String, Object>) nested);
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                validateConfigValue(item);
            }
        }
    }

    private String writeConfig(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid notification channel config",
                exception
            );
        }
    }

    private Map<String, Object> readConfig(String configJson) {
        try {
            return objectMapper.readValue(
                configJson == null || configJson.isBlank() ? "{}" : configJson,
                CONFIG_TYPE
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Stored notification channel config is invalid",
                exception
            );
        }
    }

    private NotificationChannelResponse toResponse(NotificationChannel channel) {
        return new NotificationChannelResponse(
            channel.getId(),
            channel.getAccountId(),
            channel.getName(),
            channel.getChannelType(),
            readConfig(channel.getConfigJson()),
            channel.getCredentialsRef(),
            channel.isEnabled(),
            channel.getCreatedAt(),
            channel.getUpdatedAt()
        );
    }
}
