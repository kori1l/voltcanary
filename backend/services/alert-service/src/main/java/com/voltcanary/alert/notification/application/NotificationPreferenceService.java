package com.voltcanary.alert.notification.application;

import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.notification.domain.NotificationPreference;
import com.voltcanary.alert.notification.dto.NotificationPreferenceRequest;
import com.voltcanary.alert.notification.dto.NotificationPreferenceResponse;
import com.voltcanary.alert.notification.port.AlertNotificationRequest;
import com.voltcanary.alert.notification.repository.NotificationPreferenceRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationPreferenceService {

    private static final String ALL = "*";
    private final NotificationPreferenceRepository repository;

    public NotificationPreferenceService(NotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public NotificationPreferenceResponse getDefault(String userId, String accountId) {
        UUID scope = accountScope(userId, accountId);
        return repository.findByAccountIdAndUserIdIsNull(scope)
                .map(this::toResponse)
                .orElseGet(() -> toResponse(defaultPreference(scope)));
    }

    @Transactional
    public NotificationPreferenceResponse updateDefault(
            String userId,
            String accountId,
            NotificationPreferenceRequest request) {
        UUID scope = accountScope(userId, accountId);
        NotificationPreference preference = repository.findByAccountIdAndUserIdIsNull(scope)
                .orElseGet(() -> newPreference(scope));
        applyRequest(preference, request);
        preference.setUpdatedAt(Instant.now());
        return toResponse(repository.save(preference));
    }

    @Transactional(readOnly = true)
    public boolean shouldDeliver(AlertNotificationRequest request, String channelType) {
        if (request.accountId() == null) {
            return true;
        }
        return repository.findByAccountIdAndEnabledTrueOrderByCreatedAtAsc(request.accountId()).stream()
                .findFirst()
                .map(preference -> matches(preference, request, channelType))
                .orElse(true);
    }

    private boolean matches(NotificationPreference preference, AlertNotificationRequest request, String channelType) {
        if (!contains(preference.getSeverityFilter(), request.severity())) {
            return false;
        }
        if (!contains(preference.getChannelTypeFilter(), normalize(channelType))) {
            return false;
        }
        if (!contains(preference.getDeviceFilter(), request.deviceId().toString())) {
            return false;
        }
        if (isQuietTime(preference, AlertSeverity.valueOf(request.severity()))) {
            return false;
        }
        return true;
    }

    private boolean isQuietTime(NotificationPreference preference, AlertSeverity severity) {
        if (preference.getQuietHoursStart() == null || preference.getQuietHoursEnd() == null) {
            return false;
        }
        if (severity == AlertSeverity.CRITICAL && preference.isCriticalBypassQuietHours()) {
            return false;
        }
        ZoneId zone = ZoneId.of(preference.getTimezone());
        LocalTime now = ZonedDateTime.now(zone).toLocalTime();
        LocalTime start = preference.getQuietHoursStart();
        LocalTime end = preference.getQuietHoursEnd();
        if (start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    private void applyRequest(NotificationPreference preference, NotificationPreferenceRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notification preference request is required");
        }
        if (request.enabled() != null) {
            preference.setEnabled(request.enabled());
        }
        if (request.severities() != null) {
            preference.setSeverityFilter(joinNormalized(request.severities(), true));
        }
        if (request.channelTypes() != null) {
            preference.setChannelTypeFilter(joinNormalized(request.channelTypes(), false));
        }
        if (request.deviceIds() != null) {
            preference.setDeviceFilter(request.deviceIds().isEmpty()
                    ? ALL
                    : request.deviceIds().stream().map(UUID::toString).collect(Collectors.joining(",")));
        }
        preference.setQuietHoursStart(request.quietHoursStart());
        preference.setQuietHoursEnd(request.quietHoursEnd());
        if (request.timezone() != null) {
            preference.setTimezone(validateZone(request.timezone()));
        }
        if (request.criticalBypassQuietHours() != null) {
            preference.setCriticalBypassQuietHours(request.criticalBypassQuietHours());
        }
    }

    private NotificationPreference newPreference(UUID accountId) {
        NotificationPreference preference = defaultPreference(accountId);
        preference.setId(UUID.randomUUID());
        preference.setCreatedAt(Instant.now());
        preference.setUpdatedAt(preference.getCreatedAt());
        return preference;
    }

    private NotificationPreference defaultPreference(UUID accountId) {
        NotificationPreference preference = new NotificationPreference();
        preference.setAccountId(accountId);
        preference.setEnabled(true);
        preference.setSeverityFilter("WARNING,HIGH,CRITICAL");
        preference.setChannelTypeFilter(ALL);
        preference.setDeviceFilter(ALL);
        preference.setTimezone("UTC");
        preference.setCriticalBypassQuietHours(true);
        Instant now = Instant.now();
        preference.setCreatedAt(now);
        preference.setUpdatedAt(now);
        return preference;
    }

    private UUID accountScope(String userId, String accountId) {
        String scope = accountId == null || accountId.isBlank() ? userId : accountId;
        if (scope == null || scope.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User context is required");
        }
        try {
            return UUID.fromString(scope);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account scope must be a UUID");
        }
    }

    private String joinNormalized(Set<String> values, boolean validateSeverity) {
        if (values.isEmpty()) {
            return ALL;
        }
        Set<String> normalized = values.stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (validateSeverity) {
            normalized.forEach(AlertSeverity::valueOf);
        }
        return normalized.isEmpty() ? ALL : String.join(",", normalized);
    }

    private boolean contains(String csv, String value) {
        if (csv == null || csv.isBlank() || ALL.equals(csv)) {
            return true;
        }
        return Arrays.stream(csv.split(",")).anyMatch(part -> part.trim().equalsIgnoreCase(value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String validateZone(String timezone) {
        try {
            return ZoneId.of(timezone.trim()).getId();
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timezone");
        }
    }

    private NotificationPreferenceResponse toResponse(NotificationPreference preference) {
        return new NotificationPreferenceResponse(
                preference.getId(),
                preference.getAccountId(),
                preference.getUserId(),
                preference.isEnabled(),
                splitStrings(preference.getSeverityFilter()),
                splitStrings(preference.getChannelTypeFilter()),
                splitUuids(preference.getDeviceFilter()),
                preference.getQuietHoursStart(),
                preference.getQuietHoursEnd(),
                preference.getTimezone(),
                preference.isCriticalBypassQuietHours(),
                preference.getCreatedAt(),
                preference.getUpdatedAt());
    }

    private Set<String> splitStrings(String csv) {
        if (csv == null || csv.isBlank() || ALL.equals(csv)) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<UUID> splitUuids(String csv) {
        if (csv == null || csv.isBlank() || ALL.equals(csv)) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(UUID::fromString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
