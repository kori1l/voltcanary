package com.voltcanary.device.analyticsconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.device.Device;
import com.voltcanary.device.device.DeviceService;
import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.event.DeviceRegistryEventPublisher;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceAnalyticsConfigService {

    private final DeviceService deviceService;
    private final DeviceAnalyticsConfigRepository repository;
    private final ObjectMapper objectMapper;
    private final DeviceRegistryEventPublisher eventPublisher;
    private final DeviceAuditService auditService;

    public DeviceAnalyticsConfigService(
            DeviceService deviceService,
            DeviceAnalyticsConfigRepository repository,
            ObjectMapper objectMapper,
            DeviceRegistryEventPublisher eventPublisher,
            DeviceAuditService auditService) {
        this.deviceService = deviceService;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public DeviceAnalyticsConfigResponse get(AuthenticatedUser user, UUID deviceId) {
        Device device = deviceService.findOwned(user, deviceId);
        return repository.findByDeviceId(device.getId())
                .map(this::toResponse)
                .orElseGet(() -> new DeviceAnalyticsConfigResponse(device.getId(), true, Map.of(), null));
    }

    @Transactional
    public DeviceAnalyticsConfigResponse update(
            AuthenticatedUser user,
            UUID deviceId,
            DeviceAnalyticsConfigRequest request) {
        Device device = deviceService.findOwned(user, deviceId);
        Map<String, Object> config = request.config() == null ? Map.of() : request.config();
        DeviceAnalyticsConfig entity = repository.findByDeviceId(device.getId()).orElseGet(() -> {
            DeviceAnalyticsConfig created = new DeviceAnalyticsConfig();
            created.setId(UUID.randomUUID());
            created.setDeviceId(device.getId());
            return created;
        });
        entity.setEnabled(request.enabled());
        entity.setConfigJson(toJson(config));
        entity.setUpdatedAt(Instant.now());
        DeviceAnalyticsConfig saved = repository.save(entity);
        auditService.record(user, device.getAccountId(), "ANALYTICS_CONFIG_UPDATED", "ANALYTICS_CONFIG", saved.getId());
        eventPublisher.publish(
                "ANALYTICS_CONFIG",
                "UPDATED",
                device.getAccountId(),
                device.getId(),
                saved.getId(),
                saved.isEnabled() ? "ENABLED" : "DISABLED");
        return toResponse(saved);
    }

    private DeviceAnalyticsConfigResponse toResponse(DeviceAnalyticsConfig entity) {
        return new DeviceAnalyticsConfigResponse(
                entity.getDeviceId(),
                entity.isEnabled(),
                fromJson(entity.getConfigJson()),
                entity.getUpdatedAt());
    }

    private String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid analytics config");
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
