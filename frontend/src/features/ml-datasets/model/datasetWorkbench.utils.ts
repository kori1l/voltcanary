import type {
  MlDatasetMetricCoverage,
  MlDatasetWorkbenchPreviewRequest,
  MlDatasetWorkbenchPreviewResponse,
  MlWorkbenchStatus,
} from "../../../analytics/mlModuleApi";
import type { Device } from "../../../devices/devicesApi";
import type {
  DatasetWorkbenchDraft,
  DatasetWorkbenchModelFamily,
  DatasetWorkbenchQuickRange,
  DatasetWorkbenchValidation,
} from "./datasetWorkbench.types";

const hourMs = 60 * 60 * 1000;
const dayMs = 24 * hourMs;

export const defaultDatasetWorkbenchDraft: DatasetWorkbenchDraft = {
  name: "Internal telemetry training set",
  description: "",
  modelFamily: "ANOMALY_DETECTION",
  horizonDays: 30,
  deviceIds: [],
  deviceQuery: "",
  deviceTypeFilter: "all",
  deviceStatusFilter: "all",
  metricSelectionMode: "COMMON",
  selectedMetrics: [],
  focusedMetric: "",
  from: "2026-07-01T00:00",
  to: "2026-07-02T00:00",
  quickRange: "custom",
  selectedCandidateId: null,
  splitStrategy: "TIME_BASED",
  featureConfigVersion: "feature-config-v1",
  minSamplesPerMetric: 24,
  maxSamplingGapSeconds: 21600,
};

export function datasetWorkbenchDraftKey(accountId: string) {
  return `voltcanary.ml.${accountId}.datasets.ANOMALY_DETECTION.internal-dataset`;
}

export function applyQuickRange(range: DatasetWorkbenchQuickRange, now = new Date()): Pick<DatasetWorkbenchDraft, "from" | "to" | "quickRange"> {
  if (range === "custom") {
    return {
      from: defaultDatasetWorkbenchDraft.from,
      to: defaultDatasetWorkbenchDraft.to,
      quickRange: range,
    };
  }
  const duration = range === "24h" ? dayMs : range === "7d" ? 7 * dayMs : 30 * dayMs;
  return {
    from: isoToDateTimeLocal(new Date(now.getTime() - duration).toISOString()),
    to: isoToDateTimeLocal(now.toISOString()),
    quickRange: range,
  };
}

export function buildPreviewRequest(draft: DatasetWorkbenchDraft): MlDatasetWorkbenchPreviewRequest {
  return {
    modelFamily: draft.modelFamily,
    deviceIds: draft.deviceIds,
    deviceType: draft.deviceTypeFilter === "all" ? null : draft.deviceTypeFilter,
    deviceMetadata: {},
    metricSelectionMode: draft.metricSelectionMode,
    selectedMetrics: draft.selectedMetrics,
    from: dateTimeLocalToIso(draft.from),
    to: dateTimeLocalToIso(draft.to),
    quickRange: draft.quickRange,
    horizonDays: draft.modelFamily === "FAILURE_PREDICTION" ? draft.horizonDays : null,
    splitStrategy: draft.splitStrategy,
    splitConfigJson: "{}",
    featureConfigVersion: draft.featureConfigVersion.trim() || "feature-config-v1",
    minSamplesPerMetric: draft.minSamplesPerMetric,
    maxSamplingGapSeconds: draft.maxSamplingGapSeconds,
    candidateLimit: 4,
  };
}

export function reconcileDraftWithPreview(
  draft: DatasetWorkbenchDraft,
  preview: MlDatasetWorkbenchPreviewResponse,
): DatasetWorkbenchDraft {
  const availableNames = availableMetricNames(preview);
  const defaultMetrics = preview.createRequestDefaults.metrics.filter((metric) => availableNames.includes(metric));
  const selectedMetrics = draft.selectedMetrics.length > 0 ? draft.selectedMetrics : defaultMetrics;
  const focusedMetric = selectedMetrics.includes(draft.focusedMetric) && availableNames.includes(draft.focusedMetric)
    ? draft.focusedMetric
    : selectedMetrics.find((metric) => availableNames.includes(metric)) ?? availableNames[0] ?? "";

  return {
    ...draft,
    selectedMetrics,
    focusedMetric,
    selectedCandidateId: preview.recommendedCandidateId ?? draft.selectedCandidateId,
    splitStrategy: preview.createRequestDefaults.splitStrategy ?? draft.splitStrategy,
    featureConfigVersion: preview.createRequestDefaults.featureConfigVersion ?? draft.featureConfigVersion,
    minSamplesPerMetric: preview.createRequestDefaults.minSamplesPerMetric ?? draft.minSamplesPerMetric,
    maxSamplingGapSeconds: preview.createRequestDefaults.maxSamplingGapSeconds ?? draft.maxSamplingGapSeconds,
  };
}

export function selectedDeviceSummary(devices: Device[], deviceIds: string[], preview: MlDatasetWorkbenchPreviewResponse | null) {
  const selectedDevices = devices.filter((device) => deviceIds.includes(device.id));
  const staleDeviceIds = deviceIds.filter((id) => devices.length > 0 && !devices.some((device) => device.id === id));
  const activeCount = selectedDevices.filter((device) => isActiveDeviceStatus(device.status)).length;
  const telemetryPresentCount = selectedDevices.filter((device) => deviceHasTelemetryMetadata(device, preview)).length;

  return {
    selectedCount: deviceIds.length,
    knownCount: selectedDevices.length,
    activeCount,
    inactiveCount: selectedDevices.length - activeCount,
    telemetryPresentCount,
    missingTelemetryCount: selectedDevices.length - telemetryPresentCount,
    staleDeviceIds,
  };
}

export function filterDevices(devices: Device[], draft: DatasetWorkbenchDraft) {
  const query = draft.deviceQuery.trim().toLowerCase();
  return devices.filter((device) => {
    if (draft.deviceTypeFilter !== "all" && device.type !== draft.deviceTypeFilter) {
      return false;
    }
    if (draft.deviceStatusFilter !== "all" && device.status !== draft.deviceStatusFilter) {
      return false;
    }
    if (!query) {
      return true;
    }
    return [
      device.id,
      device.name,
      device.type,
      device.status,
      device.locationId ?? "",
      device.manufacturer ?? "",
      device.model ?? "",
    ].some((value) => value.toLowerCase().includes(query));
  });
}

export function availableMetricNames(preview: MlDatasetWorkbenchPreviewResponse | null) {
  return preview?.availableMetrics.map((metric) => metric.metricName) ?? [];
}

export function metricByName(preview: MlDatasetWorkbenchPreviewResponse | null, metricName: string) {
  return preview?.availableMetrics.find((metric) => metric.metricName === metricName) ?? null;
}

export function validateWorkbench(
  draft: DatasetWorkbenchDraft,
  devices: Device[],
  preview: MlDatasetWorkbenchPreviewResponse | null,
  previewStatus: "idle" | "loading" | "ready" | "stale" | "error",
): DatasetWorkbenchValidation {
  const staleDeviceIds = selectedDeviceSummary(devices, draft.deviceIds, preview).staleDeviceIds;
  const availableNames = availableMetricNames(preview);
  const staleMetrics = preview ? draft.selectedMetrics.filter((metric) => !availableNames.includes(metric)) : [];
  const blockingReasons = [
    ...staleDeviceIds.map((id) => `Device ${id} is no longer available in the selector.`),
    ...staleMetrics.map((metric) => `Metric ${metric} is not available in the latest preview.`),
    ...(preview?.blockingReasons ?? []),
    ...(preview?.qualityGates ?? []).filter((gate) => gate.status === "BLOCK" && gate.blocking).map((gate) => gate.message),
  ].filter(Boolean);
  const warningReasons = [
    ...(preview?.warningReasons ?? []),
    ...(preview?.qualityGates ?? []).filter((gate) => gate.status === "WARN").map((gate) => gate.message),
  ].filter(Boolean);

  let createDisabledReason: string | null = null;
  if (!draft.name.trim()) {
    createDisabledReason = "Dataset name is required.";
  } else if (draft.deviceIds.length === 0) {
    createDisabledReason = "Select at least one device.";
  } else if (draft.selectedMetrics.length === 0) {
    createDisabledReason = "Select at least one preview metric.";
  } else if (!isValidRange(draft.from, draft.to)) {
    createDisabledReason = "From must be before To.";
  } else if (previewStatus === "idle") {
    createDisabledReason = "Run preview before creating a dataset.";
  } else if (previewStatus === "loading") {
    createDisabledReason = "Preview is still loading.";
  } else if (previewStatus === "stale") {
    createDisabledReason = "Preview is stale; refresh preview first.";
  } else if (previewStatus === "error") {
    createDisabledReason = "Preview failed; retry before creating.";
  } else if (preview?.status === "BLOCKED") {
    createDisabledReason = "Preview is blocked.";
  } else if (blockingReasons.length > 0) {
    createDisabledReason = blockingReasons[0];
  }

  return {
    staleDeviceIds,
    staleMetrics,
    blockingReasons,
    warningReasons,
    createDisabled: Boolean(createDisabledReason),
    createDisabledReason,
  };
}

export function dateTimeLocalToIso(value: string) {
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date.toISOString() : value;
}

export function isoToDateTimeLocal(value: string) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) {
    return value.slice(0, 16);
  }
  const pad = (item: number) => String(item).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function isValidRange(from: string, to: string) {
  const fromMs = new Date(from).getTime();
  const toMs = new Date(to).getTime();
  return Number.isFinite(fromMs) && Number.isFinite(toMs) && fromMs < toMs;
}

export function formatPercent(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return "N/A";
  }
  return `${Math.round(value * 100)}%`;
}

export function formatNumber(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return "N/A";
  }
  return Number.isInteger(value) ? value.toLocaleString() : value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

export function formatSeconds(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return "N/A";
  }
  if (value >= 86400) {
    return `${formatNumber(value / 86400)}d`;
  }
  if (value >= 3600) {
    return `${formatNumber(value / 3600)}h`;
  }
  return `${formatNumber(value)}s`;
}

export function formatDateTime(value: string) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) {
    return value;
  }
  return date.toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function statusLabel(value: string) {
  return value
    .replace(/_/g, " ")
    .toLowerCase()
    .replace(/^./, (match) => match.toUpperCase());
}

export function workbenchStatusClass(status: MlWorkbenchStatus | "PASS" | "WARN" | "BLOCK" | string) {
  if (status === "READY" || status === "PASS") {
    return "workbench-status-ready";
  }
  if (status === "READY_WITH_WARNINGS" || status === "WARN" || status === "NO_DATA") {
    return "workbench-status-warning";
  }
  if (status === "BLOCKED" || status === "BLOCK") {
    return "workbench-status-blocked";
  }
  return "workbench-status-muted";
}

export function metricStatusReason(metric: MlDatasetMetricCoverage) {
  if (metric.blockingReasons.length > 0) {
    return metric.blockingReasons.join(" ");
  }
  if (metric.warningReasons.length > 0) {
    return metric.warningReasons.join(" ");
  }
  return statusLabel(metric.status);
}

export function humanizeModelFamily(value: DatasetWorkbenchModelFamily) {
  return value === "FAILURE_PREDICTION" ? "Failure prediction" : "Anomaly detection";
}

export function humanizeFeatureName(value: string) {
  return value
    .replace(/[_-]+/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part.length <= 2 ? part.toUpperCase() : part.replace(/^./, (match) => match.toUpperCase()))
    .join(" ");
}

function isActiveDeviceStatus(status: string) {
  return status === "ACTIVE" || status === "ONLINE";
}

function deviceHasTelemetryMetadata(device: Device, preview: MlDatasetWorkbenchPreviewResponse | null) {
  if (preview?.availableMetrics.some((metric) => metric.devicesWithMetric > 0)) {
    return true;
  }
  const metadata = device.metadata ?? {};
  return ["metrics", "mlMetrics", "telemetryMetrics"].some((key) => {
    const raw = metadata[key];
    if (Array.isArray(raw)) {
      return raw.length > 0;
    }
    if (typeof raw === "string") {
      return raw.split(/[\n,]+/).some((item) => item.trim());
    }
    return false;
  });
}
