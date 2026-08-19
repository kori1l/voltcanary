package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlModelStatus;
import com.voltcanary.analytics.domain.MlModelVersion;
import com.voltcanary.analytics.dto.MlLifecycleActionRequest;
import com.voltcanary.analytics.dto.MlLifecycleImpactResponse;
import com.voltcanary.analytics.dto.MlModelStatusRequest;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import com.voltcanary.analytics.repository.MlDeploymentPolicyRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.PredictionResultRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MlModelLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(MlModelLifecycleService.class);

    private final MlModelVersionRepository modelVersionRepository;
    private final MlDeploymentPolicyRepository policyRepository;
    private final PredictionResultRepository predictionResultRepository;
    private final MlModelApprovalGateVerifier approvalGateVerifier;
    private final MlDeploymentAuditWriter auditWriter;
    private final MlModelRegistryResponseMapper mapper;

    public MlModelLifecycleService(
            MlModelVersionRepository modelVersionRepository,
            MlDeploymentPolicyRepository policyRepository,
            PredictionResultRepository predictionResultRepository,
            MlModelApprovalGateVerifier approvalGateVerifier,
            MlDeploymentAuditWriter auditWriter,
            MlModelRegistryResponseMapper mapper) {
        this.modelVersionRepository = modelVersionRepository;
        this.policyRepository = policyRepository;
        this.predictionResultRepository = predictionResultRepository;
        this.approvalGateVerifier = approvalGateVerifier;
        this.auditWriter = auditWriter;
        this.mapper = mapper;
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
        auditWriter.audit(accountId, actorId, model.getId(), null, "MODEL_DELETED", Map.of("reason", reason));
        log.info("mlModelDeleted modelVersionId={} accountId={} actorId={} reason={}",
                model.getId(), accountId, actorId, reason);
        return mapper.modelResponse(model);
    }

    @Transactional
    public MlModelVersionDto approve(UUID accountId, UUID actorId, UUID modelVersionId, MlModelStatusRequest request) {
        MlModelVersion model = findOwnedModel(accountId, modelVersionId);
        if (model.getStatus() != MlModelStatus.CANDIDATE && model.getStatus() != MlModelStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only candidate or rejected models can be approved");
        }
        approvalGateVerifier.verify(model);
        model.setStatus(MlModelStatus.APPROVED);
        model.setApprovedBy(actorId);
        model.setApprovedAt(Instant.now());
        modelVersionRepository.save(model);
        auditWriter.audit(accountId, actorId, model.getId(), null, "MODEL_APPROVED", Map.of("reason", reason(request)));
        log.info("mlModelPromoted modelVersionId={} accountId={} actorId={} status={} reason={}",
                model.getId(), accountId, actorId, model.getStatus(), reason(request));
        return mapper.modelResponse(model);
    }

    @Transactional
    public MlModelVersionDto reject(UUID accountId, UUID actorId, UUID modelVersionId, MlModelStatusRequest request) {
        MlModelVersion model = findOwnedModel(accountId, modelVersionId);
        if (model.getStatus() == MlModelStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Active models must be rolled back before rejection");
        }
        model.setStatus(MlModelStatus.REJECTED);
        modelVersionRepository.save(model);
        auditWriter.audit(accountId, actorId, model.getId(), null, "MODEL_REJECTED", Map.of("reason", reason(request)));
        log.info("mlModelRejected modelVersionId={} accountId={} actorId={} reason={}",
                model.getId(), accountId, actorId, reason(request));
        return mapper.modelResponse(model);
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
        auditWriter.audit(accountId, actorId, model.getId(), null, "MODEL_ARCHIVED", Map.of("reason", reason(request)));
        log.info("mlModelArchived modelVersionId={} accountId={} actorId={} reason={}",
                model.getId(), accountId, actorId, reason(request));
        return mapper.modelResponse(model);
    }

    private MlModelVersion findOwnedModel(UUID accountId, UUID modelVersionId) {
        MlModelVersion model = modelVersionRepository.findById(modelVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ML model version not found"));
        if (!accountId.equals(model.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ML model version not found");
        }
        return model;
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
}
