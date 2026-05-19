package com.voltcanary.simulator.dto;

import com.voltcanary.simulator.domain.SimulationProfile;
import com.voltcanary.simulator.domain.SimulationProtocol;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SaveSimulationConfigRequest(
        @NotNull UUID dataSourceId,
        @NotBlank String name,
        @NotNull SimulationProfile profile,
        @NotNull SimulationProtocol protocol,
        @NotBlank String yamlConfig,
        String sourceToken) {
}
