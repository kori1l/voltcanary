import { describe, expect, it } from "vitest";
import { readSimulationSettings, updateSimulationYaml } from "./simulationYaml";

const yaml = `name: Fridge simulator
profile: FRIDGE
protocol: HTTP
sourceId: "plug-001"
interval: 30s
duration: 1h
speed: 1
metrics:
  voltageV:
    type: constant
    value: 230
  rssi:
    type: constant
    value: -40
cycle:
  offValue:
    mean: 20
  onValue:
    mean: 120
    noise: 5
  onDuration:
    min: 5m
    max: 10m
  offDuration:
    min: 10m
    max: 20m
anomalies:
  - type: POWER_SPIKE
    enabled: false
    startAfter: 10m
    duration: 1m
    powerW: 300
  - type: OFFLINE
    enabled: false
    startAfter: 20m
    duration: 2m
  - type: CONSUMPTION_GROWTH
    enabled: false
    startAfter: 30m
    growthPercent: 25
    rampDuration: 5m
`;

describe("simulationYaml", () => {
  it("reads editable simulator settings from YAML", () => {
    expect(readSimulationSettings(yaml)).toMatchObject({
      name: "Fridge simulator",
      sourceId: "plug-001",
      interval: "30s",
      activePowerW: "120",
      powerSpikeEnabled: false,
      growthPercent: "25",
    });
  });

  it("updates scalar and anomaly fields without rebuilding the YAML", () => {
    const updated = updateSimulationYaml(updateSimulationYaml(yaml, "sourceId", "plug-002"), "powerSpikeEnabled", true);

    expect(updated).toContain('sourceId: "plug-002"');
    expect(updated).toContain("enabled: true");
    expect(readSimulationSettings(updated).sourceId).toBe("plug-002");
    expect(readSimulationSettings(updated).powerSpikeEnabled).toBe(true);
  });
});
