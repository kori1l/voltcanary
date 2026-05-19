package com.voltcanary.simulator.dto;

import com.voltcanary.simulator.domain.SimulationConfigStatus;
import com.voltcanary.simulator.domain.SimulationProfile;
import com.voltcanary.simulator.domain.SimulationProtocol;
import java.time.Instant;
import java.util.UUID;

public record SimulationConfigResponse(
        UUID id,
        UUID deviceId,
        UUID dataSourceId,
        String name,
        SimulationProfile profile,
        SimulationProtocol protocol,
        String yamlConfig,
        int yamlVersion,
        SimulationConfigStatus status,
        Instant updatedAt,
        boolean hasSourceToken) {
}
