package com.voltcanary.simulator.repository;

import com.voltcanary.simulator.domain.SimulationRun;
import com.voltcanary.simulator.domain.SimulationRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SimulationRunRepository extends JpaRepository<SimulationRun, UUID> {
    List<SimulationRun> findByDeviceIdOrderByStartedAtDesc(UUID deviceId);
    Optional<SimulationRun> findFirstByDeviceIdAndStatusOrderByStartedAtDesc(UUID deviceId, SimulationRunStatus status);
    Optional<SimulationRun> findFirstByDeviceIdAndActiveKeyOrderByStartedAtDesc(UUID deviceId, String activeKey);
    List<SimulationRun> findByStatusIn(List<SimulationRunStatus> statuses);

    @Modifying
    @Transactional
    @Query("""
            update SimulationRun run
            set run.leaseUntil = :leaseUntil
            where run.id = :runId
              and run.leaseOwner = :leaseOwner
              and run.status = com.voltcanary.simulator.domain.SimulationRunStatus.RUNNING
            """)
    int extendLease(
            @Param("runId") UUID runId,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") Instant leaseUntil);
}
