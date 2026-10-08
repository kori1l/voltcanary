import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getLatestTelemetry, getTelemetryMetrics, getTelemetryEnvelope, LatestTelemetry, TelemetryChart, TelemetryMetricMetadata } from "../../telemetry/telemetryApi";
import { ReadLimiter } from "../../components/ReadLimiter";
import { ApiError } from "../../api/client";
import { DeviceDetailsTab } from "./DeviceTabs";
import { useSectionResource } from "../../components/useSectionResource";
import {
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
  const resource = useSectionResource<{ metrics: TelemetryMetricMetadata[]; charts: Record<string, TelemetryChart>; failures: string[] }>(`${deviceId}:${chartTimeWindow?.from}:${chartTimeWindow?.to}`);
  const latestResource = useSectionResource<LatestTelemetry | null>(deviceId ?? "none");
  const latest = latestResource.data;
  const request = useRef<AbortController | null>(null);
  const limiter = useRef(new ReadLimiter(6));
  const charts = resource.data?.charts ?? {};
  const isTelemetryLoading = resource.refreshing;
  const availableChartMetrics = useMemo(() => (resource.data?.metrics ?? []).map((metric) => ({ key: metric.name, label: metric.displayName, unit: metric.unit })), [resource.data?.metrics]);
  const chartMetrics = useMemo(
    () => availableChartMetrics.filter((metric) => selectedChartMetricKeys.includes(metric.key)),
    [availableChartMetrics, selectedChartMetricKeys],
  );
  const expandedChartMetric = availableChartMetrics.find((metric) => metric.key === expandedChartMetricKey) ?? null;

  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  async function readTelemetry(currentDeviceId = deviceId) {
    if (!mounted.current || !currentDeviceId || currentDeviceId !== deviceId || !chartTimeWindow) {
      return false;
    }

    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    const latestRead = latestResource.load(async () => {
      try {
        const response = await getLatestTelemetry(currentDeviceId, controller.signal);
        if (response.deviceId !== currentDeviceId || !Number.isFinite(Date.parse(response.timestamp)) || !response.readings || Array.isArray(response.readings) || typeof response.readings !== "object") throw new Error("Latest reading unavailable: invalid response");
        return response;
      } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; }
    }, (data) => data === null || !Object.keys(data.readings).length);
    const result = await resource.load(async () => {
      const numericMetrics = await getTelemetryMetrics(currentDeviceId, chartTimeWindow.from, chartTimeWindow.to, controller.signal);
      if (!Array.isArray(numericMetrics) || numericMetrics.some((metric) => typeof metric.name !== "string" || typeof metric.unit !== "string" || metric.type !== "number")) throw new Error("Historical metric metadata unavailable: invalid response");
      const chartResponses = await Promise.allSettled(
        numericMetrics.map((metric) => limiter.current.run(async () => {
          const chart = await getTelemetryEnvelope(currentDeviceId, metric.name, chartTimeWindow.from, chartTimeWindow.to, controller.signal);
          if (chart.deviceId !== currentDeviceId || chart.metric !== metric.name || !Array.isArray(chart.points)) throw new Error("Historical metric evidence unavailable: invalid response");
          return [metric.name, { ...chart, unit: metric.unit }] as const;
        }, controller.signal)),
      );
      const charts: Record<string, TelemetryChart> = {};
      const failures: string[] = [];
      chartResponses.forEach((response, index) => {
        if (response.status === "fulfilled") charts[response.value[0]] = response.value[1];
        else failures.push(`${numericMetrics[index].displayName}: ${response.reason instanceof Error ? response.reason.message : "unavailable"}`);
      });
      if (chartResponses.length && !Object.keys(charts).length) throw new Error(failures.join("; "));
      return { metrics: numericMetrics, charts, failures };
    }, (data) => !Object.values(data.charts).some((chart) => chart.points.length > 0));
    await latestRead;
    return !!result;
  }
  const currentLoader = useRef(readTelemetry); currentLoader.current = readTelemetry;
  const loadTelemetry = useCallback((currentDeviceId?: string) => currentLoader.current(currentDeviceId), []);

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
    return () => request.current?.abort();
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
    latestResource,
    telemetryFailures: [...(latestResource.error ? [`Latest reading: ${latestResource.error}`] : []), ...(resource.data?.failures ?? [])],
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
