package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDeploymentAuditEvent;
import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentPolicy;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.dto.MlDeploymentPolicyDto;
import com.voltcanary.analytics.dto.MlDeploymentPolicyRequest;
import com.voltcanary.analytics.dto.MlModelStatusRequest;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import com.voltcanary.analytics.dto.MlResolvedDeploymentPolicyResponse;
import com.voltcanary.analytics.repository.MlDeploymentAuditEventRepository;
import com.voltcanary.analytics.repository.MlDeploymentPolicyRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(MlModelRegistryService.class);

    private final MlModelVersionRepository modelVersionRepository;
    private final MlDeploymentPolicyRepository policyRepository;
    private final MlDeploymentAuditEventRepository auditRepository;
    private final ObjectMapper objectMapper;

    public MlModelRegistryService(
            MlModelVersionRepository modelVersionRepository,
            MlDeploymentPolicyRepository policyRepository,
            MlDeploymentAuditEventRepository auditRepository,
            ObjectMapper objectMapper) {
        this.modelVersionRepository = modelVersionRepository;
        this.policyRepository = policyRepository;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<MlModelVersionDto> listModels(UUID accountId) {
        ensureDemoModels(accountId);
        return modelVersionRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(this::modelResponse)
                .toList();
    }

    public MlModelVersionDto getModel(UUID accountId, UUID modelVersionId) {
        return modelResponse(findOwnedModel(accountId, modelVersionId));
    }

    @Transactional
    public MlModelVersionDto approve(UUID accountId, UUID actorId, UUID modelVersionId, MlModelStatusRequest request) {
        MlModelVersion model = findOwnedModel(accountId, modelVersionId);
        if (model.getStatus() != MlModelStatus.CANDIDATE && model.getStatus() != MlModelStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only candidate or rejected models can be approved");
        }
        model.setStatus(MlModelStatus.APPROVED);
        model.setApprovedBy(actorId);
        model.setApprovedAt(Instant.now());
        modelVersionRepository.save(model);
        audit(accountId, actorId, model.getId(), null, "MODEL_APPROVED", Map.of("reason", reason(request)));
        log.info("mlModelPromoted modelVersionId={} accountId={} actorId={} status={} reason={}",
                model.getId(), accountId, actorId, model.getStatus(), reason(request));
        return modelResponse(model);
    }

    @Transactional
    public MlModelVersionDto reject(UUID accountId, UUID actorId, UUID modelVersionId, MlModelStatusRequest request) {
        MlModelVersion model = findOwnedModel(accountId, modelVersionId);
        if (model.getStatus() == MlModelStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Active models must be rolled back before rejection");
        }
        model.setStatus(MlModelStatus.REJECTED);
        modelVersionRepository.save(model);
        audit(accountId, actorId, model.getId(), null, "MODEL_REJECTED", Map.of("reason", reason(request)));
        log.info("mlModelRejected modelVersionId={} accountId={} actorId={} reason={}",
                model.getId(), accountId, actorId, reason(request));
        return modelResponse(model);
    }

    @Transactional
    public MlModelVersionDto archive(UUID accountId, UUID actorId, UUID modelVersionId, MlModelStatusRequest request) {
        MlModelVersion model = findOwnedModel(accountId, modelVersionId);
        if (model.getStatus() == MlModelStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Active models must be rolled back before archive");
        }
        model.setStatus(MlModelStatus.ARCHIVED);
        modelVersionRepository.save(model);
        audit(accountId, actorId, model.getId(), null, "MODEL_ARCHIVED", Map.of("reason", reason(request)));
        log.info("mlModelArchived modelVersionId={} accountId={} actorId={} reason={}",
                model.getId(), accountId, actorId, reason(request));
        return modelResponse(model);
    }

    @Transactional
    public MlDeploymentPolicyDto upsertPolicy(
            UUID accountId,
            UUID actorId,
            MlDeploymentPolicyRequest request) {
        validatePolicyRequest(request);
        MlModelVersion model = null;
        if (request.modelVersionId() != null) {
            model = findOwnedModel(accountId, request.modelVersionId());
            if (model.getStatus() != MlModelStatus.APPROVED && model.getStatus() != MlModelStatus.ACTIVE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Deployment policy requires approved or active model");
            }
        }

        MlDeploymentPolicy policy = findExistingPolicy(accountId, request)
                .orElseGet(() -> {
                    MlDeploymentPolicy created = new MlDeploymentPolicy();
                    created.setId(UUID.randomUUID());
                    return created;
                });
        policy.setAccountId(request.scope() == MlDeploymentScope.SYSTEM ? null : accountId);
        policy.setScope(request.scope());
        policy.setDeviceId(request.deviceId());
        policy.setDeviceType(request.deviceType());
        policy.setModelVersionId(request.modelVersionId());
        policy.setMode(request.mode());
        policy.setCanaryPercent(request.canaryPercent());
        policy.setFallbackEnabled(request.fallbackEnabled() == null || request.fallbackEnabled());
        policy.setUpdatedBy(actorId);
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);

        if (model != null && request.mode() == MlDeploymentMode.ACTIVE) {
            model.setStatus(MlModelStatus.ACTIVE);
            modelVersionRepository.save(model);
        }
        audit(accountId, actorId, request.modelVersionId(), policy.getId(), "POLICY_UPDATED", Map.of(
                "scope", request.scope().name(),
                "mode", request.mode().name(),
                "reason", reason(request.reason())));
        log.info("mlDeploymentPolicyUpdated policyId={} accountId={} actorId={} scope={} mode={} modelVersionId={} fallbackEnabled={} reason={}",
                policy.getId(), accountId, actorId, policy.getScope(), policy.getMode(), policy.getModelVersionId(),
                policy.isFallbackEnabled(), reason(request.reason()));
        return policyResponse(policy);
    }

    @Transactional
    public MlDeploymentPolicyDto rollbackPolicy(
            UUID accountId,
            UUID actorId,
            MlDeploymentScope scope,
            UUID deviceId,
            String deviceType,
            String reason) {
        MlDeploymentPolicy policy = findExistingPolicy(accountId,
                new MlDeploymentPolicyRequest(scope, deviceId, deviceType, null, MlDeploymentMode.DISABLED, null, true, reason))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML deployment policy not found"));
        UUID previousModelId = policy.getModelVersionId();
        policy.setModelVersionId(null);
        policy.setMode(MlDeploymentMode.DISABLED);
        policy.setFallbackEnabled(true);
        policy.setUpdatedBy(actorId);
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);
        audit(accountId, actorId, previousModelId, policy.getId(), "POLICY_ROLLED_BACK", Map.of("reason", reason(reason)));
        log.info("mlDeploymentPolicyRolledBack policyId={} accountId={} actorId={} previousModelVersionId={} scope={} reason={}",
                policy.getId(), accountId, actorId, previousModelId, policy.getScope(), reason(reason));
        return policyResponse(policy);
    }

    public MlResolvedDeploymentPolicyResponse resolvePolicy(UUID accountId, UUID deviceId, String deviceType) {
        Optional<MlDeploymentPolicy> selected = Optional.empty();
        MlDeploymentScope source = null;
        if (deviceId != null) {
            selected = policyRepository.findByScopeAndDeviceId(MlDeploymentScope.DEVICE, deviceId);
            source = MlDeploymentScope.DEVICE;
        }
        if (selected.isEmpty() && deviceType != null && !deviceType.isBlank()) {
            selected = policyRepository.findByAccountIdAndScopeAndDeviceType(accountId, MlDeploymentScope.DEVICE_TYPE, deviceType);
            source = MlDeploymentScope.DEVICE_TYPE;
        }
        if (selected.isEmpty()) {
            selected = policyRepository.findByAccountIdAndScope(accountId, MlDeploymentScope.ACCOUNT);
            source = MlDeploymentScope.ACCOUNT;
        }
        if (selected.isEmpty()) {
            selected = policyRepository.findByAccountIdAndScope(null, MlDeploymentScope.SYSTEM);
            source = MlDeploymentScope.SYSTEM;
        }
        if (selected.isEmpty()) {
            return new MlResolvedDeploymentPolicyResponse(null, null, null, MlDeploymentMode.DISABLED, true, "BASELINE_HEURISTIC_FALLBACK");
        }
        MlDeploymentPolicy policy = selected.get();
        if (policy.getModelVersionId() == null || policy.getMode() == MlDeploymentMode.DISABLED) {
            return new MlResolvedDeploymentPolicyResponse(source, policy.getId(), policy.getModelVersionId(), policy.getMode(), policy.isFallbackEnabled(), "POLICY_DISABLED");
        }
        return new MlResolvedDeploymentPolicyResponse(source, policy.getId(), policy.getModelVersionId(), policy.getMode(), policy.isFallbackEnabled(), null);
    }

    public List<MlDeploymentPolicyDto> listPolicies(UUID accountId) {
        return policyRepository.findByAccountIdOrderByUpdatedAtDesc(accountId).stream()
                .map(this::policyResponse)
                .toList();
    }

    private MlModelVersion findOwnedModel(UUID accountId, UUID modelVersionId) {
        MlModelVersion model = modelVersionRepository.findById(modelVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML model version not found"));
        if (!accountId.equals(model.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML model version not found");
        }
        return model;
    }

    private void ensureDemoModels(UUID accountId) {
        if (!modelVersionRepository.findByAccountIdOrderByCreatedAtDesc(accountId).isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        modelVersionRepository.save(demoModel(
                accountId,
                "anomaly-baseline",
                1,
                MlModelFamily.ANOMALY_DETECTION,
                "baseline-statistical-anomaly-v1",
                MlModelStatus.APPROVED,
                List.of("GENERIC_APPLIANCE", "AIR_CONDITIONER", "FRIDGE"),
                List.of("voltage", "current", "activePowerW", "energyGrowth"),
                Map.of("precision", 0.86, "recall", 0.78, "f1", 0.82, "trainingRows", 12480),
                now.minusSeconds(180)));
        modelVersionRepository.save(demoModel(
                accountId,
                "degradation-score",
                1,
                MlModelFamily.DEGRADATION_SCORING,
                "rolling-degradation-score-v1",
                MlModelStatus.APPROVED,
                List.of("GENERIC_APPLIANCE", "PUMP", "HEATER"),
                List.of("activePowerW", "current", "temperatureC", "runtimeHours"),
                Map.of("mae", 0.11, "precision", 0.81, "recall", 0.74, "trainingRows", 9180),
                now.minusSeconds(120)));
        modelVersionRepository.save(demoModel(
                accountId,
                "energy-forecast",
                1,
                MlModelFamily.FORECASTING,
                "seasonal-energy-forecast-v1",
                MlModelStatus.CANDIDATE,
                List.of("GENERIC_APPLIANCE", "FRIDGE", "SERVER_NAS"),
                List.of("activePowerW", "energyDelta", "temperatureC"),
                Map.of("mape", 0.09, "rmse", 0.18, "trainingRows", 20160),
                now.minusSeconds(60)));
    }

    private MlModelVersion demoModel(
            UUID accountId,
            String modelKeySuffix,
            int version,
            MlModelFamily family,
            String algorithm,
            MlModelStatus status,
            List<String> compatibleDeviceTypes,
            List<String> supportedMetrics,
            Map<String, Object> evaluationMetrics,
            Instant createdAt) {
        MlModelVersion model = new MlModelVersion();
        model.setId(UUID.randomUUID());
        model.setAccountId(accountId);
        model.setModelKey("demo-" + accountId.toString().substring(0, 8) + "-" + modelKeySuffix);
        model.setVersion(version);
        model.setFamily(family);
        model.setAlgorithm(algorithm);
        model.setStatus(status);
        model.setFeatureSchemaHash("sha256:demo-" + modelKeySuffix);
        model.setArtifactUri("artifact://demo/" + modelKeySuffix + "/model.json");
        model.setArtifactHash("sha256:demo-artifact-" + modelKeySuffix);
        model.setCompatibleDeviceTypesJson(writeJson(compatibleDeviceTypes));
        model.setSupportedMetricsJson(writeJson(supportedMetrics));
        model.setEvaluationMetricsJson(writeJson(evaluationMetrics));
        model.setCreatedAt(createdAt);
        return model;
    }

    private Optional<MlDeploymentPolicy> findExistingPolicy(UUID accountId, MlDeploymentPolicyRequest request) {
        return switch (request.scope()) {
            case DEVICE -> policyRepository.findByScopeAndDeviceId(MlDeploymentScope.DEVICE, request.deviceId());
            case DEVICE_TYPE -> policyRepository.findByAccountIdAndScopeAndDeviceType(accountId, MlDeploymentScope.DEVICE_TYPE, request.deviceType());
            case ACCOUNT -> policyRepository.findByAccountIdAndScope(accountId, MlDeploymentScope.ACCOUNT);
            case SYSTEM -> policyRepository.findByAccountIdAndScope(null, MlDeploymentScope.SYSTEM);
        };
    }

    private void validatePolicyRequest(MlDeploymentPolicyRequest request) {
        if (request.scope() == MlDeploymentScope.DEVICE && request.deviceId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device policy requires deviceId");
        }
        if (request.scope() == MlDeploymentScope.DEVICE_TYPE && (request.deviceType() == null || request.deviceType().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device type policy requires deviceType");
        }
        if (request.mode() == MlDeploymentMode.CANARY
                && (request.canaryPercent() == null || request.canaryPercent() < 0 || request.canaryPercent() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Canary policy requires percent between 0 and 100");
        }
        if (request.mode() != MlDeploymentMode.DISABLED && request.modelVersionId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enabled deployment policy requires modelVersionId");
        }
    }

    private void audit(UUID accountId, UUID actorId, UUID modelVersionId, UUID policyId, String action, Map<String, Object> details) {
        MlDeploymentAuditEvent event = new MlDeploymentAuditEvent();
        event.setId(UUID.randomUUID());
        event.setAccountId(accountId);
        event.setModelVersionId(modelVersionId);
        event.setPolicyId(policyId);
        event.setAction(action);
        event.setDetailsJson(writeJson(details));
        event.setActorId(actorId);
        event.setCreatedAt(Instant.now());
        auditRepository.save(event);
    }

    private String reason(MlModelStatusRequest request) {
        return request == null ? "" : reason(request.reason());
    }

    private String reason(String value) {
        return value == null ? "" : value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize ML registry audit details");
        }
    }

    private MlModelVersionDto modelResponse(MlModelVersion model) {
        return new MlModelVersionDto(
                model.getId(),
                model.getAccountId(),
                model.getModelKey(),
                model.getVersion(),
                model.getFamily(),
                model.getAlgorithm(),
                model.getStatus(),
                model.getDatasetVersionId(),
                model.getFeatureSnapshotId(),
                model.getFeatureSchemaHash(),
                model.getArtifactUri(),
                model.getArtifactHash(),
                model.getCompatibleDeviceTypesJson(),
                model.getSupportedMetricsJson(),
                model.getEvaluationMetricsJson(),
                model.getApprovedBy(),
                model.getApprovedAt(),
                model.getCreatedAt());
    }

    private MlDeploymentPolicyDto policyResponse(MlDeploymentPolicy policy) {
        return new MlDeploymentPolicyDto(
                policy.getId(),
                policy.getAccountId(),
                policy.getScope(),
                policy.getDeviceId(),
                policy.getDeviceType(),
                policy.getModelVersionId(),
                policy.getMode(),
                policy.getCanaryPercent(),
                policy.isFallbackEnabled(),
                policy.getUpdatedBy(),
                policy.getUpdatedAt());
    }
}
