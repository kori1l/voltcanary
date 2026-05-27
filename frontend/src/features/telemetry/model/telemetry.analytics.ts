import { DEFAULT_THRESHOLDS, STALE_AFTER_MS } from "./telemetry.constants";
import {
  DeviceHealth,
  MetricDefinition,
  MetricStatistics,
  MetricStatus,
  MetricThreshold,
  RelatedSignal,
  TelemetryEvent,
  TelemetryMetricOverview,
  TelemetrySample,
} from "./telemetry.types";
import { formatMetricValue } from "./telemetry.utils";

const STATUS_WEIGHT: Record<MetricStatus, number> = {
  no_data: 0,
  normal: 1,
  stale: 2,
  warning: 3,
  critical: 4,
};

export function calculateStatistics(series: TelemetrySample[], events: TelemetryEvent[] = []): MetricStatistics {
  const values = series.map((sample) => sample.value).filter((value): value is number => value !== null && Number.isFinite(value));
  if (values.length === 0) {
    return {
      latest: null,
      min: null,
      max: null,
      avg: null,
      p95: null,
      p99: null,
      samplesCount: series.length,
      missingSamplesCount: series.length,
      anomaliesCount: events.filter((event) => event.type === "spike_detected" || event.type === "threshold_exceeded").length,
      alertsCount: events.filter((event) => event.severity !== "info").length,
    };
  }

  const sorted = [...values].sort((first, second) => first - second);
  const latest = [...series].reverse().find((sample) => sample.value !== null)?.value ?? null;
  return {
    latest,
    min: sorted[0],
    max: sorted[sorted.length - 1],
    avg: values.reduce((sum, value) => sum + value, 0) / values.length,
    median: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    samplesCount: series.length,
    missingSamplesCount: series.length - values.length,
    anomaliesCount: events.filter((event) => event.type === "spike_detected" || event.type === "threshold_exceeded").length,
    alertsCount: events.filter((event) => event.severity !== "info").length,
  };
}

export function thresholdFor(metricKey: string, fallback?: Partial<MetricThreshold>): MetricThreshold | undefined {
  const predefined = DEFAULT_THRESHOLDS.find((threshold) => threshold.metricKey === metricKey);
  if (!predefined && !fallback) {
    return undefined;
  }
  return { metricKey, ...predefined, ...fallback };
}

export function detectTelemetryEvents(
  metric: MetricDefinition,
  series: TelemetrySample[],
  threshold?: MetricThreshold,
): TelemetryEvent[] {
  if (series.length === 0) {
    return [];
  }

  const events: TelemetryEvent[] = [];
  const spikeThreshold = threshold?.spikeThreshold ?? threshold?.warningMax ?? threshold?.criticalMax;
  let previousTimestamp = new Date(series[0].timestamp).getTime();

  series.forEach((sample, index) => {
    const timestamp = new Date(sample.timestamp).getTime();
    if (index > 0 && timestamp - previousTimestamp > STALE_AFTER_MS) {
      events.push({
        id: `${metric.key}-gap-${sample.timestamp}`,
        timestamp: sample.timestamp,
        type: "data_gap",
        severity: "warning",
        metricKey: metric.key,
        title: "Data gap",
        description: `No ${metric.name.toLowerCase()} samples for ${Math.round((timestamp - previousTimestamp) / 60000)} minutes.`,
      });
    }
    previousTimestamp = timestamp;

    if (sample.value === null) {
      return;
    }
    const severity = severityForValue(sample.value, threshold);
    if (severity !== "info") {
      events.push({
        id: `${metric.key}-threshold-${sample.timestamp}`,
        timestamp: sample.timestamp,
        type: "threshold_exceeded",
        severity,
        metricKey: metric.key,
        title: `${metric.name} ${severity === "critical" ? "critical" : "warning"}`,
        description: `${formatMetricValue(sample.value, metric.unit)} is outside the expected range.`,
        value: sample.value,
        threshold: threshold?.criticalMax ?? threshold?.warningMax ?? threshold?.criticalMin ?? threshold?.warningMin,
      });
      return;
    }
    if (spikeThreshold !== undefined && sample.value >= spikeThreshold) {
      events.push({
        id: `${metric.key}-spike-${sample.timestamp}`,
        timestamp: sample.timestamp,
        type: "spike_detected",
        severity: "warning",
        metricKey: metric.key,
        title: `${metric.name} spike`,
        description: `${formatMetricValue(sample.value, metric.unit)} exceeded spike threshold.`,
        value: sample.value,
        threshold: spikeThreshold,
      });
    }
  });

  return compactEvents(events);
}

export function determineMetricStatus(
  statistics: MetricStatistics,
  threshold: MetricThreshold | undefined,
  lastSampleAt: string | null,
): MetricStatus {
  if (statistics.samplesCount === 0 || statistics.latest === null) {
    return "no_data";
  }
  if (lastSampleAt && Date.now() - new Date(lastSampleAt).getTime() > STALE_AFTER_MS) {
    return "stale";
  }
  if (statistics.max !== null && isCritical(statistics.max, threshold)) {
    return "critical";
  }
  if (statistics.min !== null && isCritical(statistics.min, threshold)) {
    return "critical";
  }
  if (statistics.max !== null && isWarning(statistics.max, threshold)) {
    return "warning";
  }
  if (statistics.min !== null && isWarning(statistics.min, threshold)) {
    return "warning";
  }
  if (statistics.alertsCount > 0) {
    return "warning";
  }
  return "normal";
}

export function determineDeviceHealth(metrics: TelemetryMetricOverview[], lastSampleAt: string | null): DeviceHealth {
  if (metrics.length === 0) {
    return {
      status: "no_data",
      reason: "No telemetry samples are available for the selected range.",
      lastSampleAt,
      dataQuality: "unknown",
      activeAlertsCount: 0,
    };
  }

  const worst = metrics.reduce((current, metric) =>
    STATUS_WEIGHT[metric.status] > STATUS_WEIGHT[current.status] ? metric : current,
  );
  const activeAlertsCount = metrics.reduce((sum, metric) => sum + metric.statistics.alertsCount, 0);
  const missing = metrics.reduce((sum, metric) => sum + (metric.statistics.missingSamplesCount ?? 0), 0);
  const samples = metrics.reduce((sum, metric) => sum + metric.statistics.samplesCount, 0);
  const dataQuality = samples === 0 ? "unknown" : missing / samples > 0.2 ? "partial" : "good";

  return {
    status: worst.status,
    reason: statusReason(worst),
    lastSampleAt,
    dataQuality,
    activeAlertsCount,
  };
}

export function metricConclusion(metric: MetricDefinition, status: MetricStatus, statistics: MetricStatistics) {
  if (status === "no_data") return `${metric.name} has no samples in this range.`;
  if (status === "stale") return `${metric.name} data is stale.`;
  if (status === "critical") return `${metric.name} reached a critical value.`;
  if (status === "warning") return `${metric.name} needs attention.`;
  if (metric.key === "voltageV") return "Voltage is stable.";
  if (metric.key === "activePowerW") return "Power draw is within the expected range.";
  return `${metric.name} looks normal. Latest value is ${formatMetricValue(statistics.latest, metric.unit)}.`;
}

export function analyzeRelatedSignals(
  event: TelemetryEvent | null,
  metrics: TelemetryMetricOverview[],
): { relatedSignals: RelatedSignal[]; summary: string; possibleCause?: string; recommendedAction?: string } {
  if (!event) {
    return {
      relatedSignals: metrics.slice(0, 4).map((metric) => ({
        metricKey: metric.definition.key,
        name: metric.definition.name,
        correlation: "unknown",
        note: "Select an event to compare related signals.",
      })),
      summary: "Select an event from the timeline to see related signal analysis.",
    };
  }

  const relatedSignals = metrics
    .filter((metric) => metric.definition.key !== event.metricKey)
    .slice(0, 4)
    .map((metric) => {
      const change = trendAroundEvent(metric.series, event.timestamp);
      return {
        metricKey: metric.definition.key,
        name: metric.definition.name,
        correlation: change,
        note: `${metric.definition.name} ${change === "unknown" ? "has insufficient data" : change} around the event.`,
      };
    });

  const current = metrics.find((metric) => metric.definition.key === event.metricKey);
  const currentTrend = current ? trendAroundEvent(current.series, event.timestamp) : "unknown";
  const currentIncreased = currentTrend === "increased";
  const currentMetricKey = current?.definition.key ?? event.metricKey;
  const currentIncreasedWithPower = relatedSignals.some((signal) => signal.metricKey === "currentA" && signal.correlation === "increased");
  const voltageStable = relatedSignals.some((signal) => signal.metricKey === "voltageV" && signal.correlation === "stable");
  const signalDropped = relatedSignals.some((signal) => signal.metricKey === "rssi" && signal.correlation === "decreased");

  if (currentMetricKey === "activePowerW" && currentIncreased && currentIncreasedWithPower && voltageStable) {
    return {
      relatedSignals,
      summary: "Power increased together with current while voltage stayed stable.",
      possibleCause: "Likely load increase, not power supply instability.",
      recommendedAction: "Check the connected load or power-consuming component.",
    };
  }
  if (relatedSignals.some((signal) => signal.metricKey === "voltageV" && signal.correlation === "decreased")) {
    return {
      relatedSignals,
      summary: "Voltage changed around the event and may have affected other metrics.",
      possibleCause: "Possible power supply instability.",
      recommendedAction: "Inspect the supply line and device power conditions.",
    };
  }
  if (signalDropped || event.type === "data_gap") {
    return {
      relatedSignals,
      summary: "Connectivity-related signals degraded around the event.",
      possibleCause: "Possible network or signal quality issue.",
      recommendedAction: "Check device connectivity and signal strength.",
    };
  }
  return {
    relatedSignals,
    summary: `${event.title} appears isolated in the selected range.`,
    possibleCause: "No strong related signal pattern was detected.",
    recommendedAction: "Monitor the device and inspect the metric if this repeats.",
  };
}

function percentile(sortedValues: number[], p: number) {
  if (sortedValues.length === 0) return null;
  const index = Math.min(sortedValues.length - 1, Math.ceil((p / 100) * sortedValues.length) - 1);
  return sortedValues[index];
}

function severityForValue(value: number, threshold?: MetricThreshold): "info" | "warning" | "critical" {
  if (isCritical(value, threshold)) return "critical";
  if (isWarning(value, threshold)) return "warning";
  return "info";
}

function isCritical(value: number, threshold?: MetricThreshold) {
  return (
    (threshold?.criticalMax !== undefined && value > threshold.criticalMax) ||
    (threshold?.criticalMin !== undefined && value < threshold.criticalMin)
  );
}

function isWarning(value: number, threshold?: MetricThreshold) {
  return (
    (threshold?.warningMax !== undefined && value > threshold.warningMax) ||
    (threshold?.warningMin !== undefined && value < threshold.warningMin)
  );
}

function compactEvents(events: TelemetryEvent[]) {
  const sorted = [...events].sort((first, second) => new Date(first.timestamp).getTime() - new Date(second.timestamp).getTime());
  return sorted.filter((event, index) => {
    const previous = sorted[index - 1];
    if (!previous) return true;
    return event.metricKey !== previous.metricKey || event.type !== previous.type || new Date(event.timestamp).getTime() - new Date(previous.timestamp).getTime() > 60_000;
  });
}

function statusReason(metric: TelemetryMetricOverview) {
  if (metric.status === "normal") return "All selected metrics are within expected ranges.";
  if (metric.status === "stale") return `${metric.definition.name} telemetry is stale.`;
  if (metric.status === "no_data") return `${metric.definition.name} has no data in the selected range.`;
  return `${metric.definition.name} is ${metric.status}.`;
}

function trendAroundEvent(series: TelemetrySample[], timestamp: string): RelatedSignal["correlation"] {
  const eventTime = new Date(timestamp).getTime();
  const before = averageInWindow(series, eventTime - 5 * 60_000, eventTime);
  const after = averageInWindow(series, eventTime, eventTime + 5 * 60_000);
  if (before === null || after === null) return "unknown";
  const delta = after - before;
  const tolerance = Math.max(Math.abs(before) * 0.05, 0.05);
  if (delta > tolerance) return "increased";
  if (delta < -tolerance) return "decreased";
  return "stable";
}

function averageInWindow(series: TelemetrySample[], from: number, to: number) {
  const values = series
    .filter((sample) => {
      const time = new Date(sample.timestamp).getTime();
      return time >= from && time <= to && sample.value !== null;
    })
    .map((sample) => sample.value as number);
  return values.length === 0 ? null : values.reduce((sum, value) => sum + value, 0) / values.length;
}
