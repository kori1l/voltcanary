import { AnomalyResponse, PredictionResponse, RecommendationResponse, RiskResponse } from "../../analytics/analyticsApi";
import { TelemetryOverviewResponse } from "../../features/telemetry/model/telemetry.types";
import { formatMetricValue as formatTelemetryMetricValue } from "../../features/telemetry/model/telemetry.utils";
import { DeviceDetailsTab } from "./DeviceTabs";
import { ChartTimeRange } from "./deviceTelemetryModel";
import { formatEnum } from "./deviceDetailsFormatting";
export function normalizeDeviceTab(value: string | null): DeviceDetailsTab {
  if (value === "telemetry" || value === "analytics" || value === "alerts" || value === "maintenance" || value === "configuration" || value === "simulation") {
    return value;
  }
  if (value === "registry" || value === "config") {
    return "configuration";
  }
  return "overview";
}

export function buildIncidentSummary(overview: TelemetryOverviewResponse, riskLevel: string | null, range: ChartTimeRange) {
  const problemMetric =
    overview.metrics.find((metric) => ["critical", "warning", "stale"].includes(metric.status)) ??
    overview.metrics.find((metric) => metric.definition.key === "activePowerW") ??
    overview.metrics[0];
  const riskLabel = riskLevel ? `${formatEnum(riskLevel)} risk` : `${overview.health.status.replace("_", " ")} health`;
  const currentValue = problemMetric ? formatTelemetryMetricValue(problemMetric.statistics.latest, problemMetric.definition.unit) : "No data";
  const peakValue = problemMetric ? formatTelemetryMetricValue(problemMetric.statistics.max, problemMetric.definition.unit) : "No data";
  const title = problemMetric
    ? problemMetric.status === "normal"
      ? `${problemMetric.definition.name} stable`
      : `${problemMetric.definition.name} ${problemMetric.status.replace("_", " ")}`
    : "No telemetry data";
  return {
    riskLabel,
    riskTone: normalizeRiskTone(riskLevel ?? overview.health.status),
    title,
    eventCount: overview.events.length,
    timeRangeLabel: range === "custom" ? "custom range" : `last ${range}`,
    peakValue,
    currentValue,
    conclusion: buildIncidentConclusion(overview, problemMetric?.definition.key),
    problemMetricName: problemMetric?.definition.name ?? "Metric",
    sampleCount: problemMetric?.statistics.samplesCount ?? 0,
  };
}

export function buildIncidentConclusion(overview: TelemetryOverviewResponse, metricKey?: string) {
  const voltage = overview.metrics.find((metric) => metric.definition.key === "voltageV");
  const current = overview.metrics.find((metric) => metric.definition.key === "currentA");
  if (metricKey === "activePowerW" && current?.status !== "no_data" && voltage?.status === "normal") {
    return "Power changed with current while voltage stayed stable, so this is likely load-related.";
  }
  const problem = overview.metrics.find((metric) => metric.definition.key === metricKey);
  return problem?.conclusion ?? overview.health.reason;
}

export function buildWhyBullets(overview: TelemetryOverviewResponse, fallback: string[]) {
  const problem =
    overview.metrics.find((metric) => ["critical", "warning", "stale"].includes(metric.status)) ??
    overview.metrics.find((metric) => metric.definition.key === "activePowerW") ??
    overview.metrics[0];
  if (!problem) {
    return fallback.slice(0, 4);
  }
  const eventCount = overview.events.filter((event) => !event.metricKey || event.metricKey === problem.definition.key).length;
  const bullets = [
    eventCount > 0
      ? `${problem.definition.name} crossed monitoring rules ${eventCount} times in the selected range.`
      : `${problem.definition.name} did not cross monitoring rules in the selected range.`,
    `Peak value reached ${formatTelemetryMetricValue(problem.statistics.max, problem.definition.unit)}.`,
    `Latest value is ${formatTelemetryMetricValue(problem.statistics.latest, problem.definition.unit)}.`,
  ];
  const voltage = overview.metrics.find((metric) => metric.definition.key === "voltageV");
  if (problem.definition.key === "activePowerW" && voltage?.status === "normal") {
    bullets.push("Voltage stayed stable, so the issue is more likely related to load.");
  }
  return bullets;
}

export function buildAnalyticsSummary(overview: TelemetryOverviewResponse) {
  if (overview.metrics.length === 0) return null;
  const metrics = overview.metrics.map((metric) => ({
    key: metric.definition.key,
    name: metric.definition.name,
    status: metric.status,
    latest: formatTelemetryMetricValue(metric.statistics.latest, metric.definition.unit),
    peak: formatTelemetryMetricValue(metric.statistics.max, metric.definition.unit),
    events: overview.events.filter((event) => event.metricKey === metric.definition.key).length,
  }));
  const warningCount = overview.metrics.filter((metric) => metric.status === "warning" || metric.status === "stale").length;
  const criticalCount = overview.metrics.filter((metric) => metric.status === "critical").length;
  const noDataCount = overview.metrics.filter((metric) => metric.status === "no_data").length;
  const worst = overview.metrics.find((metric) => metric.status === "critical") ??
    overview.metrics.find((metric) => metric.status === "warning" || metric.status === "stale") ??
    overview.metrics[0];
  return {
    metrics,
    warningCount,
    criticalCount,
    eventCount: overview.events.length,
    healthStatus: overview.health.status,
    reasons: [
      `${overview.events.length} telemetry events were detected in the selected range.`,
      `${warningCount + criticalCount} metrics are outside normal state.`,
      `${worst.definition.name} is the strongest signal: latest ${formatTelemetryMetricValue(worst.statistics.latest, worst.definition.unit)}, peak ${formatTelemetryMetricValue(worst.statistics.max, worst.definition.unit)}.`,
      overview.health.reason,
    ],
  };
}

export function buildPredictionInsight(prediction: PredictionResponse | null, analytics: NonNullable<ReturnType<typeof buildAnalyticsSummary>>) {
  if (!prediction || /DISABLED|INSUFFICIENT|UNAVAILABLE|ERROR|FAILED|NO_DATA/i.test(prediction.predictionStatus)) {
    return { tone: "neutral", title: "Prediction evidence unavailable", summary: prediction ? `Prediction status: ${prediction.predictionStatus}` : "No prediction was returned. Configure ML and collect sufficient telemetry before assessing model risk.", confidence: "Unavailable", anomalyScore: "Unavailable", degradationScore: "Unavailable", primarySignal: "Unknown", modelVersion: prediction?.modelVersion ?? "Unavailable", evidence: prediction?.explanations ?? ["Telemetry observations alone are not a model prediction."], nextCheck: "Check ML settings, data coverage and the latest prediction status." };
  }
  const primary = analytics.metrics
    .filter((metric) => metric.status !== "normal")
    .sort((left, right) => right.events - left.events)[0] ?? analytics.metrics[0];
  const anomalyScore = prediction.anomalyScore;
  const degradationScore = prediction.degradationScore;
  const confidence = prediction.confidence;
  const tone = anomalyScore >= 0.75 || analytics.criticalCount > 0 ? "critical" : anomalyScore >= 0.35 || analytics.warningCount > 0 ? "warning" : "normal";
  const title = tone === "critical"
    ? "High anomaly risk predicted"
    : tone === "warning"
      ? "Anomaly risk is elevated"
      : "No near-term anomaly pattern detected";
  const modelEvidence = prediction?.explanations?.filter(Boolean) ?? [];
  return {
    tone,
    title,
    summary: `Backend prediction (${prediction.predictionStatus}) has ${Math.round(confidence * 100)}% confidence.`,
    confidence: `${Math.round(confidence * 100)}% confidence`,
    anomalyScore: `${Math.round(anomalyScore * 100)} / 100`,
    degradationScore: `${Math.round(degradationScore * 100)} / 100`,
    primarySignal: primary?.name ?? "No signal",
    modelVersion: prediction?.modelVersion ?? "Telemetry rules",
    evidence: [
      ...modelEvidence,
      `${analytics.eventCount} events detected in the selected telemetry window.`,
      `${analytics.warningCount} warning metrics and ${analytics.criticalCount} critical metrics are active.`,
      primary ? `${primary.name}: latest ${primary.latest}, peak ${primary.peak}.` : "No metric samples are available.",
    ].slice(0, 5),
    nextCheck: tone === "normal"
      ? "Keep monitoring. No immediate intervention is suggested by the current telemetry."
      : primary?.key === "activePowerW"
        ? "Check load changes, operating mode, and current draw. If voltage stays stable, focus on the connected load."
        : "Inspect the primary signal and compare it with related metrics in the detail view.",
  };
}

export function normalizeRiskTone(value: string) {
  const normalized = value.toLowerCase();
  if (normalized.includes("critical") || normalized.includes("high")) return "critical";
  if (normalized.includes("medium") || normalized.includes("warning")) return "high";
  if (normalized.includes("low") || normalized.includes("normal")) return "low";
  return "neutral";
}

export function getRiskInsight(
  risk: RiskResponse | null,
  anomalies: AnomalyResponse[],
  recommendations: RecommendationResponse[],
) {
  if (!risk) {
    return {
      tone: "neutral",
      title: "No risk score available",
      description: "Telemetry exists, but analytics have not produced a current risk score yet.",
      reasons: ["Risk has not been calculated for the current window."],
      nextAction: "Recalculate risk after new telemetry arrives.",
    };
  }

  const level = formatEnum(risk.level);
  const topAnomaly = anomalies[0];
  const topRecommendation = recommendations[0];
  const reasons =
    risk.explanations.length > 0
      ? risk.explanations.map(formatInsightText)
      : anomalies.length > 0
        ? anomalies.slice(0, 3).map((anomaly) => formatInsightText(anomaly.type))
        : ["No current anomaly explanation was returned by analytics."];
  const title = topAnomaly ? `${level} risk - ${formatInsightText(topAnomaly.type)}` : `${level} risk detected`;
  const description = topAnomaly
    ? `${topAnomaly.description} If this repeats, check the device load, power socket, or operating mode.`
    : `Risk score is ${Math.round(risk.score)} out of 100. Review recent power behavior and confirm the device is operating normally.`;

  return {
    tone: risk.level.toLowerCase(),
    title,
    description,
    reasons,
    nextAction: topRecommendation
      ? topRecommendation.description
      : "Check power load and operating conditions, then mark this device as checked.",
  };
}

export function formatInsightText(value: string) {
  return formatEnum(value)
    .replace("P90", "typical range")
    .replace("Ml", "ML")
    .replace("Power Spike", "Power spike")
    .replace("Consumption Growth", "Consumption growth");
}
