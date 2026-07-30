package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.*;
import com.voltcanary.analytics.dto.*;
import com.voltcanary.analytics.repository.MlSettingsRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class MlSettingsService {
    private final MlSettingsRepository repository;
    public MlSettingsService(MlSettingsRepository repository) { this.repository = repository; }

    public MlSettingsResponse account(UUID accountId) {
        return repository.findByAccountIdAndDeviceIdIsNull(accountId).map(v -> response(v, "ACCOUNT"))
                .orElseGet(() -> defaults(accountId, null, "DEFAULT"));
    }
    public MlSettingsResponse device(UUID accountId, UUID deviceId) {
        return repository.findByDeviceId(deviceId).map(v -> {
            requireAccount(v, accountId); return response(v, "DEVICE_OVERRIDE");
        }).orElseGet(() -> { MlSettingsResponse v = account(accountId); return new MlSettingsResponse(accountId,
                deviceId, v.mlEnabled(), v.predictionMode(), v.confidenceWarningThreshold(),
                v.maintenanceBehavior(), v.explanationsVisible(), "INHERITED", v.updatedAt()); });
    }
    public MlSettingsResponse effective(UUID deviceId, UUID accountId) { return device(accountId, deviceId); }
    public MlSettingsResponse effectiveForAnalytics(UUID deviceId, UUID accountId) {
        return repository.findByDeviceId(deviceId).map(v -> response(v, "DEVICE_OVERRIDE"))
                .orElseGet(() -> accountId == null ? defaults(null, deviceId, "DEFAULT") : device(accountId, deviceId));
    }
    @Transactional public MlSettingsResponse saveAccount(UUID accountId, UUID userId, MlSettingsRequest request) {
        validate(request); MlSettings value = repository.findByAccountIdAndDeviceIdIsNull(accountId).orElseGet(MlSettings::new);
        apply(value, accountId, null, userId, request); return response(repository.save(value), "ACCOUNT");
    }
    @Transactional public MlSettingsResponse saveDevice(UUID accountId, UUID deviceId, UUID userId, MlSettingsRequest request) {
        validate(request); MlSettings value = repository.findByDeviceId(deviceId).orElseGet(MlSettings::new);
        if (value.getAccountId() != null) requireAccount(value, accountId);
        apply(value, accountId, deviceId, userId, request); return response(repository.save(value), "DEVICE_OVERRIDE");
    }
    @Transactional public void clearDevice(UUID accountId, UUID deviceId) {
        repository.findByDeviceId(deviceId).ifPresent(v -> { requireAccount(v, accountId); repository.delete(v); });
    }
    private void validate(MlSettingsRequest v) {
        if (v.confidenceWarningThreshold() < 0 || v.confidenceWarningThreshold() > 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confidence warning threshold must be between 0 and 1");
        if (!v.mlEnabled() && v.predictionMode() != PredictionMode.RULE_BASED_ONLY)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Disabled ML requires RULE_BASED_ONLY mode");
    }
    private void apply(MlSettings v, UUID accountId, UUID deviceId, UUID userId, MlSettingsRequest r) {
        if (v.getId() == null) v.setId(UUID.randomUUID()); v.setAccountId(accountId); v.setDeviceId(deviceId);
        v.setMlEnabled(r.mlEnabled()); v.setPredictionMode(r.predictionMode());
        v.setConfidenceWarningThreshold(r.confidenceWarningThreshold()); v.setMaintenanceBehavior(r.maintenanceBehavior());
        v.setExplanationsVisible(r.explanationsVisible()); v.setUpdatedAt(Instant.now()); v.setUpdatedBy(userId);
    }
    private void requireAccount(MlSettings v, UUID accountId) {
        if (!accountId.equals(v.getAccountId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML settings not found");
    }
    private MlSettingsResponse response(MlSettings v, String source) { return new MlSettingsResponse(v.getAccountId(), v.getDeviceId(),
            v.isMlEnabled(), v.getPredictionMode(), v.getConfidenceWarningThreshold(), v.getMaintenanceBehavior(),
            v.isExplanationsVisible(), source, v.getUpdatedAt()); }
    private MlSettingsResponse defaults(UUID accountId, UUID deviceId, String source) { return new MlSettingsResponse(accountId, deviceId,
            true, PredictionMode.MODEL_OR_BASELINE_HEURISTIC, .6, MaintenanceMlBehavior.REDUCE_CONFIDENCE, true, source, null); }
}
