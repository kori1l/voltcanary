package com.voltcanary.simulator.controller;

import com.voltcanary.simulator.domain.SimulationProfile;
import com.voltcanary.simulator.dto.SaveSimulationConfigRequest;
import com.voltcanary.simulator.dto.SimulationConfigResponse;
import com.voltcanary.simulator.dto.SimulationConfigVersionResponse;
import com.voltcanary.simulator.dto.SimulationRunDetailResponse;
import com.voltcanary.simulator.dto.SimulationRunResponse;
import com.voltcanary.simulator.dto.ValidateConfigRequest;
import com.voltcanary.simulator.dto.ValidateConfigResponse;
import com.voltcanary.simulator.runner.SimulationRunManager;
import com.voltcanary.simulator.service.SimulationConfigService;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/simulation")
public class SimulationController {

    private final SimulationConfigService configService;
    private final SimulationRunManager runManager;

    public SimulationController(SimulationConfigService configService, SimulationRunManager runManager) {
        this.configService = configService;
        this.runManager = runManager;
    }

    @GetMapping("/devices/{deviceId}/config")
    public SimulationConfigResponse getConfig(@PathVariable("deviceId") UUID deviceId) {
        return configService.get(deviceId);
    }

    @PutMapping("/devices/{deviceId}/config")
    public SimulationConfigResponse saveConfig(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable("deviceId") UUID deviceId,
            @Valid @RequestBody SaveSimulationConfigRequest request) {
        return configService.save(userIdOrFallback(userId), deviceId, request);
    }

    @PostMapping("/devices/{deviceId}/config/validate")
    public ValidateConfigResponse validateConfig(
            @PathVariable("deviceId") UUID deviceId,
            @Valid @RequestBody ValidateConfigRequest request) {
        return configService.validate(request.yamlConfig());
    }

    @PostMapping("/devices/{deviceId}/config/reset-template")
    public Map<String, String> resetTemplate(
            @PathVariable("deviceId") UUID deviceId,
            @RequestParam(value = "profile", defaultValue = "FRIDGE") SimulationProfile profile,
            @RequestParam(value = "sourceId", defaultValue = "sim-fridge-001") String sourceId) {
        return Map.of("yamlConfig", configService.template(profile, sourceId));
    }

    @GetMapping("/devices/{deviceId}/config/versions")
    public List<SimulationConfigVersionResponse> configVersions(@PathVariable("deviceId") UUID deviceId) {
        return configService.versions(deviceId);
    }

    @GetMapping("/templates")
    public List<String> templates() {
        return Arrays.stream(SimulationProfile.values()).map(Enum::name).toList();
    }

    @GetMapping("/templates/{profile}")
    public Map<String, String> template(
            @PathVariable("profile") SimulationProfile profile,
            @RequestParam(value = "sourceId", defaultValue = "sim-fridge-001") String sourceId) {
        return Map.of("yamlConfig", configService.template(profile, sourceId));
    }

    @PostMapping("/devices/{deviceId}/start")
    public SimulationRunResponse start(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable("deviceId") UUID deviceId) {
        return runManager.start(userIdOrFallback(userId), deviceId);
    }

    @PostMapping("/devices/{deviceId}/stop")
    public SimulationRunResponse stop(@PathVariable("deviceId") UUID deviceId) {
        return runManager.stop(deviceId, "Stopped by user");
    }

    @GetMapping("/devices/{deviceId}/status")
    public SimulationRunResponse status(@PathVariable("deviceId") UUID deviceId) {
        return runManager.status(deviceId);
    }

    @GetMapping("/devices/{deviceId}/runs")
    public List<SimulationRunResponse> runs(@PathVariable("deviceId") UUID deviceId) {
        return runManager.runs(deviceId);
    }

    @GetMapping("/runs/{runId}")
    public SimulationRunDetailResponse run(@PathVariable("runId") UUID runId) {
        return runManager.run(runId);
    }

    private UUID userIdOrFallback(UUID userId) {
        return userId == null ? new UUID(0, 0) : userId;
    }
}
