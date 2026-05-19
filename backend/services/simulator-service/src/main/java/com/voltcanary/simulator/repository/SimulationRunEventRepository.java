package com.voltcanary.simulator.repository;

import com.voltcanary.simulator.domain.SimulationRunEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationRunEventRepository extends JpaRepository<SimulationRunEvent, UUID> {
    List<SimulationRunEvent> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
