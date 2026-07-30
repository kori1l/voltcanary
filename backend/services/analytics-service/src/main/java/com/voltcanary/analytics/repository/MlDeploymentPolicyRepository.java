package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlDeploymentPolicy;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlDeploymentPolicyRepository extends JpaRepository<MlDeploymentPolicy, UUID> {
    List<MlDeploymentPolicy> findByAccountIdOrderByUpdatedAtDesc(UUID accountId);
    Optional<MlDeploymentPolicy> findByScopeAndDeviceId(MlDeploymentScope scope, UUID deviceId);
    Optional<MlDeploymentPolicy> findByAccountIdAndScopeAndDeviceType(UUID accountId, MlDeploymentScope scope, String deviceType);
    Optional<MlDeploymentPolicy> findByAccountIdAndScope(UUID accountId, MlDeploymentScope scope);
}
