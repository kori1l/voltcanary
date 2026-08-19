package com.voltcanary.simulator.service;

import com.voltcanary.simulator.domain.SimulationConfig;
import com.voltcanary.simulator.domain.SimulationConfigVersion;
import com.voltcanary.simulator.dto.SimulationConfigResponse;
import com.voltcanary.simulator.dto.SimulationConfigVersionResponse;
import org.springframework.stereotype.Component;

@Component
public class SimulationProfileMapper {

    public SimulationConfigResponse toResponse(SimulationConfig config) {
        return new SimulationConfigResponse(
                config.getId(),
                config.getDeviceId(),
                config.getDataSourceId(),
                config.getName(),
                config.getProfile(),
                config.getProtocol(),
                config.getYamlConfig(),
                config.getYamlVersion(),
                config.getStatus(),
                config.getUpdatedAt(),
                config.getSourceToken() != null && !config.getSourceToken().isBlank());
    }

    public SimulationConfigVersionResponse toVersionResponse(SimulationConfigVersion version) {
        return new SimulationConfigVersionResponse(
                version.getId(),
                version.getVersion(),
                version.getYamlConfig(),
                version.getChangeNote(),
                version.getCreatedAt(),
                version.getCreatedBy());
    }
}
