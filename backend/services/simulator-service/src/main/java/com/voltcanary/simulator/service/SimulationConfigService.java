package com.voltcanary.simulator.service;

import com.voltcanary.simulator.domain.SimulationConfig;
import com.voltcanary.simulator.domain.SimulationConfigStatus;
import com.voltcanary.simulator.domain.SimulationConfigVersion;
import com.voltcanary.simulator.domain.SimulationProfile;
import com.voltcanary.simulator.dto.SaveSimulationConfigRequest;
import com.voltcanary.simulator.dto.SimulationConfigResponse;
import com.voltcanary.simulator.dto.SimulationConfigVersionResponse;
import com.voltcanary.simulator.dto.ValidateConfigResponse;
import com.voltcanary.simulator.repository.SimulationConfigRepository;
import com.voltcanary.simulator.repository.SimulationConfigVersionRepository;
import com.voltcanary.simulator.yaml.SimulationTemplates;
import com.voltcanary.simulator.yaml.SimulationYamlValidator;
import com.voltcanary.simulator.yaml.ValidationError;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class SimulationConfigService {

    private final SimulationConfigRepository configRepository;
    private final SimulationConfigVersionRepository versionRepository;
    private final SimulationYamlValidator validator;
    private final SimulationTemplates templates;

    public SimulationConfigService(
            SimulationConfigRepository configRepository,
            SimulationConfigVersionRepository versionRepository,
            SimulationYamlValidator validator,
            SimulationTemplates templates) {
        this.configRepository = configRepository;
        this.versionRepository = versionRepository;
        this.validator = validator;
        this.templates = templates;
    }

    @Transactional(readOnly = true)
    public SimulationConfigResponse get(UUID deviceId) {
        return configRepository.findByDeviceId(deviceId).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation config not found"));
    }

    @Transactional
    public SimulationConfigResponse save(UUID userId, UUID deviceId, SaveSimulationConfigRequest request) {
        List<ValidationError> errors = validator.validate(request.yamlConfig());
        Instant now = Instant.now();
        SimulationConfig config = configRepository.findByDeviceId(deviceId).orElseGet(() -> {
            SimulationConfig created = new SimulationConfig();
            created.setId(UUID.randomUUID());
            created.setDeviceId(deviceId);
            created.setAccountId(userId);
            created.setCreatedAt(now);
            created.setCreatedBy(userId);
            created.setYamlVersion(0);
            return created;
        });
        config.setDataSourceId(request.dataSourceId());
        config.setName(request.name());
        config.setProfile(request.profile());
        config.setProtocol(request.protocol());
        config.setYamlConfig(request.yamlConfig());
        config.setYamlVersion(config.getYamlVersion() + 1);
        config.setStatus(errors.isEmpty() ? SimulationConfigStatus.ACTIVE : SimulationConfigStatus.INVALID);
        if (request.sourceToken() != null && !request.sourceToken().isBlank()) {
            config.setSourceToken(request.sourceToken());
        }
        config.setUpdatedAt(now);
        config.setUpdatedBy(userId);
        SimulationConfig saved = configRepository.save(config);
        saveVersion(saved, userId);
        return toResponse(saved);
    }

    public ValidateConfigResponse validate(String yaml) {
        List<ValidationError> errors = validator.validate(yaml);
        return new ValidateConfigResponse(errors.isEmpty(), errors);
    }

    public String template(SimulationProfile profile, String sourceId) {
        return templates.template(profile, sourceId);
    }

    @Transactional(readOnly = true)
    public List<SimulationConfigVersionResponse> versions(UUID deviceId) {
        SimulationConfig config = configRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation config not found"));
        return versionRepository.findByConfigIdOrderByVersionDesc(config.getId()).stream()
                .map(version -> new SimulationConfigVersionResponse(
                        version.getId(),
                        version.getVersion(),
                        version.getYamlConfig(),
                        version.getChangeNote(),
                        version.getCreatedAt(),
                        version.getCreatedBy()))
                .toList();
    }

    private void saveVersion(SimulationConfig config, UUID userId) {
        SimulationConfigVersion version = new SimulationConfigVersion();
        version.setId(UUID.randomUUID());
        version.setConfigId(config.getId());
        version.setVersion(config.getYamlVersion());
        version.setYamlConfig(config.getYamlConfig());
        version.setCreatedAt(Instant.now());
        version.setCreatedBy(userId);
        versionRepository.save(version);
    }

    private SimulationConfigResponse toResponse(SimulationConfig config) {
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
}
