package com.voltcanary.device.service;

import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceAnalyticsConfig;
import com.voltcanary.device.dto.DeviceAnalyticsConfigRequest;
import com.voltcanary.device.dto.DeviceAnalyticsConfigResponse;
import com.voltcanary.device.event.DeviceRegistryEventPublisher;
import com.voltcanary.device.repository.DeviceAnalyticsConfigRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class DeviceAnalyticsPolicyService {

    private final DeviceService deviceService;
    private final DeviceAnalyticsConfigRepository repository;
    private final DeviceAnalyticsPolicyMapper mapper;
    private final DeviceRegistryEventPublisher eventPublisher;
    private final DeviceAuditService auditService;

    public DeviceAnalyticsPolicyService(
            DeviceService deviceService,
            DeviceAnalyticsConfigRepository repository,
            DeviceAnalyticsPolicyMapper mapper,
            DeviceRegistryEventPublisher eventPublisher,
            DeviceAuditService auditService) {
        this.deviceService = deviceService;
        this.repository = repository;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public DeviceAnalyticsConfigResponse get(AuthenticatedUser user, UUID deviceId) {
        Device device = deviceService.findOwned(user, deviceId);
        return repository.findByDeviceId(device.getId())
                .map(mapper::toResponse)
                .orElseGet(() -> mapper.defaults(device.getId()));
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
        entity.setConfigJson(mapper.toJson(config));
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
        return mapper.toResponse(saved);
    }
}
