package com.voltcanary.device.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.voltcanary.device.repository.RegistryQueries;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceStatus;
import com.voltcanary.device.domain.DeviceType;
import com.voltcanary.device.dto.DeviceImportResponse;
import com.voltcanary.device.dto.DeviceRequest;
import com.voltcanary.device.dto.DeviceResponse;
import com.voltcanary.device.event.DeviceRegistryEventPublisher;
import com.voltcanary.device.exception.NotFoundException;
import com.voltcanary.device.metrics.DeviceOperationalMetrics;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.repository.DeviceTypeRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final RegistryQueries queries;
    private final AccountService accountService;
    private final LocationService locationService;
    private final DeviceRepository deviceRepository;
    private final DeviceTypeRepository deviceTypeRepository;
    private final DeviceRegistryEventPublisher eventPublisher;
    private final DeviceAuditService auditService;
    private final ObjectMapper objectMapper;
    private final DeviceOperationalMetrics metrics;

    @Transactional
    public DeviceResponse create(AuthenticatedUser user, DeviceRequest request) {
        Account account = accountService.currentAccount(user);
        DeviceType deviceType = deviceType(request.type());
        if (request.locationId() != null) {
            locationService.findOwned(user, request.locationId());
        }

        Instant now = Instant.now();
        Device device = new Device();
        device.setId(UUID.randomUUID());
        device.setAccountId(account.getId());
        device.setLocationId(request.locationId());
        device.setDeviceTypeId(deviceType.getId());
        device.setName(normalizeRequired(request.name(), "name"));
        device.setManufacturer(normalizeOptional(request.manufacturer()));
        device.setModel(normalizeOptional(request.model()));
        device.setMetadataJson(toJson(safeMetadata(request.metadata())));
        device.setStatus(DeviceStatus.ACTIVE);
        device.setCreatedAt(now);
        device.setUpdatedAt(now);

        Device saved = deviceRepository.save(device);
        auditService.record(user, saved.getAccountId(), "DEVICE_CREATED", "DEVICE", saved.getId());
        eventPublisher.publish("DEVICE", "CREATED", saved.getAccountId(), saved.getId(), saved.getId(), saved.getStatus().name());
        return toResponse(saved, deviceType);
    }

    @Transactional
    public DeviceImportResponse importCsv(AuthenticatedUser user, String csv) {
        if (csv == null || csv.isBlank()) {
            return new DeviceImportResponse(0, List.of("CSV body is empty"));
        }
        List<DeviceRequest> requests = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String[]> rows;
        try { rows = RegistryCsv.read(csv); }
        catch (IllegalArgumentException invalid) { return new DeviceImportResponse(0, List.of(invalid.getMessage())); }
        for (int index = 0; index < rows.size(); index++) {
            String[] columns = rows.get(index);
            if (index == 0 && columns.length >= 2 && "name".equalsIgnoreCase(columns[0].trim()) && "type".equalsIgnoreCase(columns[1].trim())) continue;
            if (columns.length < 2) {
                errors.add("line " + (index + 1) + ": expected at least name,type");
                continue;
            }
            UUID locationId = parseOptionalUuid(column(columns, 2), index + 1, errors);
            DeviceRequest request = new DeviceRequest(
                    locationId,
                    column(columns, 0),
                    column(columns, 1),
                    column(columns, 3),
                    column(columns, 4),
                    Map.of());
            validateImportRow(user, request, index + 1, errors);
            requests.add(request);
        }
        if (!errors.isEmpty()) {
            return new DeviceImportResponse(0, errors);
        }
        requests.forEach(request -> create(user, request));
        return new DeviceImportResponse(requests.size(), List.of());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String exportCsv(AuthenticatedUser user) {
        StringBuilder csv = new StringBuilder("id,name,type,locationId,manufacturer,model,status\n");
        for (DeviceResponse device : list(user)) {
            csv.append(device.id()).append(',')
                    .append(RegistryCsv.cell(device.name())).append(',')
                    .append(RegistryCsv.cell(device.type())).append(',')
                    .append(device.locationId() == null ? "" : device.locationId()).append(',')
                    .append(RegistryCsv.cell(device.manufacturer())).append(',')
                    .append(RegistryCsv.cell(device.model())).append(',')
                    .append(RegistryCsv.cell(device.status())).append('\n');
        }
        return csv.toString();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<DeviceResponse> list(AuthenticatedUser user) {
        return list(user, null, null, null, null, null, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<DeviceResponse> list(
            AuthenticatedUser user,
            UUID locationId,
            String type,
            DeviceStatus status,
            String search,
            Integer limit,
            Integer offset) {
        Account account = accountService.currentAccount(user);
        return queries.devices(account.getId(), locationId, type, status, search, limit, offset).stream()
                .map(row -> toResponse(row.device(), row.type())).toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DeviceResponse get(AuthenticatedUser user, UUID id) {
        return toResponse(findOwned(user, id));
    }

    @Transactional
    public DeviceResponse update(AuthenticatedUser user, UUID id, DeviceRequest request) {
        Device device = deviceRepository.lockOwned(id, accountService.currentAccount(user).getId())
                .orElseThrow(() -> new NotFoundException("Device not found"));
        DeviceType deviceType = deviceType(request.type());
        if (!device.getDeviceTypeId().equals(deviceType.getId())
                && deviceRepository.countEnabledVariableBindings(device.getId()) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Disable device variable bindings before changing device type");
        }
        if (request.locationId() != null) {
            locationService.findOwned(user, request.locationId());
        }

        device.setLocationId(request.locationId());
        device.setDeviceTypeId(deviceType.getId());
        device.setName(normalizeRequired(request.name(), "name"));
        device.setManufacturer(normalizeOptional(request.manufacturer()));
        device.setModel(normalizeOptional(request.model()));
        device.setMetadataJson(toJson(safeMetadata(request.metadata())));
        device.setUpdatedAt(Instant.now());

        Device saved = deviceRepository.save(device);
        auditService.record(user, saved.getAccountId(), "DEVICE_UPDATED", "DEVICE", saved.getId());
        eventPublisher.publish("DEVICE", "UPDATED", saved.getAccountId(), saved.getId(), saved.getId(), saved.getStatus().name());
        return toResponse(saved, deviceType);
    }

    @Transactional
    public void delete(AuthenticatedUser user, UUID id) {
        transition(user, id, DeviceStatus.DECOMMISSIONED);
    }

    @Transactional
    public DeviceResponse enable(AuthenticatedUser user, UUID id) {
        return transition(user, id, DeviceStatus.ACTIVE);
    }

    @Transactional
    public DeviceResponse disable(AuthenticatedUser user, UUID id) {
        return transition(user, id, DeviceStatus.DISABLED);
    }

    @Transactional
    public DeviceResponse decommission(AuthenticatedUser user, UUID id) {
        return transition(user, id, DeviceStatus.DECOMMISSIONED);
    }

    public Device findOwned(AuthenticatedUser user, UUID id) {
        Account account = accountService.currentAccount(user);
        return deviceRepository.findByIdAndAccountId(id, account.getId())
                .orElseThrow(() -> {
                    metrics.ownershipRejected();
                    return new NotFoundException("Device not found");
                });
    }

    public Device findByIdForMaintenanceResponse(UUID id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Device not found"));
    }

    private DeviceResponse toResponse(Device device) {
        DeviceType deviceType = deviceTypeRepository.findById(device.getDeviceTypeId())
                .orElseThrow(() -> new NotFoundException("Device type not found"));
        return toResponse(device, deviceType);
    }

    private DeviceResponse toResponse(Device device, DeviceType deviceType) {
        return new DeviceResponse(
                device.getId(),
                device.getLocationId(),
                device.getName(),
                deviceType.getCode(),
                device.getManufacturer(),
                device.getModel(),
                device.getStatus().name(),
                fromJson(device.getMetadataJson()));
    }

    private DeviceType deviceType(String code) {
        return deviceTypeRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Unknown device type: " + code));
    }

    private DeviceType toDeviceType(Device device) {
        return deviceTypeRepository.findById(device.getDeviceTypeId())
                .orElseThrow(() -> new NotFoundException("Device type not found"));
    }

    private DeviceResponse transition(AuthenticatedUser user, UUID id, DeviceStatus targetStatus) {
        Device device = findOwned(user, id);
        if (device.getStatus() == DeviceStatus.DECOMMISSIONED && targetStatus != DeviceStatus.DECOMMISSIONED) {
            throw new IllegalArgumentException("Decommissioned devices cannot be re-enabled");
        }
        device.setStatus(targetStatus);
        device.setUpdatedAt(Instant.now());
        Device saved = deviceRepository.save(device);
        auditService.record(user, saved.getAccountId(), "DEVICE_STATUS_CHANGED", "DEVICE", saved.getId());
        eventPublisher.publish("DEVICE", "STATUS_CHANGED", saved.getAccountId(), saved.getId(), saved.getId(), saved.getStatus().name());
        return toResponse(saved);
    }

    private String normalizeRequired(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String column(String[] columns, int index) {
        if (index >= columns.length) {
            return null;
        }
        String value = columns[index].trim();
        return value.isEmpty() ? null : value;
    }

    private UUID parseOptionalUuid(String value, int line, List<String> errors) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            errors.add("line " + line + ": invalid locationId");
            return null;
        }
    }

    private void validateImportRow(AuthenticatedUser user, DeviceRequest request, int line, List<String> errors) {
        try {
            deviceType(request.type());
            if (request.locationId() != null) {
                locationService.findOwned(user, request.locationId());
            }
            normalizeRequired(request.name(), "name");
            if (request.name().length() > 255 || request.manufacturer() != null && request.manufacturer().length() > 255
                    || request.model() != null && request.model().length() > 255) throw new IllegalArgumentException("Device text fields must be at most 255 characters");
        } catch (RuntimeException exception) {
            errors.add("line " + line + ": " + exception.getMessage());
        }
    }

    private Map<String, Object> safeMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return Map.of();
        }
        if (metadata.containsKey("password")
                || metadata.containsKey("token")
                || metadata.containsKey("secret")
                || metadata.containsKey("credential")) {
            throw new IllegalArgumentException("metadata must not contain secrets");
        }
        return metadata;
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid device metadata");
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid persisted device metadata", exception);
        }
    }
}
