package com.voltcanary.simulator.repository;

import com.voltcanary.simulator.domain.SimulationRun;
import com.voltcanary.simulator.domain.SimulationRunStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationRunRepository extends JpaRepository<SimulationRun, UUID> {
    List<SimulationRun> findByDeviceIdOrderByStartedAtDesc(UUID deviceId);
    Optional<SimulationRun> findFirstByDeviceIdAndStatusOrderByStartedAtDesc(UUID deviceId, SimulationRunStatus status);
}
