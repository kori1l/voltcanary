package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlSettings;
import com.voltcanary.analytics.dto.MlSettingsRequest;
import com.voltcanary.analytics.dto.MlSettingsResponse;
import com.voltcanary.analytics.repository.MlSettingsRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlPolicyService {

    private final MlSettingsRepository repository;
    private final MlPolicyMapper mapper;
    private final MlPolicyValidator validator;

    public MlPolicyService(
            MlSettingsRepository repository,
            MlPolicyMapper mapper,
            MlPolicyValidator validator) {
        this.repository = repository;
        this.mapper = mapper;
        this.validator = validator;
    }

    public MlSettingsResponse account(UUID accountId) {
        return repository.findByAccountIdAndDeviceIdIsNull(accountId)
                .map(value -> mapper.response(value, "ACCOUNT"))
                .orElseGet(() -> mapper.defaults(accountId, null, "DEFAULT"));
    }

    public MlSettingsResponse device(UUID accountId, UUID deviceId) {
        return repository.findByDeviceId(deviceId)
                .map(value -> {
                    requireAccount(value, accountId);
                    return mapper.response(value, "DEVICE_OVERRIDE");
                })
                .orElseGet(() -> mapper.inherited(account(accountId), deviceId));
    }

    public MlSettingsResponse effective(UUID deviceId, UUID accountId) {
        return device(accountId, deviceId);
    }

    public MlSettingsResponse effectiveForAnalytics(UUID deviceId, UUID accountId) {
        return repository.findByDeviceId(deviceId)
                .map(value -> mapper.response(value, "DEVICE_OVERRIDE"))
                .orElseGet(() -> accountId == null
                        ? mapper.defaults(null, deviceId, "DEFAULT")
                        : device(accountId, deviceId));
    }

    @Transactional
    public MlSettingsResponse saveAccount(UUID accountId, UUID userId, MlSettingsRequest request) {
        validator.validate(request);
        MlSettings value = repository.findByAccountIdAndDeviceIdIsNull(accountId).orElseGet(MlSettings::new);
        apply(value, accountId, null, userId, request);
        return mapper.response(repository.save(value), "ACCOUNT");
    }

    @Transactional
    public MlSettingsResponse saveDevice(UUID accountId, UUID deviceId, UUID userId, MlSettingsRequest request) {
        validator.validate(request);
        MlSettings value = repository.findByDeviceId(deviceId).orElseGet(MlSettings::new);
        if (value.getAccountId() != null) {
            requireAccount(value, accountId);
        }
        apply(value, accountId, deviceId, userId, request);
        return mapper.response(repository.save(value), "DEVICE_OVERRIDE");
    }

    @Transactional
    public void clearDevice(UUID accountId, UUID deviceId) {
        repository.findByDeviceId(deviceId).ifPresent(value -> {
            requireAccount(value, accountId);
            repository.delete(value);
        });
    }

    private void apply(MlSettings value, UUID accountId, UUID deviceId, UUID userId, MlSettingsRequest request) {
        if (value.getId() == null) {
            value.setId(UUID.randomUUID());
        }
        value.setAccountId(accountId);
        value.setDeviceId(deviceId);
        value.setMlEnabled(request.mlEnabled());
        value.setPredictionMode(request.predictionMode());
        value.setConfidenceWarningThreshold(request.confidenceWarningThreshold());
        value.setMaintenanceBehavior(request.maintenanceBehavior());
        value.setExplanationsVisible(request.explanationsVisible());
        value.setUpdatedAt(Instant.now());
        value.setUpdatedBy(userId);
    }

    private void requireAccount(MlSettings value, UUID accountId) {
        if (!accountId.equals(value.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML settings not found");
        }
    }
}
