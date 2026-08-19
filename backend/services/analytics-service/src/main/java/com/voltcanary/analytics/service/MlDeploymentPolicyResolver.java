package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentPolicy;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import com.voltcanary.analytics.dto.MlResolvedDeploymentPolicyResponse;
import com.voltcanary.analytics.repository.MlDeploymentPolicyRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MlDeploymentPolicyResolver {

    private final MlDeploymentPolicyRepository policyRepository;

    public MlDeploymentPolicyResolver(MlDeploymentPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
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
            return new MlResolvedDeploymentPolicyResponse(
                    null,
                    null,
                    null,
                    MlDeploymentMode.DISABLED,
                    deviceType,
                    null,
                    null,
                    true,
                    "BASELINE_HEURISTIC_FALLBACK");
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
}
