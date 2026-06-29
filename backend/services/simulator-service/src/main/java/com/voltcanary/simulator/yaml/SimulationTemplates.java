package com.voltcanary.simulator.yaml;

import com.voltcanary.simulator.domain.SimulationProfile;
import com.voltcanary.simulator.dto.SimulationPresetResponse;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SimulationTemplates {

    public String template(SimulationProfile profile, String sourceId) {
        return template(profile, sourceId, "NORMAL_OPERATION");
    }

    public String template(SimulationProfile profile, String sourceId, String preset) {
        String normalizedSourceId = sourceId == null || sourceId.isBlank() ? "sim-fridge-001" : sourceId;
        String yaml = """
                schemaVersion: 1
                simulator:
                  name: kitchen-fridge-simulator
                  profile: %s
                  mode: NORMAL
                  protocol: HTTP
                target:
                  sourceId: "%s"
                schedule:
                  interval: 5s
                  startDelay: 0s
                  duration: 30m
                  speed: 1.0
                metrics:
                  voltageV:
                    type: constant
                    value: 230.0
                  rssi:
                    type: constant
                    value: -61
                  activePowerW:
                    type: cycle
                    offValue:
                      mean: 2.0
                      noise: 0.5
                    onValue:
                      mean: 120.0
                      noise: 8.0
                    cycle:
                      onDuration:
                        min: 8m
                        max: 16m
                      offDuration:
                        min: 20m
                        max: 45m
                  currentA:
                    type: derived
                    expression: "activePowerW / voltageV"
                  energyWh:
                    type: accumulator
                    from: activePowerW
                relayState:
                  type: derived
                  expression: "activePowerW > 5 ? 'ON' : 'OFF'"
                anomalies:
                  - type: POWER_SPIKE
                    enabled: false
                    startAfter: 10m
                    duration: 20s
                    powerW: 600
                  - type: OFFLINE
                    enabled: false
                    startAfter: 20m
                    duration: 5m
                  - type: CONSUMPTION_GROWTH
                    enabled: false
                    startAfter: 15m
                    growthPercent: 25
                    rampDuration: 10m
                """.formatted(profile.name(), normalizedSourceId);
        return applyPreset(yaml, preset);
    }

    public List<SimulationPresetResponse> presets() {
        List<String> metrics = List.of("activePowerW", "voltageV", "currentA", "energyWh", "rssi");
        return List.of(
                new SimulationPresetResponse(
                        "NORMAL_OPERATION",
                        "Normal operation",
                        "Stable telemetry for validating charts and device health states.",
                        metrics,
                        List.of(),
                        "30m",
                        "5s"),
                new SimulationPresetResponse(
                        "POWER_SPIKE",
                        "Power spike",
                        "Short high-power anomaly for alert rule and incident inbox testing.",
                        metrics,
                        List.of("POWER_SPIKE"),
                        "30m",
                        "5s"),
                new SimulationPresetResponse(
                        "OFFLINE_WINDOW",
                        "Offline window",
                        "Temporary telemetry gap for offline and stale-data workflows.",
                        metrics,
                        List.of("OFFLINE"),
                        "30m",
                        "5s"),
                new SimulationPresetResponse(
                        "CONSUMPTION_GROWTH",
                        "Consumption growth",
                        "Gradual load increase for baseline and trend alert testing.",
                        metrics,
                        List.of("CONSUMPTION_GROWTH"),
                        "30m",
                        "5s"));
    }

    private String applyPreset(String yaml, String preset) {
        if ("POWER_SPIKE".equalsIgnoreCase(preset)) {
            return yaml.replaceFirst("type: POWER_SPIKE\\R\\s+enabled: false", "type: POWER_SPIKE\n    enabled: true");
        }
        if ("OFFLINE_WINDOW".equalsIgnoreCase(preset) || "OFFLINE".equalsIgnoreCase(preset)) {
            return yaml.replaceFirst("type: OFFLINE\\R\\s+enabled: false", "type: OFFLINE\n    enabled: true");
        }
        if ("CONSUMPTION_GROWTH".equalsIgnoreCase(preset)) {
            return yaml.replaceFirst("type: CONSUMPTION_GROWTH\\R\\s+enabled: false", "type: CONSUMPTION_GROWTH\n    enabled: true");
        }
        return yaml;
    }
}
