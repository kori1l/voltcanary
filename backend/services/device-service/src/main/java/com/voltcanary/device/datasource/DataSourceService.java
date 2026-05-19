package com.voltcanary.device.datasource;

import com.voltcanary.device.device.Device;
import com.voltcanary.device.device.DeviceRepository;
import com.voltcanary.device.device.DeviceService;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.shared.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataSourceService {

    private final DeviceService deviceService;
    private final DeviceRepository deviceRepository;
    private final DataSourceRepository dataSourceRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public DataSourceService(
            DeviceService deviceService,
            DeviceRepository deviceRepository,
            DataSourceRepository dataSourceRepository) {
        this.deviceService = deviceService;
        this.deviceRepository = deviceRepository;
        this.dataSourceRepository = dataSourceRepository;
    }

    @Transactional
    public DataSourceResponse create(AuthenticatedUser user, UUID deviceId, DataSourceRequest request) {
        Device device = deviceService.findOwned(user, deviceId);

        Instant now = Instant.now();
        DataSource dataSource = new DataSource();
        dataSource.setId(UUID.randomUUID());
        dataSource.setDeviceId(device.getId());
        dataSource.setName(request.externalId());
        dataSource.setType(request.sourceType());
        dataSource.setConnectionRef(request.protocol());
        dataSource.setStatus(SourceStatus.ACTIVE);
        dataSource.setCreatedAt(now);
        dataSource.setUpdatedAt(now);

        return toResponse(dataSourceRepository.save(dataSource));
    }

    @Transactional
    public SourceTokenResponse rotateToken(AuthenticatedUser user, UUID deviceId, UUID dataSourceId) {
        Device device = deviceService.findOwned(user, deviceId);
        DataSource dataSource = dataSourceRepository.findById(dataSourceId)
                .filter(source -> source.getDeviceId().equals(device.getId()))
                .orElseThrow(() -> new NotFoundException("Data source not found"));

        String token = generateToken();
        String prefix = token.substring(0, Math.min(16, token.length()));
        dataSource.setIngestionTokenHash(sha256Hex(token));
        dataSource.setTokenPrefix(prefix);
        dataSource.setTokenCreatedAt(Instant.now());
        dataSource.setTokenRevokedAt(null);
        dataSource.setUpdatedAt(Instant.now());
        dataSourceRepository.save(dataSource);
        return new SourceTokenResponse(token, prefix);
    }

    @Transactional
    public void revokeToken(AuthenticatedUser user, UUID deviceId, UUID dataSourceId) {
        Device device = deviceService.findOwned(user, deviceId);
        DataSource dataSource = dataSourceRepository.findById(dataSourceId)
                .filter(source -> source.getDeviceId().equals(device.getId()))
                .orElseThrow(() -> new NotFoundException("Data source not found"));
        dataSource.setTokenRevokedAt(Instant.now());
        dataSource.setStatus(SourceStatus.REVOKED);
        dataSource.setUpdatedAt(Instant.now());
        dataSourceRepository.save(dataSource);
    }

    @Transactional
    public void markTokenUsed(UUID dataSourceId) {
        DataSource dataSource = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new NotFoundException("Data source not found"));
        dataSource.setTokenLastUsedAt(Instant.now());
        dataSource.setUpdatedAt(Instant.now());
        dataSourceRepository.save(dataSource);
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID deviceId, String sourceId) {
        return dataSourceRepository.existsByDeviceIdAndName(deviceId, sourceId);
    }

    @Transactional
    public DataSourceResponse update(AuthenticatedUser user, UUID deviceId, UUID dataSourceId, DataSourceRequest request) {
        Device device = deviceService.findOwned(user, deviceId);
        DataSource dataSource = dataSourceRepository.findById(dataSourceId)
                .filter(source -> source.getDeviceId().equals(device.getId()))
                .orElseThrow(() -> new NotFoundException("Data source not found"));
        dataSource.setType(request.sourceType());
        dataSource.setConnectionRef(request.protocol());
        dataSource.setName(request.externalId());
        dataSource.setUpdatedAt(Instant.now());
        return toResponse(dataSourceRepository.save(dataSource));
    }

    @Transactional(readOnly = true)
    public InternalDataSourceResponse findInternalByTokenPrefix(String tokenPrefix) {
        return toInternalResponse(dataSourceRepository.findByTokenPrefix(tokenPrefix)
                .orElseThrow(() -> new NotFoundException("Data source not found")));
    }

    @Transactional(readOnly = true)
    public InternalDataSourceResponse findInternalByExternalId(String externalId) {
        return toInternalResponse(dataSourceRepository.findByName(externalId)
                .orElseThrow(() -> new NotFoundException("Data source not found")));
    }

    @Transactional(readOnly = true)
    public InternalDataSourceResponse findInternal(UUID dataSourceId) {
        return toInternalResponse(dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new NotFoundException("Data source not found")));
    }

    private DataSourceResponse toResponse(DataSource dataSource) {
        return new DataSourceResponse(
                dataSource.getId(),
                dataSource.getDeviceId(),
                dataSource.getType().name(),
                dataSource.getConnectionRef().name(),
                dataSource.getName());
    }

    private InternalDataSourceResponse toInternalResponse(DataSource dataSource) {
        Device device = deviceRepository.findById(dataSource.getDeviceId())
                .orElseThrow(() -> new NotFoundException("Device not found"));
        return new InternalDataSourceResponse(
                device.getAccountId(),
                dataSource.getDeviceId(),
                dataSource.getId(),
                dataSource.getName(),
                dataSource.getType().name(),
                dataSource.getConnectionRef().name(),
                dataSource.getStatus().name(),
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
}
