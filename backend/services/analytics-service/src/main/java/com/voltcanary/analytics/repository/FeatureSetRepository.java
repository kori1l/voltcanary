package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.feature.FeatureSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureSetRepository extends JpaRepository<FeatureSet, UUID> {

    List<FeatureSet> findTop100ByDeviceIdOrderByWindowEndDesc(UUID deviceId);
}
