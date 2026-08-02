package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private final MlModelRegistryService service = new MlModelRegistryService(
            modelRepository,
            policyRepository,
            auditRepository,
            new ObjectMapper());

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
    void upsertsActiveDeploymentPolicyAndMarksModelActive() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        MlModelVersion model = model(accountId, MlModelStatus.APPROVED);
        when(modelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(policyRepository.findByAccountIdAndScope(accountId, MlDeploymentScope.ACCOUNT)).thenReturn(Optional.empty());

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
        when(policyRepository.findByScopeAndDeviceId(MlDeploymentScope.DEVICE, deviceId)).thenReturn(Optional.of(devicePolicy));
        MlResolvedDeploymentPolicyResponse device = service.resolvePolicy(accountId, deviceId, "washer");
        assertThat(device.policySource()).isEqualTo(MlDeploymentScope.DEVICE);
        assertThat(device.modelVersionId()).isEqualTo(deviceModelId);

        when(policyRepository.findByScopeAndDeviceId(MlDeploymentScope.DEVICE, deviceId)).thenReturn(Optional.empty());
        when(policyRepository.findByAccountIdAndScopeAndDeviceType(accountId, MlDeploymentScope.DEVICE_TYPE, "washer"))
                .thenReturn(Optional.of(policy(accountId, MlDeploymentScope.DEVICE_TYPE, null, "washer", typeModelId, MlDeploymentMode.CANARY)));
        MlResolvedDeploymentPolicyResponse type = service.resolvePolicy(accountId, deviceId, "washer");
        assertThat(type.policySource()).isEqualTo(MlDeploymentScope.DEVICE_TYPE);
        assertThat(type.modelVersionId()).isEqualTo(typeModelId);

        when(policyRepository.findByAccountIdAndScopeAndDeviceType(accountId, MlDeploymentScope.DEVICE_TYPE, "washer")).thenReturn(Optional.empty());
        when(policyRepository.findByAccountIdAndScope(accountId, MlDeploymentScope.ACCOUNT))
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
        when(policyRepository.findByAccountIdAndScope(accountId, MlDeploymentScope.ACCOUNT)).thenReturn(Optional.of(policy));

        MlDeploymentPolicyDto response = service.rollbackPolicy(accountId, actorId, MlDeploymentScope.ACCOUNT, null, null, "rollback");

        assertThat(response.mode()).isEqualTo(MlDeploymentMode.DISABLED);
        assertThat(response.modelVersionId()).isNull();
        verify(auditRepository).save(org.mockito.ArgumentMatchers.argThat(event ->
                "POLICY_ROLLED_BACK".equals(event.getAction()) && modelId.equals(event.getModelVersionId())));
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
