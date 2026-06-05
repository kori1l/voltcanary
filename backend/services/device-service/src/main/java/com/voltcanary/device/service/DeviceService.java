package com.voltcanary.device.service;

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
import com.voltcanary.device.metrics.DeviceOperationalMetrics;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.repository.DeviceTypeRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.shared.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class DeviceService {

    private final AccountService accountService;
    private final LocationService locationService;
    private final DeviceRepository deviceRepository;
    private final DeviceTypeRepository deviceTypeRepository;
    private final DeviceRegistryEventPublisher eventPublisher;
    private final DeviceAuditService auditService;
    private final ObjectMapper objectMapper;
    private final DeviceOperationalMetrics metrics;

    public DeviceService(
            AccountService accountService,
            LocationService locationService,
            DeviceRepository deviceRepository,
            DeviceTypeRepository deviceTypeRepository,
            DeviceRegistryEventPublisher eventPublisher,
            DeviceAuditService auditService,
            ObjectMapper objectMapper,
            DeviceOperationalMetrics metrics) {
        this.accountService = accountService;
        this.locationService = locationService;
        this.deviceRepository = deviceRepository;
        this.deviceTypeRepository = deviceTypeRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

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
        String[] lines = csv.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index == 0 && line.toLowerCase().startsWith("name,type")) {
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.split(",", -1);
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

    @Transactional(readOnly = true)
    public String exportCsv(AuthenticatedUser user) {
        StringBuilder csv = new StringBuilder("id,name,type,locationId,manufacturer,model,status\n");
        for (DeviceResponse device : list(user)) {
            csv.append(device.id()).append(',')
                    .append(csvValue(device.name())).append(',')
                    .append(csvValue(device.type())).append(',')
                    .append(device.locationId() == null ? "" : device.locationId()).append(',')
                    .append(csvValue(device.manufacturer())).append(',')
                    .append(csvValue(device.model())).append(',')
                    .append(csvValue(device.status())).append('\n');
        }
        return csv.toString();
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> list(AuthenticatedUser user) {
        return list(user, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> list(
            AuthenticatedUser user,
            UUID locationId,
            String type,
            DeviceStatus status,
            String search,
            Integer limit,
            Integer offset) {
        Account account = accountService.currentAccount(user);
        return deviceRepository.findByAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                .filter(device -> locationId == null || locationId.equals(device.getLocationId()))
                .filter(device -> type == null || type.isBlank() || type.equalsIgnoreCase(toDeviceType(device).getCode()))
                .filter(device -> status == null || status == device.getStatus())
                .filter(device -> matchesSearch(device, search))
                .skip(normalizedOffset(offset))
                .limit(normalizedLimit(limit))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceResponse get(AuthenticatedUser user, UUID id) {
        return toResponse(findOwned(user, id));
    }

    @Transactional
    public DeviceResponse update(AuthenticatedUser user, UUID id, DeviceRequest request) {
        Device device = findOwned(user, id);
        DeviceType deviceType = deviceType(request.type());
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

    private boolean matchesSearch(Device device, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String needle = search.trim().toLowerCase();
        return contains(device.getName(), needle)
                || contains(device.getManufacturer(), needle)
                || contains(device.getModel(), needle)
                || contains(device.getExternalId(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    private long normalizedOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
        return offset;
    }

    private long normalizedLimit(Integer limit) {
        if (limit == null) {
            return Long.MAX_VALUE;
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return limit;
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
        } catch (RuntimeException exception) {
            errors.add("line " + line + ": " + exception.getMessage());
        }
    }

    private String csvValue(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
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
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
