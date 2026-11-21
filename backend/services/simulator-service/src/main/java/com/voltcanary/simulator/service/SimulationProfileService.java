package com.voltcanary.simulator.service;

import com.voltcanary.simulator.domain.SimulationConfig;
import com.voltcanary.simulator.domain.SimulationConfigStatus;
import com.voltcanary.simulator.domain.SimulationProfile;
import com.voltcanary.simulator.dto.SaveSimulationConfigRequest;
import com.voltcanary.simulator.dto.SimulationConfigResponse;
import com.voltcanary.simulator.dto.SimulationConfigVersionResponse;
import com.voltcanary.simulator.dto.SimulationPresetResponse;
import com.voltcanary.simulator.dto.ValidateConfigResponse;
import com.voltcanary.simulator.repository.SimulationConfigRepository;
import com.voltcanary.simulator.yaml.SimulationTemplates;
import com.voltcanary.simulator.yaml.SimulationYamlValidator;
import com.voltcanary.simulator.yaml.ValidationError;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SimulationProfileService {

    private final SimulationConfigRepository configRepository;
    private final SimulationProfileVersionWriter versionWriter;
    private final SimulationProfileMapper mapper;
    private final SimulationYamlValidator validator;
    private final SimulationTemplates templates;
    private final VehicleSimulationTarget target;
    private final com.voltcanary.simulator.yaml.SimulationYamlParser parser;

    @Transactional(readOnly = true)
    public SimulationConfigResponse get(UUID deviceId) {
        return configRepository.findByDeviceId(deviceId).map(mapper::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation config not found"));
    }

    @Transactional
    public SimulationConfigResponse save(UUID userId, UUID deviceId, SaveSimulationConfigRequest request) {
        List<ValidationError> errors = validator.validate(request.yamlConfig());
        if (errors.isEmpty()) {
            var model = parser.parse(request.yamlConfig());
            if (!model.profile().equals(request.profile().name()) || !model.protocol().equals(request.protocol().name())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile/protocol must match YAML");
            }
            target.require(userId, deviceId, request.dataSourceId(), model);
        }
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
        if (!request.dataSourceId().equals(config.getDataSourceId())) {
            config.setSourceToken(null);
        }
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
        versionWriter.save(saved, userId);
        return mapper.toResponse(saved);
    }

    public ValidateConfigResponse validate(String yaml) {
        List<ValidationError> errors = validator.validate(yaml);
        return new ValidateConfigResponse(errors.isEmpty(), errors);
    }

    public String template(SimulationProfile profile, String sourceId) {
        return templates.template(profile, sourceId);
    }

    public String template(SimulationProfile profile, String sourceId, String preset) {
        return templates.template(profile, sourceId, preset);
    }

    public List<SimulationPresetResponse> presets() {
        return templates.presets();
    }

    @Transactional(readOnly = true)
    public List<SimulationConfigVersionResponse> versions(UUID deviceId) {
        SimulationConfig config = configRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation config not found"));
        return versionWriter.versions(config.getId());
    }
}
