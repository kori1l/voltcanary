package com.voltcanary.simulator.repository;

import com.voltcanary.simulator.domain.SimulationConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationConfigRepository extends JpaRepository<SimulationConfig, UUID> {
    Optional<SimulationConfig> findByDeviceId(UUID deviceId);
}
