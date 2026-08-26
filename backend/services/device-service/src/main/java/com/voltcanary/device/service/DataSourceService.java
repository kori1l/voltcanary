package com.voltcanary.device.service;

import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.domain.DataSource;
import com.voltcanary.device.domain.DataSourceProtocol;
import com.voltcanary.device.domain.DataSourceType;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceStatus;
import com.voltcanary.device.domain.SourceStatus;
import com.voltcanary.device.dto.DataSourceImportResponse;
import com.voltcanary.device.dto.DataSourceRequest;
import com.voltcanary.device.dto.DataSourceResponse;
import com.voltcanary.device.dto.InternalDataSourceResponse;
import com.voltcanary.device.dto.SourceTokenResponse;
import com.voltcanary.device.event.DeviceRegistryEventPublisher;
import com.voltcanary.device.event.SourceRuntimeConfigEventPublisher;
import com.voltcanary.device.repository.AccountRepository;
import com.voltcanary.device.repository.DataSourceRepository;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.exception.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class DataSourceService {

    private final DeviceService deviceService;
    private final AccountRepository accountRepository;
    private final DeviceRepository deviceRepository;
    private final DataSourceRepository dataSourceRepository;
    private final DeviceRegistryEventPublisher eventPublisher;
    private final SourceRuntimeConfigEventPublisher runtimeConfigEventPublisher;
    private final DeviceAuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public DataSourceService(
            DeviceService deviceService,
            AccountRepository accountRepository,
            DeviceRepository deviceRepository,
            DataSourceRepository dataSourceRepository,
            DeviceRegistryEventPublisher eventPublisher,
            SourceRuntimeConfigEventPublisher runtimeConfigEventPublisher,
            DeviceAuditService auditService) {
        this.deviceService = deviceService;
        this.accountRepository = accountRepository;
        this.deviceRepository = deviceRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.eventPublisher = eventPublisher;
        this.runtimeConfigEventPublisher = runtimeConfigEventPublisher;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<DataSourceResponse> list(AuthenticatedUser user, UUID deviceId) {
        Device device = deviceService.findOwned(user, deviceId);
        return dataSourceRepository.findByDeviceId(device.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DataSourceResponse get(AuthenticatedUser user, UUID deviceId, UUID dataSourceId) {
        Device device = deviceService.findOwned(user, deviceId);
        return toResponse(findOwnedDataSource(device, dataSourceId));
    }

    @Transactional
    public DataSourceResponse create(AuthenticatedUser user, UUID deviceId, DataSourceRequest request) {
        Device device = deviceService.findOwned(user, deviceId);

        Instant now = Instant.now();
        DataSource dataSource = new DataSource();
        dataSource.setId(UUID.randomUUID());
        dataSource.setDeviceId(device.getId());
        String sourceId = normalizeRequired(request.externalId(), "externalId");
        dataSource.setName(sourceId);
        dataSource.setType(request.sourceType());
        dataSource.setSourceId(sourceId);
        dataSource.setSourceType(request.sourceType());
        dataSource.setProtocol(request.protocol());
        dataSource.setConnectionRef(null);
        dataSource.setEnabled(true);
        dataSource.setStatus(SourceStatus.ACTIVE);
        dataSource.setCreatedAt(now);
        dataSource.setUpdatedAt(now);

        DataSource saved = dataSourceRepository.save(dataSource);
        auditService.record(user, device.getAccountId(), "DATA_SOURCE_CREATED", "DATA_SOURCE", saved.getId());
        eventPublisher.publish("DATA_SOURCE", "CREATED", device.getAccountId(), device.getId(), saved.getId(), saved.getStatus().name());
        runtimeConfigEventPublisher.publish(device.getAccountId(), saved);
        return toResponse(saved);
    }

    @Transactional
    public DataSourceImportResponse importCsv(AuthenticatedUser user, UUID deviceId, String csv) {
        Device device = deviceService.findOwned(user, deviceId);
        List<String> errors = new ArrayList<>();
        List<DataSourceRequest> requests = new ArrayList<>();
        Set<String> importedSourceIds = new HashSet<>();
        Set<String> existingSourceIds = dataSourceRepository.findByDeviceId(device.getId()).stream()
                .map(DataSource::getSourceId)
                .collect(java.util.stream.Collectors.toSet());

        String[] lines = csv == null ? new String[0] : csv.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length == 0 || !lines[0].trim().equalsIgnoreCase("externalId,sourceType,protocol")) {
            errors.add("Header must be externalId,sourceType,protocol");
        }

        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.split(",", -1);
            try {
                DataSourceRequest request = validateImportRow(columns, index + 1, existingSourceIds, importedSourceIds);
                requests.add(request);
            } catch (IllegalArgumentException exception) {
                errors.add(exception.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            return new DataSourceImportResponse(0, errors);
        }

        requests.forEach(request -> create(user, device.getId(), request));
        return new DataSourceImportResponse(requests.size(), List.of());
    }

    @Transactional(readOnly = true)
    public String exportCsv(AuthenticatedUser user, UUID deviceId) {
        Device device = deviceService.findOwned(user, deviceId);
        StringBuilder csv = new StringBuilder("id,deviceId,externalId,sourceType,protocol,status\n");
        dataSourceRepository.findByDeviceId(device.getId()).forEach(source -> csv
                .append(source.getId()).append(',')
                .append(source.getDeviceId()).append(',')
                .append(csvValue(source.getSourceId())).append(',')
                .append(source.getSourceType()).append(',')
                .append(source.getProtocol()).append(',')
                .append(source.getStatus()).append('\n'));
        return csv.toString();
    }

    @Transactional
    public SourceTokenResponse rotateToken(AuthenticatedUser user, UUID deviceId, UUID dataSourceId) {
        Device device = deviceService.findOwned(user, deviceId);
        DataSource dataSource = findOwnedDataSource(device, dataSourceId);

        String token = generateToken();
        String prefix = token.substring(0, Math.min(16, token.length()));
        dataSource.setIngestionTokenHash(sha256Hex(token));
        dataSource.setTokenPrefix(prefix);
        dataSource.setCredentialsRef(prefix);
        dataSource.setTokenCreatedAt(Instant.now());
        dataSource.setTokenRevokedAt(null);
        dataSource.setUpdatedAt(Instant.now());
        DataSource saved = dataSourceRepository.save(dataSource);
        auditService.record(user, device.getAccountId(), "DATA_SOURCE_TOKEN_ROTATED", "DATA_SOURCE", saved.getId());
        eventPublisher.publish("DATA_SOURCE", "TOKEN_ROTATED", device.getAccountId(), device.getId(), saved.getId(), saved.getStatus().name());
        runtimeConfigEventPublisher.publish(device.getAccountId(), saved);
        return new SourceTokenResponse(token, prefix);
    }

    @Transactional
    public void revokeToken(AuthenticatedUser user, UUID deviceId, UUID dataSourceId) {
        Device device = deviceService.findOwned(user, deviceId);
        DataSource dataSource = findOwnedDataSource(device, dataSourceId);
        dataSource.setTokenRevokedAt(Instant.now());
        dataSource.setStatus(SourceStatus.REVOKED);
        dataSource.setUpdatedAt(Instant.now());
        DataSource saved = dataSourceRepository.save(dataSource);
        auditService.record(user, device.getAccountId(), "DATA_SOURCE_TOKEN_REVOKED", "DATA_SOURCE", saved.getId());
        eventPublisher.publish("DATA_SOURCE", "TOKEN_REVOKED", device.getAccountId(), device.getId(), saved.getId(), saved.getStatus().name());
        runtimeConfigEventPublisher.publish(device.getAccountId(), saved);
    }

    @Transactional
    public void markTokenUsed(UUID dataSourceId) {
        DataSource dataSource = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new NotFoundException("Data source not found"));
        dataSource.setTokenLastUsedAt(Instant.now());
        dataSource.setLastSeenAt(Instant.now());
        dataSource.setUpdatedAt(Instant.now());
        dataSourceRepository.save(dataSource);
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID deviceId, String sourceId) {
        return deviceRepository.findById(deviceId)
                .filter(device -> accountRepository.findById(device.getAccountId())
                        .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                        .isPresent())
                .filter(device -> device.getStatus() == DeviceStatus.ACTIVE)
                .filter(device -> dataSourceRepository.findByDeviceId(device.getId()).stream()
                        .anyMatch(source -> source.isEnabled()
                                && source.getStatus() == SourceStatus.ACTIVE
                                && source.getSourceId().equals(sourceId)))
                .isPresent();
    }

    @Transactional
    public DataSourceResponse update(AuthenticatedUser user, UUID deviceId, UUID dataSourceId, DataSourceRequest request) {
        Device device = deviceService.findOwned(user, deviceId);
        DataSource dataSource = findOwnedDataSource(device, dataSourceId);
        dataSource.setType(request.sourceType());
        dataSource.setSourceType(request.sourceType());
        dataSource.setProtocol(request.protocol());
        String sourceId = normalizeRequired(request.externalId(), "externalId");
        dataSource.setName(sourceId);
        dataSource.setSourceId(sourceId);
        dataSource.setUpdatedAt(Instant.now());
        DataSource saved = dataSourceRepository.save(dataSource);
        auditService.record(user, device.getAccountId(), "DATA_SOURCE_UPDATED", "DATA_SOURCE", saved.getId());
        eventPublisher.publish("DATA_SOURCE", "UPDATED", device.getAccountId(), device.getId(), saved.getId(), saved.getStatus().name());
        runtimeConfigEventPublisher.publish(device.getAccountId(), saved);
        return toResponse(saved);
    }

    @Transactional
    public void disable(AuthenticatedUser user, UUID deviceId, UUID dataSourceId) {
        Device device = deviceService.findOwned(user, deviceId);
        DataSource dataSource = findOwnedDataSource(device, dataSourceId);
        Instant now = Instant.now();
        dataSource.setEnabled(false);
        dataSource.setStatus(SourceStatus.DISABLED);
        if (dataSource.getTokenRevokedAt() == null) {
            dataSource.setTokenRevokedAt(now);
        }
        dataSource.setUpdatedAt(now);
        DataSource saved = dataSourceRepository.save(dataSource);
        auditService.record(user, device.getAccountId(), "DATA_SOURCE_DISABLED", "DATA_SOURCE", saved.getId());
        eventPublisher.publish("DATA_SOURCE", "DISABLED", device.getAccountId(), device.getId(), saved.getId(), saved.getStatus().name());
        runtimeConfigEventPublisher.publish(device.getAccountId(), saved);
    }

    @Transactional(readOnly = true)
    public InternalDataSourceResponse findInternalByTokenPrefix(String tokenPrefix) {
        return toInternalResponse(dataSourceRepository.findByTokenPrefix(tokenPrefix)
                .orElseThrow(() -> new NotFoundException("Data source not found")));
    }

    @Transactional(readOnly = true)
    public InternalDataSourceResponse findInternalByExternalId(String externalId) {
        return toInternalResponse(dataSourceRepository.findFirstBySourceId(externalId)
                .orElseThrow(() -> new NotFoundException("Data source not found")));
    }

    @Transactional(readOnly = true)
    public InternalDataSourceResponse findInternal(UUID dataSourceId) {
        return toInternalResponse(dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new NotFoundException("Data source not found")));
    }

    private DataSource findOwnedDataSource(Device device, UUID dataSourceId) {
        return dataSourceRepository.findById(dataSourceId)
                .filter(source -> source.getDeviceId().equals(device.getId()))
                .orElseThrow(() -> new NotFoundException("Data source not found"));
    }

    private DataSourceResponse toResponse(DataSource dataSource) {
        return new DataSourceResponse(
                dataSource.getId(),
                dataSource.getDeviceId(),
                dataSource.getSourceType().name(),
                dataSource.getProtocol().name(),
                dataSource.getSourceId());
    }

    private InternalDataSourceResponse toInternalResponse(DataSource dataSource) {
        Device device = deviceRepository.findById(dataSource.getDeviceId())
                .orElseThrow(() -> new NotFoundException("Device not found"));
        return new InternalDataSourceResponse(
                device.getAccountId(),
                dataSource.getDeviceId(),
                dataSource.getId(),
                dataSource.getSourceId(),
                dataSource.getSourceType().name(),
                dataSource.getProtocol().name(),
                dataSource.getStatus().name(),
                dataSource.getTokenPrefix(),
                dataSource.getIngestionTokenHash());
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "vc_src_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash source token", exception);
        }
    }

    private String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException(field + " must be at most 128 characters");
        }
        if (!normalized.matches("^[A-Za-z0-9._:-]+$")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private DataSourceRequest validateImportRow(
            String[] columns,
            int rowNumber,
            Set<String> existingSourceIds,
            Set<String> importedSourceIds) {
        if (columns.length != 3) {
            throw new IllegalArgumentException("Row " + rowNumber + ": expected 3 columns");
        }
        String externalId = normalizeRequired(columns[0], "externalId");
        if (existingSourceIds.contains(externalId) || !importedSourceIds.add(externalId)) {
            throw new IllegalArgumentException("Row " + rowNumber + ": duplicate externalId " + externalId);
        }
        try {
            return new DataSourceRequest(
                    DataSourceType.valueOf(columns[1].trim()),
                    DataSourceProtocol.valueOf(columns[2].trim()),
                    externalId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Row " + rowNumber + ": unknown source type or protocol");
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
}
