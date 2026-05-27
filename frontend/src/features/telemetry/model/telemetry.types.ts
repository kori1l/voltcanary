export type MetricStatus = "normal" | "warning" | "critical" | "stale" | "no_data";
export type MetricKind = "gauge" | "counter" | "accumulator" | "signal";
export type TelemetrySampleQuality = "good" | "partial" | "bad";
export type EventSeverity = "info" | "warning" | "critical";
export type TelemetryEventType =
  | "threshold_exceeded"
  | "spike_detected"
  | "returned_to_normal"
  | "data_gap"
  | "device_offline"
  | "custom";

export interface MetricDefinition {
  id: string;
  key: string;
  name: string;
  unit: string;
  kind: MetricKind;
  description?: string;
  defaultVisible: boolean;
  order: number;
}

export interface TelemetrySample {
  timestamp: string;
  metricKey: string;
  value: number | null;
  quality?: TelemetrySampleQuality;
}

export interface MetricStatistics {
  latest: number | null;
  min: number | null;
  max: number | null;
  avg: number | null;
  median?: number | null;
  p95?: number | null;
  p99?: number | null;
  samplesCount: number;
  missingSamplesCount?: number;
  anomaliesCount: number;
  alertsCount: number;
}

export interface MetricThreshold {
  metricKey: string;
  normalMin?: number;
  normalMax?: number;
  warningMin?: number;
  warningMax?: number;
  criticalMin?: number;
  criticalMax?: number;
  spikeThreshold?: number;
  spikeDurationSec?: number;
}

export interface TelemetryEvent {
  id: string;
  timestamp: string;
  endTimestamp?: string;
  type: TelemetryEventType;
  severity: EventSeverity;
  metricKey?: string;
  title: string;
  description?: string;
  value?: number;
  threshold?: number;
}

export interface DeviceHealth {
  status: MetricStatus;
  reason: string;
  lastSampleAt: string | null;
  dataQuality: "good" | "partial" | "bad" | "unknown";
  activeAlertsCount: number;
}

export interface TelemetryMetricOverview {
  definition: MetricDefinition;
  status: MetricStatus;
  statistics: MetricStatistics;
  threshold?: MetricThreshold;
  series: TelemetrySample[];
  conclusion: string;
}

export interface TelemetryOverviewResponse {
  deviceId: string;
  range: {
    from: string;
    to: string;
    preset: "15m" | "1h" | "6h" | "24h" | "7d" | "custom";
  };
  health: DeviceHealth;
  metrics: TelemetryMetricOverview[];
  events: TelemetryEvent[];
}

export interface RelatedSignal {
  metricKey: string;
  name: string;
  correlation: "increased" | "decreased" | "stable" | "unknown";
  note: string;
}

export interface MetricDetailResponse {
  deviceId: string;
  metric: MetricDefinition;
  status: MetricStatus;
  statistics: MetricStatistics;
  threshold?: MetricThreshold;
  series: TelemetrySample[];
  events: TelemetryEvent[];
  relatedSignals: RelatedSignal[];
  analysis?: {
    summary: string;
    possibleCause?: string;
    recommendedAction?: string;
  };
}
