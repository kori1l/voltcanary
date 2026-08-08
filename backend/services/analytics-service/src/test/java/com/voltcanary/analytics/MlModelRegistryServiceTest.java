package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.voltcanary.analytics.dto.MlModelStatusRequest;
import com.voltcanary.analytics.dto.MlModelVersionDto;
import com.voltcanary.analytics.dto.MlResolvedDeploymentPolicyResponse;
import com.voltcanary.analytics.repository.MlDeploymentAuditEventRepository;
import com.voltcanary.analytics.repository.MlDeploymentPolicyRepository;
import com.voltcanary.analytics.repository.MlModelVersionRepository;
import com.voltcanary.analytics.repository.PredictionResultRepository;
import com.voltcanary.analytics.service.MlLifecycleConflictException;
import com.voltcanary.analytics.service.MlModelRegistryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class MlModelRegistryServiceTest {

    private final MlModelVersionRepository modelRepository = mock(MlModelVersionRepository.class);
    private final MlDeploymentPolicyRepository policyRepository = mock(MlDeploymentPolicyRepository.class);
    private final MlDeploymentAuditEventRepository auditRepository = mock(MlDeploymentAuditEventRepository.class);
    private final PredictionResultRepository predictionResultRepository = mock(PredictionResultRepository.class);
    private final MlRegistryProperties registryProperties = new MlRegistryProperties();
    private final MlModelRegistryService service = new MlModelRegistryService(
            modelRepository,
            policyRepository,
            auditRepository,
            predictionResultRepository,
            new ObjectMapper(),
            registryProperties);

    @Test
    void listModelsDoesNotSeedDemoModelsUnlessEnabled() {
        UUID accountId = UUID.randomUUID();
        when(modelRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of());

        assertThat(service.listModels(accountId)).isEmpty();

        verify(modelRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void listModelsSeedsDemoModelsWhenExplicitlyEnabled() {
        UUID accountId = UUID.randomUUID();
        registryProperties.setDemoSeedingEnabled(true);
        when(modelRepository.findByAccountIdOrderByCreatedAtDesc(accountId))
                .thenReturn(List.of())
                .thenReturn(List.of(model(accountId, MlModelStatus.APPROVED)));

        service.listModels(accountId);

        verify(modelRepository, org.mockito.Mockito.times(3)).save(org.mockito.ArgumentMatchers.any(MlModelVersion.class));
    }

    @Test
    void modelResponseDistinguishesTrainedDemoAndSystemHeuristicModels() {
        UUID accountId = UUID.randomUUID();
        MlModelVersion trained = model(accountId, MlModelStatus.APPROVED);
        MlModelVersion demo = model(accountId, MlModelStatus.APPROVED);
        demo.setModelKey("demo-" + accountId + "-anomaly");
        demo.setArtifactUri("artifact://demo/anomaly/model.json");
        MlModelVersion system = model(null, MlModelStatus.APPROVED);
        system.setModelKey("system-baseline-heuristic");
        system.setAlgorithm("baseline-heuristic-v1");
        system.setArtifactUri("artifact://system/baseline/model.json");
        when(modelRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of(trained, demo, system));

        List<MlModelVersionDto> models = service.listModels(accountId);

        assertThat(models).extracting(MlModelVersionDto::modelOrigin)
                .containsExactly("TRAINED", "DEMO", "SYSTEM_HEURISTIC");
        assertThat(models.get(0).demoModel()).isFalse();
        assertThat(models.get(1).demoModel()).isTrue();
        assertThat(models.get(2).systemHeuristic()).isTrue();
    }

    @Test
    void approvesRejectsAndArchivesModelsWithAuditEvents() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        MlModelVersion model = model(accountId, MlModelStatus.CANDIDATE);
        when(modelRepository.findById(model.getId())).thenReturn(Optional.of(model));

        MlModelVersionDto approved = service.approve(accountId, actorId, model.getId(), new MlModelStatusRequest("reviewed"));
        assertThat(approved.status()).isEqualTo(MlModelStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo(actorId);

        service.reject(accountId, actorId, model.getId(), new MlModelStatusRequest("bad metrics"));
        assertThat(model.getStatus()).isEqualTo(MlModelStatus.REJECTED);

        service.archive(accountId, actorId, model.getId(), new MlModelStatusRequest("cleanup"));
        assertThat(model.getStatus()).isEqualTo(MlModelStatus.ARCHIVED);

        ArgumentCaptor<MlDeploymentAuditEvent> audit = ArgumentCaptor.forClass(MlDeploymentAuditEvent.class);
        verify(auditRepository, org.mockito.Mockito.times(3)).save(audit.capture());
        assertThat(audit.getAllValues()).extracting(MlDeploymentAuditEvent::getAction)
                .containsExactly("MODEL_APPROVED", "MODEL_REJECTED", "MODEL_ARCHIVED");
    }

    @Test
    void blocksDeletingPolicyReferencedModel() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        MlModelVersion model = model(accountId, MlModelStatus.CANDIDATE);
        MlDeploymentPolicy policy = policy(accountId, MlDeploymentScope.ACCOUNT, null, null, model.getId(), MlDeploymentMode.ACTIVE);
        when(modelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(policyRepository.findByModelVersionIdAndDeletedAtIsNull(model.getId())).thenReturn(List.of(policy));

        assertThatThrownBy(() -> service.deleteModel(accountId, actorId, model.getId(), new MlLifecycleActionRequest("cleanup", false)))
                .isInstanceOf(MlLifecycleConflictException.class)
                .hasMessageContaining("referenced by deployment policies");
    }

    @Test
    void modelImpactShowsPolicyReferencesBeforeDeletion() {
        UUID accountId = UUID.randomUUID();
        MlModelVersion model = model(accountId, MlModelStatus.CANDIDATE);
        MlDeploymentPolicy policy = policy(accountId, MlDeploymentScope.ACCOUNT, null, null, model.getId(), MlDeploymentMode.ACTIVE);
        when(modelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(policyRepository.findByModelVersionIdAndDeletedAtIsNull(model.getId())).thenReturn(List.of(policy));

        var impact = service.modelImpact(accountId, model.getId(), "delete");

        assertThat(impact.blocked()).isTrue();
        assertThat(impact.dependencies()).anySatisfy(dependency -> {
            assertThat(dependency).containsEntry("type", "deploymentPolicy");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> details = (java.util.Map<String, Object>) dependency.get("details");
            assertThat(details)
                    .containsEntry("scope", "ACCOUNT")
                    .containsEntry("mode", "ACTIVE");
        });
    }

    @Test
    void upsertsActiveDeploymentPolicyAndMarksModelActive() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        MlModelVersion model = model(accountId, MlModelStatus.APPROVED);
        when(modelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(policyRepository.findByAccountIdAndScopeAndDeletedAtIsNull(accountId, MlDeploymentScope.ACCOUNT)).thenReturn(Optional.empty());

        MlDeploymentPolicyDto policy = service.upsertPolicy(accountId, actorId, new MlDeploymentPolicyRequest(
                MlDeploymentScope.ACCOUNT,
                null,
                null,
                model.getId(),
                MlDeploymentMode.ACTIVE,
                null,
                true,
                "promote"));

        assertThat(policy.scope()).isEqualTo(MlDeploymentScope.ACCOUNT);
        assertThat(policy.mode()).isEqualTo(MlDeploymentMode.ACTIVE);
        assertThat(model.getStatus()).isEqualTo(MlModelStatus.ACTIVE);
        verify(auditRepository).save(org.mockito.ArgumentMatchers.argThat(event -> "POLICY_UPDATED".equals(event.getAction())));
    }

    @Test
    void approvalRequiresVerifiedArtifactMetadata() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        MlModelVersion model = model(accountId, MlModelStatus.CANDIDATE);
        model.setArtifactManifestUri("");
        when(modelRepository.findById(model.getId())).thenReturn(Optional.of(model));

        assertThatThrownBy(() -> service.approve(accountId, actorId, model.getId(), new MlModelStatusRequest("reviewed")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("artifact manifest URI");
    }


    @Test
    void resolvesDeploymentPoliciesByDeviceDeviceTypeThenAccount() {
        UUID accountId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID deviceModelId = UUID.randomUUID();
        UUID typeModelId = UUID.randomUUID();
        UUID accountModelId = UUID.randomUUID();

        MlDeploymentPolicy devicePolicy = policy(accountId, MlDeploymentScope.DEVICE, deviceId, null, deviceModelId, MlDeploymentMode.SHADOW);
        when(policyRepository.findByScopeAndDeviceIdAndDeletedAtIsNull(MlDeploymentScope.DEVICE, deviceId)).thenReturn(Optional.of(devicePolicy));
        MlResolvedDeploymentPolicyResponse device = service.resolvePolicy(accountId, deviceId, "washer");
        assertThat(device.policySource()).isEqualTo(MlDeploymentScope.DEVICE);
        assertThat(device.modelVersionId()).isEqualTo(deviceModelId);

        when(policyRepository.findByScopeAndDeviceIdAndDeletedAtIsNull(MlDeploymentScope.DEVICE, deviceId)).thenReturn(Optional.empty());
        when(policyRepository.findByAccountIdAndScopeAndDeviceTypeAndDeletedAtIsNull(accountId, MlDeploymentScope.DEVICE_TYPE, "washer"))
                .thenReturn(Optional.of(policy(accountId, MlDeploymentScope.DEVICE_TYPE, null, "washer", typeModelId, MlDeploymentMode.CANARY)));
        MlResolvedDeploymentPolicyResponse type = service.resolvePolicy(accountId, deviceId, "washer");
        assertThat(type.policySource()).isEqualTo(MlDeploymentScope.DEVICE_TYPE);
        assertThat(type.modelVersionId()).isEqualTo(typeModelId);
        assertThat(type.resolvedDeviceType()).isEqualTo("washer");

        when(policyRepository.findByAccountIdAndScopeAndDeviceTypeAndDeletedAtIsNull(accountId, MlDeploymentScope.DEVICE_TYPE, "washer")).thenReturn(Optional.empty());
        when(policyRepository.findByAccountIdAndScopeAndDeletedAtIsNull(accountId, MlDeploymentScope.ACCOUNT))
                .thenReturn(Optional.of(policy(accountId, MlDeploymentScope.ACCOUNT, null, null, accountModelId, MlDeploymentMode.ACTIVE)));
        MlResolvedDeploymentPolicyResponse account = service.resolvePolicy(accountId, deviceId, "washer");
        assertThat(account.policySource()).isEqualTo(MlDeploymentScope.ACCOUNT);
        assertThat(account.modelVersionId()).isEqualTo(accountModelId);
    }

    @Test
    void rollbackDisablesPolicyAndAuditLogsPreviousModel() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        MlDeploymentPolicy policy = policy(accountId, MlDeploymentScope.ACCOUNT, null, null, modelId, MlDeploymentMode.ACTIVE);
        when(policyRepository.findByAccountIdAndScopeAndDeletedAtIsNull(accountId, MlDeploymentScope.ACCOUNT)).thenReturn(Optional.of(policy));

        MlDeploymentPolicyDto response = service.rollbackPolicy(accountId, actorId, MlDeploymentScope.ACCOUNT, null, null, "rollback");

        assertThat(response.mode()).isEqualTo(MlDeploymentMode.DISABLED);
        assertThat(response.modelVersionId()).isNull();
        verify(auditRepository).save(org.mockito.ArgumentMatchers.argThat(event ->
                "POLICY_ROLLED_BACK".equals(event.getAction()) && modelId.equals(event.getModelVersionId())));
    }

    @Test
    void deletingDisabledDevicePolicyDoesNotBreakResolutionFallback() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID staleModelId = UUID.randomUUID();
        UUID accountModelId = UUID.randomUUID();
        MlDeploymentPolicy devicePolicy = policy(accountId, MlDeploymentScope.DEVICE, deviceId, null, staleModelId, MlDeploymentMode.DISABLED);
        MlDeploymentPolicy accountPolicy = policy(accountId, MlDeploymentScope.ACCOUNT, null, null, accountModelId, MlDeploymentMode.ACTIVE);
        when(policyRepository.findById(devicePolicy.getId())).thenReturn(Optional.of(devicePolicy));

        MlDeploymentPolicyDto deleted = service.deletePolicy(accountId, actorId, devicePolicy.getId(), new MlLifecycleActionRequest("stale cleanup", false));
        when(policyRepository.findByScopeAndDeviceIdAndDeletedAtIsNull(MlDeploymentScope.DEVICE, deviceId)).thenReturn(Optional.empty());
        when(policyRepository.findByAccountIdAndScopeAndDeletedAtIsNull(accountId, MlDeploymentScope.ACCOUNT)).thenReturn(Optional.of(accountPolicy));

        MlResolvedDeploymentPolicyResponse resolved = service.resolvePolicy(accountId, deviceId, null);

        assertThat(deleted.mode()).isEqualTo(MlDeploymentMode.DISABLED);
        assertThat(devicePolicy.getDeletedAt()).isNotNull();
        assertThat(resolved.policySource()).isEqualTo(MlDeploymentScope.ACCOUNT);
        assertThat(resolved.modelVersionId()).isEqualTo(accountModelId);
    }

    private MlModelVersion model(UUID accountId, MlModelStatus status) {
        MlModelVersion model = new MlModelVersion();
        model.setId(UUID.randomUUID());
        model.setAccountId(accountId);
        model.setModelKey("model-key");
        model.setVersion(1);
        model.setFamily(MlModelFamily.ANOMALY_DETECTION);
        model.setAlgorithm("baseline-statistical-anomaly-v1");
        model.setStatus(status);
        model.setDatasetVersionId(UUID.randomUUID());
        model.setFeatureSnapshotId(UUID.randomUUID());
        model.setFeatureSchemaHash("sha256:test");
        model.setArtifactUri("artifact://model");
        model.setArtifactManifestUri("artifact://model/manifest.json");
        model.setArtifactHash("sha256:artifact");
        model.setArtifactSizeBytes(128L);
        model.setCompatibleDeviceTypesJson("[]");
        model.setSupportedMetricsJson("[]");
        model.setEvaluationMetricsJson("{}");
        model.setCreatedAt(Instant.now());
        return model;
    }

    private MlDeploymentPolicy policy(UUID accountId, MlDeploymentScope scope, UUID deviceId, String deviceType, UUID modelId, MlDeploymentMode mode) {
        MlDeploymentPolicy policy = new MlDeploymentPolicy();
        policy.setId(UUID.randomUUID());
        policy.setAccountId(scope == MlDeploymentScope.SYSTEM ? null : accountId);
        policy.setScope(scope);
        policy.setDeviceId(deviceId);
        policy.setDeviceType(deviceType);
        policy.setModelVersionId(modelId);
        policy.setMode(mode);
        policy.setFallbackEnabled(true);
        policy.setUpdatedAt(Instant.now());
        return policy;
    }
}
