package com.voltcanary.simulator.generator;

import com.voltcanary.simulator.yaml.SimulationConfigModel;
import java.time.Instant;
import java.util.UUID;

public record SimulationContext(
        UUID runId,
        UUID deviceId,
        UUID dataSourceId,
        String sourceId,
        Instant startedAt,
        Instant currentTime,
        long tickNumber,
        SimulationConfigModel config) {
}
