package com.voltcanary.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.config.MlRegistryProperties;
import com.voltcanary.analytics.domain.MlDeploymentScope;
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
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MlModelRegistryService {

    private final MlModelQueryService modelQueryService;
    private final MlModelLifecycleService modelLifecycleService;
    private final MlDeploymentPolicyService deploymentPolicyService;
    private final MlDeploymentPolicyResolver deploymentPolicyResolver;

    @Autowired
    public MlModelRegistryService(
            MlModelQueryService modelQueryService,
            MlModelLifecycleService modelLifecycleService,
            MlDeploymentPolicyService deploymentPolicyService,
            MlDeploymentPolicyResolver deploymentPolicyResolver) {
        this.modelQueryService = modelQueryService;
        this.modelLifecycleService = modelLifecycleService;
        this.deploymentPolicyService = deploymentPolicyService;
        this.deploymentPolicyResolver = deploymentPolicyResolver;
    }

    public MlModelRegistryService(
            MlModelVersionRepository modelVersionRepository,
            MlDeploymentPolicyRepository policyRepository,
            MlDeploymentAuditEventRepository auditRepository,
            PredictionResultRepository predictionResultRepository,
            ObjectMapper objectMapper,
            MlRegistryProperties registryProperties) {
        MlDeploymentAuditWriter auditWriter = new MlDeploymentAuditWriter(auditRepository, objectMapper);
        MlModelRegistryResponseMapper mapper = new MlModelRegistryResponseMapper();
        MlDemoModelSeeder demoModelSeeder = new MlDemoModelSeeder(modelVersionRepository, auditWriter);
        MlModelApprovalGateVerifier approvalGateVerifier = new MlModelApprovalGateVerifier(objectMapper);
        this.modelQueryService = new MlModelQueryService(
                modelVersionRepository,
                registryProperties,
                demoModelSeeder,
                mapper);
        this.modelLifecycleService = new MlModelLifecycleService(
                modelVersionRepository,
                policyRepository,
                predictionResultRepository,
                approvalGateVerifier,
                auditWriter,
                mapper);
        this.deploymentPolicyService = new MlDeploymentPolicyService(
                policyRepository,
                modelVersionRepository,
                auditWriter,
                mapper);
        this.deploymentPolicyResolver = new MlDeploymentPolicyResolver(policyRepository);
    }

    public List<MlModelVersionDto> listModels(UUID accountId) {
        return modelQueryService.listModels(accountId);
    }

    public List<MlModelVersionDto> listModels(UUID accountId, String statusFilter) {
        return modelQueryService.listModels(accountId, statusFilter);
    }

    public MlModelVersionDto getModel(UUID accountId, UUID modelVersionId) {
        return modelQueryService.getModel(accountId, modelVersionId);
    }

    public MlLifecycleImpactResponse modelImpact(UUID accountId, UUID modelVersionId, String action) {
        return modelLifecycleService.modelImpact(accountId, modelVersionId, action);
    }

    public MlModelVersionDto deleteModel(UUID accountId, UUID actorId, UUID modelVersionId, MlLifecycleActionRequest request) {
        return modelLifecycleService.deleteModel(accountId, actorId, modelVersionId, request);
    }

    public MlModelVersionDto approve(UUID accountId, UUID actorId, UUID modelVersionId, MlModelStatusRequest request) {
        return modelLifecycleService.approve(accountId, actorId, modelVersionId, request);
    }

    public MlModelVersionDto reject(UUID accountId, UUID actorId, UUID modelVersionId, MlModelStatusRequest request) {
        return modelLifecycleService.reject(accountId, actorId, modelVersionId, request);
    }

    public MlModelVersionDto archive(UUID accountId, UUID actorId, UUID modelVersionId, MlModelStatusRequest request) {
        return modelLifecycleService.archive(accountId, actorId, modelVersionId, request);
    }

    public MlDeploymentPolicyDto disablePolicy(UUID accountId, UUID actorId, UUID policyId, MlLifecycleActionRequest request) {
        return deploymentPolicyService.disablePolicy(accountId, actorId, policyId, request);
    }

    public MlDeploymentPolicyDto deletePolicy(UUID accountId, UUID actorId, UUID policyId, MlLifecycleActionRequest request) {
        return deploymentPolicyService.deletePolicy(accountId, actorId, policyId, request);
    }

    public MlLifecycleImpactResponse policyImpact(UUID accountId, UUID policyId, String action) {
        return deploymentPolicyService.policyImpact(accountId, policyId, action);
    }

    public MlDeploymentPolicyDto upsertPolicy(UUID accountId, UUID actorId, MlDeploymentPolicyRequest request) {
        return deploymentPolicyService.upsertPolicy(accountId, actorId, request);
    }

    public MlDeploymentPolicyDto rollbackPolicy(
            UUID accountId,
            UUID actorId,
            MlDeploymentScope scope,
            UUID deviceId,
            String deviceType,
            String reason) {
        return deploymentPolicyService.rollbackPolicy(accountId, actorId, scope, deviceId, deviceType, reason);
    }

    public MlResolvedDeploymentPolicyResponse resolvePolicy(UUID accountId, UUID deviceId, String deviceType) {
        return deploymentPolicyResolver.resolvePolicy(accountId, deviceId, deviceType);
    }

    public List<MlDeploymentPolicyDto> listPolicies(UUID accountId) {
        return deploymentPolicyService.listPolicies(accountId);
    }

    public List<MlDeploymentPolicyDto> listPolicies(UUID accountId, String statusFilter) {
        return deploymentPolicyService.listPolicies(accountId, statusFilter);
    }
}
