package com.voltcanary.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.config.MlRegistryProperties;
import com.voltcanary.analytics.domain.MlDeploymentAuditEvent;
import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentPolicy;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.dto.MlDeploymentPolicyDto;
import com.voltcanary.analytics.dto.MlDeploymentPolicyRequest;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.dto.MlModelStatusRequest;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import com.voltcanary.analytics.dto.MlResolvedDeploymentPolicyResponse;
import com.voltcanary.analytics.repository.MlDeploymentAuditEventRepository;
import com.voltcanary.analytics.repository.MlDeploymentPolicyRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.PredictionResultRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private final PredictionResultRepository predictionResultRepository;
    private final ObjectMapper objectMapper;
    private final MlRegistryProperties registryProperties;

    public MlModelRegistryService(
            MlModelVersionRepository modelVersionRepository,
            MlDeploymentPolicyRepository policyRepository,
            MlDeploymentAuditEventRepository auditRepository,
            PredictionResultRepository predictionResultRepository,
            ObjectMapper objectMapper,
            MlRegistryProperties registryProperties) {
        this.modelVersionRepository = modelVersionRepository;
        this.policyRepository = policyRepository;
        this.auditRepository = auditRepository;
        this.predictionResultRepository = predictionResultRepository;
        this.objectMapper = objectMapper;
        this.registryProperties = registryProperties;
    }

    @Transactional
    public List<MlModelVersionDto> listModels(UUID accountId) {
        return listModels(accountId, "default");
    }

    @Transactional
    public List<MlModelVersionDto> listModels(UUID accountId, String statusFilter) {
        if (registryProperties.isDemoSeedingEnabled()) {
            ensureDemoModels(accountId);
        }
        String filter = normalizedFilter(statusFilter);
        return modelVersionRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .filter(model -> matchesModelFilter(model.getStatus(), filter))
                .map(this::modelResponse)
                .toList();
    }

    private boolean matchesModelFilter(MlModelStatus status, String filter) {
        return switch (filter) {
            case "all" -> true;
            case "failed" -> status == MlModelStatus.REJECTED;
            case "archived" -> status == MlModelStatus.ARCHIVED;
            case "deleted" -> status == MlModelStatus.DELETED;
            default -> status != MlModelStatus.ARCHIVED && status != MlModelStatus.DELETED;
        };
    }

    public MlModelVersionDto getModel(UUID accountId, UUID modelVersionId) {
        return modelResponse(findOwnedModel(accountId, modelVersionId));
    }

    @Transactional(readOnly = true)
    public MlLifecycleImpactResponse modelImpact(UUID accountId, UUID modelVersionId, String action) {
        MlModelVersion model = findOwnedModel(accountId, modelVersionId);
        String normalizedAction = normalizedAction(action);
        List<Map<String, Object>> dependencies = modelDependencies(model);
        boolean blocked = "delete".equals(normalizedAction)
                && (model.getStatus() == MlModelStatus.ACTIVE
                || dependencies.stream().anyMatch(dependency -> !"predictionResults".equals(dependency.get("type")) || predictionCount(dependency) > 0));
        String message = blocked
                ? "Model has runtime or prediction references and should be archived instead of deleted"
                : "Model action is allowed when submitted with an audit reason";
        return new MlLifecycleImpactResponse(normalizedAction, blocked, message, dependencies);
    }

    @Transactional
    public MlModelVersionDto deleteModel(UUID accountId, UUID actorId, UUID modelVersionId, MlLifecycleActionRequest request) {
        MlModelVersion model = findOwnedModel(accountId, modelVersionId);
        String reason = requiredReason(request);
        List<Map<String, Object>> dependencies = activeModelPolicyDependencies(modelVersionId);
        if (model.getStatus() == MlModelStatus.ACTIVE || !dependencies.isEmpty()) {
            throw new MlLifecycleConflictException(
                    "MODEL_POLICY_REFERENCED",
                    "Model is active or referenced by deployment policies",
                    dependencies.isEmpty()
                            ? List.of(dependency("modelVersion", model.getId(), Map.of("status", model.getStatus().name())))
                            : dependencies);
        }
        long predictionReferences = predictionResultRepository.countByModelId(modelVersionId);
        if (predictionReferences > 0) {
            throw new MlLifecycleConflictException(
                    "MODEL_PREDICTION_REFERENCED",
                    "Model has historical prediction references and must be archived instead of deleted",
                    dependency("predictionResults", model.getId(), Map.of("count", predictionReferences)));
        }
        if (model.getStatus() != MlModelStatus.CANDIDATE && model.getStatus() != MlModelStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only unreferenced candidate or rejected models can be deleted");
        }
        model.setStatus(MlModelStatus.DELETED);
        stampLifecycle(model, actorId, reason);
        modelVersionRepository.save(model);
        audit(accountId, actorId, model.getId(), null, "MODEL_DELETED", Map.of("reason", reason));
        log.info("mlModelDeleted modelVersionId={} accountId={} actorId={} reason={}",
                model.getId(), accountId, actorId, reason);
        return modelResponse(model);
    }

    private List<Map<String, Object>> modelDependencies(MlModelVersion model) {
        List<Map<String, Object>> policies = activeModelPolicyDependencies(model.getId());
        long predictionReferences = predictionResultRepository.countByModelId(model.getId());
        List<Map<String, Object>> dependencies = new java.util.ArrayList<>(policies);
        if (model.getStatus() == MlModelStatus.ACTIVE && policies.isEmpty()) {
            dependencies.add(dependency("modelVersion", model.getId(), Map.of("status", model.getStatus().name())));
        }
        dependencies.add(dependency("predictionResults", model.getId(), Map.of("count", predictionReferences)));
        return dependencies;
    }

    private List<Map<String, Object>> activeModelPolicyDependencies(UUID modelVersionId) {
        return policyRepository.findByModelVersionIdAndDeletedAtIsNull(modelVersionId).stream()
                .filter(policy -> policy.getMode() != MlDeploymentMode.DISABLED)
                .map(policy -> dependency("deploymentPolicy", policy.getId(), Map.of(
                        "scope", policy.getScope().name(),
                        "mode", policy.getMode().name())))
                .toList();
    }

    private long predictionCount(Map<String, Object> dependency) {
        Object details = dependency.get("details");
        if (!(details instanceof Map<?, ?> values)) {
            return 0;
        }
        Object count = values.get("count");
        return count instanceof Number number ? number.longValue() : 0;
    }

    @Transactional
    public MlModelVersionDto approve(UUID accountId, UUID actorId, UUID modelVersionId, MlModelStatusRequest request) {
        MlModelVersion model = findOwnedModel(accountId, modelVersionId);
        if (model.getStatus() != MlModelStatus.CANDIDATE && model.getStatus() != MlModelStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only candidate or rejected models can be approved");
        }
        verifyArtifactMetadata(model);
        verifyFailureApprovalGates(model);
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
        stampLifecycle(model, actorId, reason(request));
        modelVersionRepository.save(model);
        audit(accountId, actorId, model.getId(), null, "MODEL_ARCHIVED", Map.of("reason", reason(request)));
        log.info("mlModelArchived modelVersionId={} accountId={} actorId={} reason={}",
                model.getId(), accountId, actorId, reason(request));
        return modelResponse(model);
    }

    @Transactional
    public MlDeploymentPolicyDto disablePolicy(UUID accountId, UUID actorId, UUID policyId, MlLifecycleActionRequest request) {
        MlDeploymentPolicy policy = findOwnedPolicy(accountId, policyId);
        String reason = requiredReason(request);
        if (policy.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML deployment policy not found");
        }
        policy.setMode(MlDeploymentMode.DISABLED);
        policy.setFallbackEnabled(true);
        policy.setUpdatedBy(actorId);
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);
        audit(accountId, actorId, policy.getModelVersionId(), policy.getId(), "POLICY_DISABLED", Map.of("reason", reason));
        log.info("mlDeploymentPolicyDisabled policyId={} accountId={} actorId={} reason={}", policy.getId(), accountId, actorId, reason);
        return policyResponse(policy);
    }

    @Transactional
    public MlDeploymentPolicyDto deletePolicy(UUID accountId, UUID actorId, UUID policyId, MlLifecycleActionRequest request) {
        MlDeploymentPolicy policy = findOwnedPolicy(accountId, policyId);
        String reason = requiredReason(request);
        if (policy.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML deployment policy not found");
        }
        if (policy.getMode() != MlDeploymentMode.DISABLED) {
            throw new MlLifecycleConflictException(
                    "POLICY_ACTIVE",
                    "Deployment policy must be disabled before deletion",
                    dependency("deploymentPolicy", policy.getId(), Map.of("scope", policy.getScope().name(), "mode", policy.getMode().name())));
        }
        policy.setDeletedAt(Instant.now());
        policy.setDeletedBy(actorId);
        policy.setDeletionReason(reason);
        policy.setUpdatedBy(actorId);
        policy.setUpdatedAt(policy.getDeletedAt());
        policyRepository.save(policy);
        audit(accountId, actorId, policy.getModelVersionId(), policy.getId(), "POLICY_DELETED", Map.of("reason", reason));
        log.info("mlDeploymentPolicyDeleted policyId={} accountId={} actorId={} reason={}", policy.getId(), accountId, actorId, reason);
        return policyResponse(policy);
    }

    @Transactional(readOnly = true)
    public MlLifecycleImpactResponse policyImpact(UUID accountId, UUID policyId, String action) {
        MlDeploymentPolicy policy = findOwnedPolicy(accountId, policyId);
        String normalizedAction = normalizedAction(action);
        boolean blocked = "delete".equals(normalizedAction) && policy.getMode() != MlDeploymentMode.DISABLED;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scope", policy.getScope().name());
        details.put("mode", policy.getMode().name());
        details.put("modelVersionId", policy.getModelVersionId() == null ? null : policy.getModelVersionId().toString());
        details.put("fallbackEnabled", policy.isFallbackEnabled());
        details.put("deleted", policy.getDeletedAt() != null);
        String message = blocked
                ? "Deployment policy must be disabled before deletion"
                : "Deployment policy action is allowed when submitted with an audit reason";
        return new MlLifecycleImpactResponse(
                normalizedAction,
                blocked,
                message,
                List.of(dependency("deploymentPolicy", policy.getId(), details)));
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
            selected = policyRepository.findByScopeAndDeviceIdAndDeletedAtIsNull(MlDeploymentScope.DEVICE, deviceId);
            source = MlDeploymentScope.DEVICE;
        }
        if (selected.isEmpty() && deviceType != null && !deviceType.isBlank()) {
            selected = policyRepository.findByAccountIdAndScopeAndDeviceTypeAndDeletedAtIsNull(accountId, MlDeploymentScope.DEVICE_TYPE, deviceType);
            source = MlDeploymentScope.DEVICE_TYPE;
        }
        if (selected.isEmpty()) {
            selected = policyRepository.findByAccountIdAndScopeAndDeletedAtIsNull(accountId, MlDeploymentScope.ACCOUNT);
            source = MlDeploymentScope.ACCOUNT;
        }
        if (selected.isEmpty()) {
            selected = policyRepository.findByAccountIdAndScopeAndDeletedAtIsNull(null, MlDeploymentScope.SYSTEM);
            source = MlDeploymentScope.SYSTEM;
        }
        if (selected.isEmpty()) {
            return new MlResolvedDeploymentPolicyResponse(null, null, null, MlDeploymentMode.DISABLED, deviceType, null, null, true, "BASELINE_HEURISTIC_FALLBACK");
        }
        MlDeploymentPolicy policy = selected.get();
        if (policy.getModelVersionId() == null || policy.getMode() == MlDeploymentMode.DISABLED) {
            return new MlResolvedDeploymentPolicyResponse(
                    source,
                    policy.getId(),
                    policy.getModelVersionId(),
                    policy.getMode(),
                    deviceType,
                    policy.getCanaryPercent(),
                    null,
                    policy.isFallbackEnabled(),
                    "POLICY_DISABLED");
        }
        return new MlResolvedDeploymentPolicyResponse(
                source,
                policy.getId(),
                policy.getModelVersionId(),
                policy.getMode(),
                deviceType,
                policy.getCanaryPercent(),
                null,
                policy.isFallbackEnabled(),
                null);
    }

    public List<MlDeploymentPolicyDto> listPolicies(UUID accountId) {
        return listPolicies(accountId, "default");
    }

    public List<MlDeploymentPolicyDto> listPolicies(UUID accountId, String statusFilter) {
        String filter = normalizedFilter(statusFilter);
        return policyRepository.findByAccountIdOrderByUpdatedAtDesc(accountId).stream()
                .filter(policy -> matchesPolicyFilter(policy, filter))
                .map(this::policyResponse)
                .toList();
    }

    private boolean matchesPolicyFilter(MlDeploymentPolicy policy, String filter) {
        return switch (filter) {
            case "all" -> true;
            case "active", "current" -> policy.getDeletedAt() == null && policy.getMode() != MlDeploymentMode.DISABLED;
            case "failed", "archived" -> policy.getDeletedAt() == null && policy.getMode() == MlDeploymentMode.DISABLED;
            case "deleted" -> policy.getDeletedAt() != null;
            default -> policy.getDeletedAt() == null;
        };
    }

    private String normalizedFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return "default";
        }
        return statusFilter.trim().toLowerCase(Locale.ROOT);
    }

    private MlDeploymentPolicy findOwnedPolicy(UUID accountId, UUID policyId) {
        MlDeploymentPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML deployment policy not found"));
        if (policy.getAccountId() != null && !accountId.equals(policy.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML deployment policy not found");
        }
        return policy;
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
        model.setArtifactManifestUri("artifact://demo/" + modelKeySuffix + "/manifest.json");
        model.setArtifactHash("sha256:demo-artifact-" + modelKeySuffix);
        model.setArtifactSizeBytes((long) writeJson(evaluationMetrics).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        model.setCompatibleDeviceTypesJson(writeJson(compatibleDeviceTypes));
        model.setSupportedMetricsJson(writeJson(supportedMetrics));
        model.setEvaluationMetricsJson(writeJson(evaluationMetrics));
        model.setCreatedAt(createdAt);
        return model;
    }

    private Optional<MlDeploymentPolicy> findExistingPolicy(UUID accountId, MlDeploymentPolicyRequest request) {
        return switch (request.scope()) {
            case DEVICE -> policyRepository.findByScopeAndDeviceIdAndDeletedAtIsNull(MlDeploymentScope.DEVICE, request.deviceId());
            case DEVICE_TYPE -> policyRepository.findByAccountIdAndScopeAndDeviceTypeAndDeletedAtIsNull(accountId, MlDeploymentScope.DEVICE_TYPE, request.deviceType());
            case ACCOUNT -> policyRepository.findByAccountIdAndScopeAndDeletedAtIsNull(accountId, MlDeploymentScope.ACCOUNT);
            case SYSTEM -> policyRepository.findByAccountIdAndScopeAndDeletedAtIsNull(null, MlDeploymentScope.SYSTEM);
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

    private String requiredReason(MlLifecycleActionRequest request) {
        String value = request == null ? null : request.reason();
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lifecycle reason is required");
        }
        return value.trim();
    }

    private void stampLifecycle(MlModelVersion model, UUID actorId, String reason) {
        model.setLifecycleActorId(actorId);
        model.setLifecycleAt(Instant.now());
        model.setLifecycleReason(reason);
    }

    private Map<String, Object> dependency(String type, UUID id, Map<String, ?> details) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("id", id == null ? "" : id.toString());
        value.put("details", details);
        return value;
    }

    private String normalizedAction(String action) {
        if (action == null || action.isBlank()) {
            return "delete";
        }
        return action.trim().toLowerCase(Locale.ROOT);
    }

    private void verifyArtifactMetadata(MlModelVersion model) {
        if (model.getArtifactUri() == null || model.getArtifactUri().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model artifact URI is required before approval");
        }
        if (model.getArtifactManifestUri() == null || model.getArtifactManifestUri().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model artifact manifest URI is required before approval");
        }
        if (model.getArtifactHash() == null || !model.getArtifactHash().startsWith("sha256:")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model artifact hash must be a sha256 value before approval");
        }
        if (model.getArtifactSizeBytes() == null || model.getArtifactSizeBytes() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model artifact size must be recorded before approval");
        }
    }

    private void verifyFailureApprovalGates(MlModelVersion model) {
        if (model.getFamily() != MlModelFamily.FAILURE_PREDICTION) {
            return;
        }
        Map<String, Object> metrics = readJsonObject(model.getEvaluationMetricsJson());
        Object approval = metrics.get("approvalGates");
        if (!(approval instanceof Map<?, ?> gates)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Failure model approval gates are missing; retrain or repair the model with auditable gate metadata");
        }
        Object profileId = gates.get("profileId");
        Object profileVersion = gates.get("profileVersion");
        Object thresholdSource = gates.get("thresholdSource");
        if (isBlank(profileId) || isBlank(profileVersion) || isBlank(thresholdSource)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Failure model approval gate profile metadata is missing; retrain or repair the model with auditable gate metadata");
        }
        Object criteria = gates.get("criteria");
        if (!(criteria instanceof List<?> items) || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failure model approval gates are empty");
        }
        List<String> failed = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> gate)) {
                continue;
            }
            boolean passed = Boolean.TRUE.equals(gate.get("passed"));
            if (!passed) {
                Object rawName = gate.get("name");
                Object rawReason = gate.get("reason");
                String name = rawName == null ? "approval_gate" : String.valueOf(rawName);
                String reason = rawReason == null ? "Approval gate failed" : String.valueOf(rawReason);
                failed.add(name + ": " + reason);
            }
        }
        if (!failed.isEmpty() || !Boolean.TRUE.equals(gates.get("promotionEligible"))) {
            String source = String.valueOf(thresholdSource);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Failure model approval blocked by " + source + " gates: " + String.join("; ", failed));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not parse ML model evaluation metrics");
        }
    }

    private boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
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
                model.getArtifactManifestUri(),
                model.getArtifactHash(),
                model.getArtifactSizeBytes(),
                model.getCompatibleDeviceTypesJson(),
                model.getSupportedMetricsJson(),
                model.getEvaluationMetricsJson(),
                modelOrigin(model),
                isDemoModel(model),
                isSystemHeuristic(model),
                model.getApprovedBy(),
                model.getApprovedAt(),
                model.getCreatedAt());
    }

    private String modelOrigin(MlModelVersion model) {
        if (isDemoModel(model)) {
            return "DEMO";
        }
        if (isSystemHeuristic(model)) {
            return "SYSTEM_HEURISTIC";
        }
        return "TRAINED";
    }

    private boolean isDemoModel(MlModelVersion model) {
        return startsWith(model.getModelKey(), "demo-") || startsWith(model.getArtifactUri(), "artifact://demo/");
    }

    private boolean isSystemHeuristic(MlModelVersion model) {
        return model.getAccountId() == null
                || startsWith(model.getArtifactUri(), "artifact://system/")
                || contains(model.getAlgorithm(), "heuristic");
    }

    private boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    private boolean contains(String value, String fragment) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(fragment);
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
