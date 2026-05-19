package com.voltcanary.simulator.dto;

import com.voltcanary.simulator.domain.SimulationProtocol;
import com.voltcanary.simulator.domain.SimulationRunStatus;
import java.time.Instant;
import java.util.UUID;

public record SimulationRunResponse(
        UUID runId,
        SimulationRunStatus status,
        String profile,
        SimulationProtocol protocol,
        long generatedPoints,
        Instant startedAt,
        Instant stoppedAt,
        String lastError) {
}
