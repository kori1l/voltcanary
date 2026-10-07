import { useEffect, useMemo, useState } from "react";
import { getLatestTelemetry, getTelemetryReadings, LatestTelemetry, TelemetryChart } from "../../telemetry/telemetryApi";
import { DeviceDetailsTab } from "./DeviceTabs";
import { useSectionResource } from "../../components/useSectionResource";
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
  const [selectedChartMetricKeys, setSelectedChartMetricKeys] = useState<string[]>(initialChartPreferences.selectedMetricKeys);
  const [showChartPoints, setShowChartPoints] = useState(initialChartPreferences.showChartPoints);
  const [chartDensity, setChartDensity] = useState<ChartDensity>(initialChartPreferences.chartDensity);
  const [chartTimeRange, setChartTimeRange] = useState<ChartTimeRange>(initialChartPreferences.chartTimeRange);
  const [customChartFrom, setCustomChartFrom] = useState(initialChartPreferences.customChartFrom);
  const [customChartTo, setCustomChartTo] = useState(initialChartPreferences.customChartTo);
  const [metricSearch, setMetricSearch] = useState("");
  const [expandedChartMetricKey, setExpandedChartMetricKey] = useState<string | null>(initialChartPreferences.expandedChartMetricKey);
  const [selectedTelemetryEventId, setSelectedTelemetryEventId] = useState<string | null>(null);

  const chartTimeWindow = useMemo(() => getChartTimeWindow(chartTimeRange, customChartFrom, customChartTo), [chartTimeRange, customChartFrom, customChartTo]);
  const resource = useSectionResource<{ latest: LatestTelemetry; charts: Record<string, TelemetryChart>; failures: string[] }>(`${deviceId}:${chartTimeWindow?.from}:${chartTimeWindow?.to}`);
  const latest = resource.data?.latest ?? null;
  const charts = resource.data?.charts ?? {};
  const isTelemetryLoading = resource.refreshing;
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

    const result = await resource.load(async () => {
      const latestResponse = await getLatestTelemetry(currentDeviceId);
      const numericMetrics = buildChartMetrics(latestResponse.readings);
      const chartResponses = await Promise.allSettled(
        numericMetrics.map(async (metric) => [metric.key, await getTelemetryReadings(currentDeviceId, metric.key, chartTimeWindow.from, chartTimeWindow.to)] as const),
      );
      const charts: Record<string, TelemetryChart> = {};
      const failures: string[] = [];
      chartResponses.forEach((response, index) => {
        if (response.status === "fulfilled") charts[response.value[0]] = response.value[1];
        else failures.push(`${numericMetrics[index].label}: ${response.reason instanceof Error ? response.reason.message : "unavailable"}`);
      });
      if (chartResponses.length && !Object.keys(charts).length) throw new Error(failures.join("; "));
      return { latest: latestResponse, charts, failures };
    }, (data) => !Object.values(data.charts).some((chart) => chart.points.length > 0));
    return !!result;
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
    if (from && to && Number.isFinite(Date.parse(from)) && Number.isFinite(Date.parse(to)) && Date.parse(from) < Date.parse(to)) {
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
    resource,
    telemetryFailures: resource.data?.failures ?? [],
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
