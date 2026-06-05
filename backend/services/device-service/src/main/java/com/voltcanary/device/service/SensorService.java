package com.voltcanary.device.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.Sensor;
import com.voltcanary.device.dto.SensorRequest;
import com.voltcanary.device.dto.SensorResponse;
import com.voltcanary.device.event.DeviceRegistryEventPublisher;
import com.voltcanary.device.repository.SensorRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.exception.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class SensorService {

    private final DeviceService deviceService;
    private final SensorRepository sensorRepository;
    private final ObjectMapper objectMapper;
    private final DeviceRegistryEventPublisher eventPublisher;
    private final DeviceAuditService auditService;

    public SensorService(
            DeviceService deviceService,
            SensorRepository sensorRepository,
            ObjectMapper objectMapper,
            DeviceRegistryEventPublisher eventPublisher,
            DeviceAuditService auditService) {
        this.deviceService = deviceService;
        this.sensorRepository = sensorRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SensorResponse> list(AuthenticatedUser user, UUID deviceId) {
        Device device = deviceService.findOwned(user, deviceId);
        return sensorRepository.findByDeviceId(device.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SensorResponse get(AuthenticatedUser user, UUID deviceId, UUID sensorId) {
        Device device = deviceService.findOwned(user, deviceId);
        return toResponse(findOwnedSensor(device, sensorId));
    }

    @Transactional
    public SensorResponse create(AuthenticatedUser user, UUID deviceId, SensorRequest request) {
        Device device = deviceService.findOwned(user, deviceId);
        Instant now = Instant.now();
        Sensor sensor = new Sensor();
        sensor.setId(UUID.randomUUID());
        sensor.setDeviceId(device.getId());
        apply(sensor, request);
        sensor.setCreatedAt(now);
        sensor.setUpdatedAt(now);
        Sensor saved = sensorRepository.save(sensor);
        auditService.record(user, device.getAccountId(), "SENSOR_CREATED", "SENSOR", saved.getId());
        eventPublisher.publish("SENSOR", "CREATED", device.getAccountId(), device.getId(), saved.getId(), null);
        return toResponse(saved);
    }

    @Transactional
    public SensorResponse update(AuthenticatedUser user, UUID deviceId, UUID sensorId, SensorRequest request) {
        Device device = deviceService.findOwned(user, deviceId);
        Sensor sensor = findOwnedSensor(device, sensorId);
        apply(sensor, request);
        sensor.setUpdatedAt(Instant.now());
        Sensor saved = sensorRepository.save(sensor);
        auditService.record(user, device.getAccountId(), "SENSOR_UPDATED", "SENSOR", saved.getId());
        eventPublisher.publish("SENSOR", "UPDATED", device.getAccountId(), device.getId(), saved.getId(), null);
        return toResponse(saved);
    }

    @Transactional
    public void delete(AuthenticatedUser user, UUID deviceId, UUID sensorId) {
        Device device = deviceService.findOwned(user, deviceId);
        Sensor sensor = findOwnedSensor(device, sensorId);
        sensorRepository.delete(sensor);
        auditService.record(user, device.getAccountId(), "SENSOR_DELETED", "SENSOR", sensor.getId());
        eventPublisher.publish("SENSOR", "DELETED", device.getAccountId(), device.getId(), sensor.getId(), null);
    }

    private void apply(Sensor sensor, SensorRequest request) {
        sensor.setName(request.name().trim());
        sensor.setType(request.type().trim());
        sensor.setUnit(trimToNull(request.unit()));
        sensor.setMetricMapping(trimToNull(request.metricMapping()));
        sensor.setMetadataJson(toJson(request.metadata() == null ? Map.of() : request.metadata()));
    }

    private Sensor findOwnedSensor(Device device, UUID sensorId) {
        return sensorRepository.findById(sensorId)
                .filter(sensor -> sensor.getDeviceId().equals(device.getId()))
                .orElseThrow(() -> new NotFoundException("Sensor not found"));
    }

    private SensorResponse toResponse(Sensor sensor) {
        return new SensorResponse(
                sensor.getId(),
                sensor.getDeviceId(),
                sensor.getName(),
                sensor.getType(),
                sensor.getUnit(),
                sensor.getMetricMapping(),
                fromJson(sensor.getMetadataJson()),
                sensor.getCreatedAt(),
                sensor.getUpdatedAt());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid sensor metadata");
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
