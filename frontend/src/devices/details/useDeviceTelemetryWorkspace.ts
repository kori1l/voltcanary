import { useEffect, useMemo, useState } from "react";
import { getLatestTelemetry, getTelemetryReadings, LatestTelemetry, TelemetryChart } from "../../telemetry/telemetryApi";
import { DeviceDetailsTab } from "./DeviceTabs";
import {
  arraysEqual,
  buildAvailableChartMetrics,
  buildChartMetrics,
  CHART_DENSITY_LIMITS,
  ChartDensity,
  ChartTimeRange,
  DEFAULT_CHART_METRICS,
  getChartTimeWindow,
  readDashboardChartPreferences,
  writeDashboardChartPreferences,
} from "./deviceTelemetryModel";
import { toDateTimeLocalValue } from "./deviceDetailsFormatting";

export function useDeviceTelemetryWorkspace({
  deviceId,
  activeTab,
  searchParams,
}: {
  deviceId: string | undefined;
  activeTab: DeviceDetailsTab;
  searchParams: URLSearchParams;
}) {
  const initialChartPreferences = readDashboardChartPreferences(deviceId);
  const [latest, setLatest] = useState<LatestTelemetry | null>(null);
  const [charts, setCharts] = useState<Record<string, TelemetryChart>>({});
  const [selectedChartMetricKeys, setSelectedChartMetricKeys] = useState<string[]>(initialChartPreferences.selectedMetricKeys);
  const [showChartPoints, setShowChartPoints] = useState(initialChartPreferences.showChartPoints);
  const [chartDensity, setChartDensity] = useState<ChartDensity>(initialChartPreferences.chartDensity);
  const [chartTimeRange, setChartTimeRange] = useState<ChartTimeRange>(initialChartPreferences.chartTimeRange);
  const [customChartFrom, setCustomChartFrom] = useState(initialChartPreferences.customChartFrom);
  const [customChartTo, setCustomChartTo] = useState(initialChartPreferences.customChartTo);
  const [metricSearch, setMetricSearch] = useState("");
  const [expandedChartMetricKey, setExpandedChartMetricKey] = useState<string | null>(initialChartPreferences.expandedChartMetricKey);
  const [selectedTelemetryEventId, setSelectedTelemetryEventId] = useState<string | null>(null);
  const [isTelemetryLoading, setIsTelemetryLoading] = useState(false);

  const chartTimeWindow = useMemo(() => getChartTimeWindow(chartTimeRange, customChartFrom, customChartTo), [chartTimeRange, customChartFrom, customChartTo]);
  const availableChartMetrics = useMemo(() => buildAvailableChartMetrics(charts), [charts]);
  const availableChartKeySignature = availableChartMetrics.map((metric) => metric.key).join("|");
  const chartMetrics = useMemo(
    () => availableChartMetrics.filter((metric) => selectedChartMetricKeys.includes(metric.key)),
    [availableChartMetrics, selectedChartMetricKeys],
  );
  const expandedChartMetric = availableChartMetrics.find((metric) => metric.key === expandedChartMetricKey) ?? null;

  async function loadTelemetry(currentDeviceId = deviceId) {
    if (!currentDeviceId || !chartTimeWindow) {
      return false;
    }

    setIsTelemetryLoading(true);
    try {
      const latestResponse = await getLatestTelemetry(currentDeviceId);
      setLatest(latestResponse);
      const numericMetrics = buildChartMetrics(latestResponse.readings);
      const chartResponses = await Promise.all(
        numericMetrics.map(async (metric) => [metric.key, await getTelemetryReadings(currentDeviceId, metric.key, chartTimeWindow.from, chartTimeWindow.to)] as const),
      );
      setCharts(Object.fromEntries(chartResponses));
      return true;
    } catch {
      setLatest(null);
      setCharts({});
      return false;
    } finally {
      setIsTelemetryLoading(false);
    }
  }

  useEffect(() => {
    const preferences = readDashboardChartPreferences(deviceId);
    setSelectedChartMetricKeys(preferences.selectedMetricKeys);
    setShowChartPoints(preferences.showChartPoints);
    setChartDensity(preferences.chartDensity);
    setChartTimeRange(preferences.chartTimeRange);
    setCustomChartFrom(preferences.customChartFrom);
    setCustomChartTo(preferences.customChartTo);
    setExpandedChartMetricKey(preferences.expandedChartMetricKey);
  }, [deviceId]);

  useEffect(() => {
    const metric = searchParams.get("metric");
    const from = searchParams.get("from");
    const to = searchParams.get("to");
    if (activeTab !== "telemetry" || !metric) {
      return;
    }
    setSelectedChartMetricKeys([metric]);
    setExpandedChartMetricKey(metric);
    if (from && to) {
      setChartTimeRange("custom");
      setCustomChartFrom(toDateTimeLocalValue(new Date(from)));
      setCustomChartTo(toDateTimeLocalValue(new Date(to)));
    }
  }, [activeTab, searchParams]);

  useEffect(() => {
    if (!deviceId) return;
    writeDashboardChartPreferences(deviceId, {
      selectedMetricKeys: selectedChartMetricKeys,
      showChartPoints,
      chartDensity,
      chartTimeRange,
      customChartFrom,
      customChartTo,
      expandedChartMetricKey,
    });
  }, [deviceId, selectedChartMetricKeys, showChartPoints, chartDensity, chartTimeRange, customChartFrom, customChartTo, expandedChartMetricKey]);

  useEffect(() => {
    if (!deviceId) {
      return;
    }
    void loadTelemetry(deviceId);
  }, [deviceId, chartTimeRange, customChartFrom, customChartTo]);

  useEffect(() => {
    const availableKeys = availableChartKeySignature ? availableChartKeySignature.split("|") : [];
    if (availableKeys.length === 0) {
      return;
    }
    setSelectedChartMetricKeys((current) => {
      const currentAvailable = current.filter((key) => availableKeys.includes(key));
      const defaultAvailable = DEFAULT_CHART_METRICS.filter((key) => availableKeys.includes(key));
      const next = currentAvailable.length > 0 ? currentAvailable : defaultAvailable;
      return arraysEqual(current, next) ? current : next;
    });
  }, [availableChartKeySignature]);

  function toggleChartMetric(metricKey: string) {
    setSelectedChartMetricKeys((current) =>
      current.includes(metricKey) ? current.filter((key) => key !== metricKey) : [...current, metricKey],
    );
  }

  function selectAllChartMetrics() {
    setSelectedChartMetricKeys(availableChartMetrics.map((metric) => metric.key));
  }

  function resetChartMetrics() {
    const defaults = DEFAULT_CHART_METRICS.filter((key) => availableChartMetrics.some((metric) => metric.key === key));
    setSelectedChartMetricKeys(defaults.length > 0 ? defaults : availableChartMetrics.slice(0, 4).map((metric) => metric.key));
  }

  return {
    latest,
    charts,
    selectedChartMetricKeys,
    setSelectedChartMetricKeys,
    showChartPoints,
    setShowChartPoints,
    chartDensity,
    setChartDensity,
    chartTimeRange,
    setChartTimeRange,
    customChartFrom,
    setCustomChartFrom,
    customChartTo,
    setCustomChartTo,
    metricSearch,
    setMetricSearch,
    expandedChartMetricKey,
    setExpandedChartMetricKey,
    selectedTelemetryEventId,
    setSelectedTelemetryEventId,
    isTelemetryLoading,
    chartTimeWindow,
    availableChartMetrics,
    chartMetrics,
    expandedChartMetric,
    loadTelemetry,
    toggleChartMetric,
    selectAllChartMetrics,
    resetChartMetrics,
    densityLimits: CHART_DENSITY_LIMITS,
  };
}
