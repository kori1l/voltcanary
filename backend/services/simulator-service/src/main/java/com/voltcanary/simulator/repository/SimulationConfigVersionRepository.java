package com.voltcanary.simulator.repository;

import com.voltcanary.simulator.domain.SimulationConfigVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationConfigVersionRepository extends JpaRepository<SimulationConfigVersion, UUID> {
    List<SimulationConfigVersion> findByConfigIdOrderByVersionDesc(UUID configId);
}
