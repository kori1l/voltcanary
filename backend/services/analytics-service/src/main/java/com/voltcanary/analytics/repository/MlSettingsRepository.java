package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlSettingsRepository extends JpaRepository<MlSettings, UUID> {
    Optional<MlSettings> findByAccountIdAndDeviceIdIsNull(UUID accountId);
    Optional<MlSettings> findByDeviceId(UUID deviceId);
    void deleteByDeviceId(UUID deviceId);
}
