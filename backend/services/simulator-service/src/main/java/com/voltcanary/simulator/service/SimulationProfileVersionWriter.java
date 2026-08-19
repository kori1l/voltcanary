package com.voltcanary.simulator.service;

import com.voltcanary.simulator.domain.SimulationConfig;
import com.voltcanary.simulator.domain.SimulationConfigVersion;
import com.voltcanary.simulator.dto.SimulationConfigVersionResponse;
import com.voltcanary.simulator.repository.SimulationConfigVersionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SimulationProfileVersionWriter {

    private final SimulationConfigVersionRepository versionRepository;
    private final SimulationProfileMapper mapper;

    public SimulationProfileVersionWriter(
            SimulationConfigVersionRepository versionRepository,
            SimulationProfileMapper mapper) {
        this.versionRepository = versionRepository;
        this.mapper = mapper;
    }

    public void save(SimulationConfig config, UUID userId) {
        SimulationConfigVersion version = new SimulationConfigVersion();
        version.setId(UUID.randomUUID());
        version.setConfigId(config.getId());
        version.setVersion(config.getYamlVersion());
        version.setYamlConfig(config.getYamlConfig());
        version.setCreatedAt(Instant.now());
        version.setCreatedBy(userId);
        versionRepository.save(version);
    }

    public List<SimulationConfigVersionResponse> versions(UUID configId) {
        return versionRepository.findByConfigIdOrderByVersionDesc(configId).stream()
                .map(mapper::toVersionResponse)
                .toList();
    }
}
