package com.voltcanary.simulator.dto;

import java.time.Instant;
import java.util.UUID;

public record SimulationConfigVersionResponse(
        UUID id,
        int version,
        String yamlConfig,
        String changeNote,
        Instant createdAt,
        UUID createdBy) {
}
