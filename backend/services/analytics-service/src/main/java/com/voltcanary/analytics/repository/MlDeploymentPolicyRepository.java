package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlDeploymentPolicy;
import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlDeploymentPolicyRepository extends JpaRepository<MlDeploymentPolicy, UUID> {
    List<MlDeploymentPolicy> findByAccountIdOrderByUpdatedAtDesc(UUID accountId);
    List<MlDeploymentPolicy> findByModelVersionIdAndDeletedAtIsNull(UUID modelVersionId);
    long countByModelVersionIdAndModeNotAndDeletedAtIsNull(UUID modelVersionId, MlDeploymentMode mode);
    Optional<MlDeploymentPolicy> findByScopeAndDeviceId(MlDeploymentScope scope, UUID deviceId);
    Optional<MlDeploymentPolicy> findByScopeAndDeviceIdAndDeletedAtIsNull(MlDeploymentScope scope, UUID deviceId);
    Optional<MlDeploymentPolicy> findByAccountIdAndScopeAndDeviceType(UUID accountId, MlDeploymentScope scope, String deviceType);
    Optional<MlDeploymentPolicy> findByAccountIdAndScopeAndDeviceTypeAndDeletedAtIsNull(UUID accountId, MlDeploymentScope scope, String deviceType);
    Optional<MlDeploymentPolicy> findByAccountIdAndScope(UUID accountId, MlDeploymentScope scope);
    Optional<MlDeploymentPolicy> findByAccountIdAndScopeAndDeletedAtIsNull(UUID accountId, MlDeploymentScope scope);
}
