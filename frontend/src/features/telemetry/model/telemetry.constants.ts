import { MetricDefinition, MetricThreshold } from "./telemetry.types";

export const DEFAULT_METRIC_DEFINITIONS: MetricDefinition[] = [
  {
    id: "activePowerW",
    key: "activePowerW",
    name: "Active power",
    unit: "W",
    kind: "gauge",
    description: "Current device power draw.",
    defaultVisible: true,
    order: 10,
  },
  {
    id: "voltageV",
    key: "voltageV",
    name: "Voltage",
    unit: "V",
    kind: "gauge",
    description: "Input voltage reported by the device.",
    defaultVisible: true,
    order: 20,
  },
  {
    id: "currentA",
    key: "currentA",
    name: "Current",
    unit: "A",
    kind: "gauge",
    description: "Current derived from power and voltage.",
    defaultVisible: true,
    order: 30,
  },
  {
    id: "energyWh",
    key: "energyWh",
    name: "Energy",
    unit: "Wh",
    kind: "accumulator",
    description: "Accumulated energy consumption.",
    defaultVisible: true,
    order: 40,
  },
  {
    id: "rssi",
    key: "rssi",
    name: "Signal strength",
    unit: "dBm",
    kind: "signal",
    description: "Telemetry radio or network signal strength.",
    defaultVisible: false,
    order: 50,
  },
];

export const DEFAULT_THRESHOLDS: MetricThreshold[] = [
  {
    metricKey: "activePowerW",
    normalMin: 0,
    warningMax: 180,
    criticalMax: 400,
    spikeThreshold: 180,
    spikeDurationSec: 20,
  },
  {
    metricKey: "voltageV",
    normalMin: 228,
    normalMax: 232,
    warningMin: 225,
    warningMax: 235,
    criticalMin: 220,
    criticalMax: 240,
  },
  {
    metricKey: "currentA",
    normalMin: 0,
    warningMax: 1,
    criticalMax: 2,
  },
  {
    metricKey: "rssi",
    normalMin: -70,
    warningMin: -80,
    criticalMin: -90,
  },
];

export const STALE_AFTER_MS = 10 * 60 * 1000;
