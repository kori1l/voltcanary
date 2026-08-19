package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentPolicy;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.dto.MlDeploymentPolicyDto;
import com.voltcanary.analytics.dto.MlDeploymentPolicyRequest;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.repository.MlDeploymentPolicyRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import java.time.Instant;
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
public class MlDeploymentPolicyService {

    private static final Logger log = LoggerFactory.getLogger(MlDeploymentPolicyService.class);

    private final MlDeploymentPolicyRepository policyRepository;
    private final MlModelVersionRepository modelVersionRepository;
    private final MlDeploymentAuditWriter auditWriter;
    private final MlModelRegistryResponseMapper mapper;

    public MlDeploymentPolicyService(
            MlDeploymentPolicyRepository policyRepository,
            MlModelVersionRepository modelVersionRepository,
            MlDeploymentAuditWriter auditWriter,
            MlModelRegistryResponseMapper mapper) {
        this.policyRepository = policyRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.auditWriter = auditWriter;
        this.mapper = mapper;
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
        auditWriter.audit(accountId, actorId, policy.getModelVersionId(), policy.getId(), "POLICY_DISABLED", Map.of("reason", reason));
        log.info("mlDeploymentPolicyDisabled policyId={} accountId={} actorId={} reason={}", policy.getId(), accountId, actorId, reason);
        return mapper.policyResponse(policy);
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
        auditWriter.audit(accountId, actorId, policy.getModelVersionId(), policy.getId(), "POLICY_DELETED", Map.of("reason", reason));
        log.info("mlDeploymentPolicyDeleted policyId={} accountId={} actorId={} reason={}", policy.getId(), accountId, actorId, reason);
        return mapper.policyResponse(policy);
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
        auditWriter.audit(accountId, actorId, request.modelVersionId(), policy.getId(), "POLICY_UPDATED", Map.of(
                "scope", request.scope().name(),
                "mode", request.mode().name(),
                "reason", reason(request.reason())));
        log.info("mlDeploymentPolicyUpdated policyId={} accountId={} actorId={} scope={} mode={} modelVersionId={} fallbackEnabled={} reason={}",
                policy.getId(), accountId, actorId, policy.getScope(), policy.getMode(), policy.getModelVersionId(),
                policy.isFallbackEnabled(), reason(request.reason()));
        return mapper.policyResponse(policy);
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
        auditWriter.audit(accountId, actorId, previousModelId, policy.getId(), "POLICY_ROLLED_BACK", Map.of("reason", reason(reason)));
        log.info("mlDeploymentPolicyRolledBack policyId={} accountId={} actorId={} previousModelVersionId={} scope={} reason={}",
                policy.getId(), accountId, actorId, previousModelId, policy.getScope(), reason(reason));
        return mapper.policyResponse(policy);
    }

    public List<MlDeploymentPolicyDto> listPolicies(UUID accountId) {
        return listPolicies(accountId, "default");
    }

    public List<MlDeploymentPolicyDto> listPolicies(UUID accountId, String statusFilter) {
        String filter = normalizedFilter(statusFilter);
        return policyRepository.findByAccountIdOrderByUpdatedAtDesc(accountId).stream()
                .filter(policy -> matchesPolicyFilter(policy, filter))
                .map(mapper::policyResponse)
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
}
