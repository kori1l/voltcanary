import { AnomalyResponse, BaselineResponse } from "../../../analytics/analyticsApi";
import { TelemetryChart } from "../../../telemetry/telemetryApi";
import {
  analyzeRelatedSignals,
  calculateStatistics,
  determineDeviceHealth,
  determineMetricStatus,
  detectTelemetryEvents,
  metricConclusion,
  thresholdFor,
} from "./telemetry.analytics";
import {
  MetricDetailResponse,
  TelemetryEvent,
  TelemetryMetricOverview,
  TelemetryOverviewResponse,
  TelemetrySample,
} from "./telemetry.types";
import { getMetricDefinition } from "./telemetry.utils";

export function mapTelemetryOverview({
  deviceId,
  charts,
  latestTimestamp,
  selectedMetricKeys,
  range,
  baseline,
  anomalies,
}: {
  deviceId: string;
  charts: Record<string, TelemetryChart>;
  latestTimestamp: string | null;
  selectedMetricKeys: string[];
  range: TelemetryOverviewResponse["range"];
  baseline?: BaselineResponse | null;
  anomalies?: AnomalyResponse[];
}): TelemetryOverviewResponse {
  const metrics = Object.entries(charts)
    .map(([metricKey, chart]) => {
      const definition = getMetricDefinition(metricKey);
      const series = chart.points.map((point): TelemetrySample => ({
        timestamp: point.timestamp,
        metricKey,
        value: point.value,
        quality: "good",
      }));
      const threshold = thresholdFor(metricKey, metricKey === "activePowerW" && baseline ? {
        warningMax: baseline.p90,
        spikeThreshold: baseline.p90,
      } : undefined);
      const computedEvents = detectTelemetryEvents(definition, series, threshold);
      const analyticsEvents = (anomalies ?? [])
        .filter((anomaly) => metricKey === "activePowerW" && Date.parse(anomaly.detectedAt) >= Date.parse(range.from) && Date.parse(anomaly.detectedAt) <= Date.parse(range.to))
        .map((anomaly): TelemetryEvent => ({
          id: anomaly.id,
          timestamp: anomaly.detectedAt,
          type: "spike_detected",
          severity: normalizeSeverity(anomaly.severity),
          metricKey,
          title: anomaly.type.replace(/_/g, " "),
          description: anomaly.description,
          value: undefined,
          threshold: threshold?.spikeThreshold,
        }));
      const events = mergeEvents([...analyticsEvents, ...computedEvents]);
      const statistics = calculateStatistics(series, events);
      const status = determineMetricStatus(statistics, threshold, latestTimestamp);
      return {
        definition,
        status,
        statistics,
        threshold,
        series,
        conclusion: metricConclusion(definition, status, statistics),
      };
    })
    .sort((first, second) => first.definition.order - second.definition.order || first.definition.name.localeCompare(second.definition.name));

  const selectedMetrics = metrics.filter((metric) => selectedMetricKeys.includes(metric.definition.key));
  const health = determineDeviceHealth(selectedMetrics.length > 0 ? selectedMetrics : metrics, latestTimestamp);
  return {
    deviceId,
    range,
    health,
    metrics,
    events: mergeEvents([...(anomalies ?? []).filter((anomaly) => Date.parse(anomaly.detectedAt) >= Date.parse(range.from) && Date.parse(anomaly.detectedAt) <= Date.parse(range.to)).map((anomaly): TelemetryEvent => ({ id: anomaly.id, timestamp: anomaly.detectedAt, metricKey: "activePowerW", type: "custom", severity: normalizeSeverity(anomaly.severity), title: anomaly.type.replace(/_/g, " "), description: anomaly.description })), ...metrics.flatMap((metric) => metric.statistics.alertsCount > 0 || metric.status !== "normal"
      ? detectTelemetryEvents(metric.definition, metric.series, metric.threshold)
      : detectTelemetryEvents(metric.definition, metric.series, metric.threshold).slice(0, 2))]),
  };
}

export function mapMetricDetail(
  overview: TelemetryOverviewResponse,
  metricKey: string,
  selectedEventId?: string | null,
): MetricDetailResponse | null {
  const metric = overview.metrics.find((item) => item.definition.key === metricKey);
  if (!metric) {
    return null;
  }
  const metricEvents = overview.events.filter((event) => !event.metricKey || event.metricKey === metricKey);
  const selectedEvent = overview.events.find((event) => event.id === selectedEventId) ?? metricEvents[0] ?? null;
  const analysis = analyzeRelatedSignals(selectedEvent, overview.metrics);
  return {
    deviceId: overview.deviceId,
    metric: metric.definition,
    status: metric.status,
    statistics: metric.statistics,
    threshold: metric.threshold,
    series: metric.series,
    events: metricEvents,
    relatedSignals: analysis.relatedSignals,
    analysis: {
      summary: analysis.summary,
      possibleCause: analysis.possibleCause,
      recommendedAction: analysis.recommendedAction,
    },
  };
}

function normalizeSeverity(value: string): TelemetryEvent["severity"] {
  const normalized = value.toLowerCase();
  if (normalized.includes("critical") || normalized.includes("high")) return "critical";
  if (normalized.includes("warning") || normalized.includes("medium")) return "warning";
  return "info";
}

function mergeEvents(events: TelemetryEvent[]) {
  const seen = new Set<string>();
  return events
    .sort((first, second) => new Date(second.timestamp).getTime() - new Date(first.timestamp).getTime())
    .filter((event) => {
      const key = `${event.metricKey}-${event.type}-${event.timestamp}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
}
