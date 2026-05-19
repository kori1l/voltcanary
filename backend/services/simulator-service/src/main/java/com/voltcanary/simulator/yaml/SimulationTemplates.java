package com.voltcanary.simulator.yaml;

import com.voltcanary.simulator.domain.SimulationProfile;
import org.springframework.stereotype.Component;

@Component
public class SimulationTemplates {

    public String template(SimulationProfile profile, String sourceId) {
        String normalizedSourceId = sourceId == null || sourceId.isBlank() ? "sim-fridge-001" : sourceId;
        return """
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
    }
}
