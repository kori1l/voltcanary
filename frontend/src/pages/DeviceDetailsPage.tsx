import { useEffect, useMemo, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { acknowledgeAlert, AlertResponse, listAlerts, resolveAlert } from "../alerts/alertsApi";
import {
  AnomalyResponse,
  BaselineResponse,
  getDeviceAnomalies,
  getDeviceBaseline,
  getDeviceRecommendations,
  getDeviceRisk,
  getLatestPrediction,
  PredictionResponse,
  recalculateAnalytics,
  RecommendationResponse,
  RiskResponse,
} from "../analytics/analyticsApi";
import {
  AnalyticsConfig,
  createDataSource,
  createDataSourceToken,
  createSensor,
  DataSource,
  decommissionDevice,
  deleteSensor,
  disableDataSource,
  disableDevice,
  enableDevice,
  exportDataSourcesCsv,
  getAnalyticsConfig,
  Device,
  getDevice,
  importDataSourcesCsv,
  listDataSources,
  listLocations,
  listSensors,
  Location,
  revokeDataSourceToken,
  Sensor,
  updateAnalyticsConfig,
  updateDataSource,
} from "../devices/devicesApi";
import {
  getLatestTelemetry,
  getTelemetryReadings,
  LatestTelemetry,
  TelemetryChart,
} from "../telemetry/telemetryApi";
import {
  getSimulationConfig,
  getSimulationRuns,
  getSimulationStatus,
  resetSimulationTemplate,
  saveSimulationConfig,
  SimulationConfig,
  SimulationRun,
  startSimulation,
  stopSimulation,
  validateSimulationConfig,
} from "../simulation/simulationApi";
import { TelemetryOverviewPage } from "../features/telemetry/pages/TelemetryOverviewPage";
import { mapTelemetryOverview } from "../features/telemetry/model/telemetry.mappers";
import { TelemetryOverviewResponse } from "../features/telemetry/model/telemetry.types";
import { formatMetricValue as formatTelemetryMetricValue } from "../features/telemetry/model/telemetry.utils";

type ChartPoint = { timestamp: string; value: number };
type SampledChartPoint = { point: ChartPoint; sourceIndex: number };
type ChartMetricConfig = { key: string; label: string; unit: string };
type ChartDensity = "compact" | "balanced" | "detailed";
type ChartTimeRange = "15m" | "1h" | "6h" | "24h" | "7d" | "custom";
type SimulationSettings = {
  name: string;
  sourceId: string;
  interval: string;
  duration: string;
  speed: string;
  voltageV: string;
  rssi: string;
  idlePowerW: string;
  activePowerW: string;
  activeNoiseW: string;
  onMin: string;
  onMax: string;
  offMin: string;
  offMax: string;
  powerSpikeEnabled: boolean;
  powerSpikeAfter: string;
  powerSpikeDuration: string;
  powerSpikeWatts: string;
  offlineEnabled: boolean;
  offlineAfter: string;
  offlineDuration: string;
  growthEnabled: boolean;
  growthAfter: string;
  growthPercent: string;
  growthRamp: string;
};

const CHART_METRICS: ChartMetricConfig[] = [
  { key: "activePowerW", label: "Active power", unit: "W" },
  { key: "voltageV", label: "Voltage", unit: "V" },
  { key: "currentA", label: "Current", unit: "A" },
  { key: "energyWh", label: "Energy", unit: "Wh" },
  { key: "rssi", label: "Signal strength", unit: "dBm" },
];

const CHART_METRIC_LOOKUP = new Map(CHART_METRICS.map((metric) => [metric.key, metric]));
const DEFAULT_CHART_METRICS = ["activePowerW", "voltageV", "currentA", "energyWh"];
const CHART_DENSITY_LIMITS: Record<ChartDensity, number> = {
  compact: 50,
  balanced: 90,
  detailed: 160,
};
const CHART_TIME_RANGES: { value: ChartTimeRange; label: string; durationMs?: number }[] = [
  { value: "15m", label: "15m", durationMs: 15 * 60 * 1000 },
  { value: "1h", label: "1h", durationMs: 60 * 60 * 1000 },
  { value: "6h", label: "6h", durationMs: 6 * 60 * 60 * 1000 },
  { value: "24h", label: "24h", durationMs: 24 * 60 * 60 * 1000 },
  { value: "7d", label: "7d", durationMs: 7 * 24 * 60 * 60 * 1000 },
  { value: "custom", label: "Custom" },
];

export function DeviceDetailsPage() {
  const { deviceId } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = searchParams.get("tab") ?? "overview";
  const [device, setDevice] = useState<Device | null>(null);
  const [locations, setLocations] = useState<Location[]>([]);
  const [latest, setLatest] = useState<LatestTelemetry | null>(null);
  const [charts, setCharts] = useState<Record<string, TelemetryChart>>({});
  const [selectedChartMetricKeys, setSelectedChartMetricKeys] = useState<string[]>(DEFAULT_CHART_METRICS);
  const [showChartPoints, setShowChartPoints] = useState(false);
  const [chartDensity, setChartDensity] = useState<ChartDensity>("balanced");
  const [chartTimeRange, setChartTimeRange] = useState<ChartTimeRange>("24h");
  const [customChartFrom, setCustomChartFrom] = useState(() => toDateTimeLocalValue(new Date(Date.now() - 24 * 60 * 60 * 1000)));
  const [customChartTo, setCustomChartTo] = useState(() => toDateTimeLocalValue(new Date()));
  const [metricSearch, setMetricSearch] = useState("");
  const [expandedChartMetricKey, setExpandedChartMetricKey] = useState<string | null>(null);
  const [selectedTelemetryEventId, setSelectedTelemetryEventId] = useState<string | null>(null);
  const [deviceAlertStatusFilter, setDeviceAlertStatusFilter] = useState("active");
  const [showAllDeviceAlerts, setShowAllDeviceAlerts] = useState(false);
  const [isTelemetryLoading, setIsTelemetryLoading] = useState(false);
  const [baseline, setBaseline] = useState<BaselineResponse | null>(null);
  const [risk, setRisk] = useState<RiskResponse | null>(null);
  const [anomalies, setAnomalies] = useState<AnomalyResponse[]>([]);
  const [deviceAlerts, setDeviceAlerts] = useState<AlertResponse[]>([]);
  const [recommendations, setRecommendations] = useState<RecommendationResponse[]>([]);
  const [prediction, setPrediction] = useState<PredictionResponse | null>(null);
  const [dataSources, setDataSources] = useState<DataSource[]>([]);
  const [sensors, setSensors] = useState<Sensor[]>([]);
  const [analyticsConfig, setAnalyticsConfig] = useState<AnalyticsConfig | null>(null);
  const [analyticsConfigText, setAnalyticsConfigText] = useState("{\n  \"baselineWindowDays\": 14\n}");
  const [dataSourceExternalId, setDataSourceExternalId] = useState("");
  const [dataSourceType, setDataSourceType] = useState("SMART_PLUG");
  const [dataSourceProtocol, setDataSourceProtocol] = useState("REST");
  const [sourceCsvText, setSourceCsvText] = useState("externalId,sourceType,protocol\n");
  const [sourceCsvMessage, setSourceCsvMessage] = useState<string | null>(null);
  const [lastSourceToken, setLastSourceToken] = useState<string | null>(null);
  const [sensorName, setSensorName] = useState("");
  const [sensorType, setSensorType] = useState("");
  const [sensorUnit, setSensorUnit] = useState("");
  const [sensorMetricMapping, setSensorMetricMapping] = useState("");
  const [sensorMetadataText, setSensorMetadataText] = useState("{}");
  const [isLoading, setIsLoading] = useState(true);
  const [isRegistryBusy, setIsRegistryBusy] = useState(false);
  const [isRecalculating, setIsRecalculating] = useState(false);
  const [isChecked, setIsChecked] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [simulationConfig, setSimulationConfig] = useState<SimulationConfig | null>(null);
  const [simulationStatus, setSimulationStatus] = useState<SimulationRun | null>(null);
  const [simulationRuns, setSimulationRuns] = useState<SimulationRun[]>([]);
  const [simulationYaml, setSimulationYaml] = useState("");
  const [simulationProfile, setSimulationProfile] = useState("FRIDGE");
  const [simulationProtocol, setSimulationProtocol] = useState("HTTP");
  const [simulationMessage, setSimulationMessage] = useState<string | null>(null);
  const [simulationValidation, setSimulationValidation] = useState<{ valid: boolean; message: string }>({ valid: true, message: "YAML is valid" });
  const [isSimulationBusy, setIsSimulationBusy] = useState(false);

  async function loadAnalytics(currentDeviceId: string) {
    const [baselineResponse, riskResponse, anomaliesResponse, recommendationsResponse, predictionResponse] = await Promise.all([
      getDeviceBaseline(currentDeviceId).catch(() => null),
      getDeviceRisk(currentDeviceId).catch(() => null),
      getDeviceAnomalies(currentDeviceId).catch(() => []),
      getDeviceRecommendations(currentDeviceId).catch(() => []),
      getLatestPrediction(currentDeviceId).catch(() => null),
    ]);
    setBaseline(baselineResponse);
    setRisk(riskResponse);
    setAnomalies(anomaliesResponse);
    setRecommendations(recommendationsResponse);
    setPrediction(predictionResponse);
    return riskResponse;
  }

  async function loadTelemetry(currentDeviceId: string) {
    const timeWindow = getChartTimeWindow(chartTimeRange, customChartFrom, customChartTo);
    if (!timeWindow) {
      return false;
    }

    setIsTelemetryLoading(true);
    try {
      const latestResponse = await getLatestTelemetry(currentDeviceId);
      setLatest(latestResponse);
      const numericMetrics = buildChartMetrics(latestResponse.readings);
      const chartResponses = await Promise.all(
        numericMetrics.map(async (metric) => [metric.key, await getTelemetryReadings(currentDeviceId, metric.key, timeWindow.from, timeWindow.to)] as const),
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

  async function load() {
    if (!deviceId) {
      return;
    }
    setIsLoading(true);
    setError(null);
    try {
      const [deviceResponse, locationsResponse] = await Promise.all([getDevice(deviceId), listLocations().catch(() => [])]);
      setDevice(deviceResponse);
      setLocations(locationsResponse);
      setDeviceAlerts((await listAlerts().catch(() => [])).filter((alert) => alert.deviceId === deviceId));
      const hasTelemetry = await loadTelemetry(deviceId);
      const loadedRisk = await loadAnalytics(deviceId);
      if (hasTelemetry && !loadedRisk) {
        await recalculateAnalytics(deviceId).catch(() => undefined);
        await loadAnalytics(deviceId);
      }
      await loadRegistry(deviceId);
      await loadSimulation(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load device");
    } finally {
      setIsLoading(false);
    }
  }

  async function loadRegistry(currentDeviceId: string) {
    const [sourcesResponse, sensorsResponse, analyticsConfigResponse] = await Promise.all([
      listDataSources(currentDeviceId).catch(() => []),
      listSensors(currentDeviceId).catch(() => []),
      getAnalyticsConfig(currentDeviceId).catch(() => null),
    ]);
    setDataSources(sourcesResponse);
    setSensors(sensorsResponse);
    setAnalyticsConfig(analyticsConfigResponse);
    if (analyticsConfigResponse) {
      setAnalyticsConfigText(JSON.stringify(analyticsConfigResponse.config ?? {}, null, 2));
    }
  }

  async function loadSimulation(currentDeviceId: string) {
    try {
      const [configResponse, statusResponse, runsResponse] = await Promise.all([
        getSimulationConfig(currentDeviceId),
        getSimulationStatus(currentDeviceId).catch(() => null),
        getSimulationRuns(currentDeviceId).catch(() => []),
      ]);
      setSimulationConfig(configResponse);
      setSimulationYaml(configResponse.yamlConfig);
      setSimulationProfile(configResponse.profile);
      setSimulationProtocol(configResponse.protocol);
      setSimulationStatus(statusResponse);
      setSimulationRuns(runsResponse);
    } catch {
      setSimulationConfig(null);
      setSimulationYaml("");
      setSimulationStatus(null);
      setSimulationRuns([]);
      setSimulationProfile("FRIDGE");
      setSimulationProtocol("HTTP");
    }
  }

  useEffect(() => {
    void load();
  }, [deviceId]);

  useEffect(() => {
    if (!deviceId) {
      return;
    }
    void loadTelemetry(deviceId);
  }, [chartTimeRange, customChartFrom, customChartTo]);

  useEffect(() => {
    if (!deviceId || !simulationConfig || !simulationYaml) {
      return;
    }
    let cancelled = false;
    const timeoutId = window.setTimeout(() => {
      validateSimulationConfig(deviceId, simulationYaml)
        .then((result) => {
          if (cancelled) {
            return;
          }
          setSimulationValidation({
            valid: result.valid,
            message: result.valid ? "YAML is valid" : result.errors.map((item) => `${item.path}: ${item.message}`).join("; "),
          });
        })
        .catch((requestError) => {
          if (cancelled) {
            return;
          }
          setSimulationValidation({
            valid: false,
            message: requestError instanceof Error ? requestError.message : "YAML validation failed",
          });
        });
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timeoutId);
    };
  }, [deviceId, simulationConfig, simulationYaml]);

  const readings = latest?.readings ?? {};
  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, locationLabel(location)])), [locations]);
  const locationName = device ? locationById.get(device.locationId ?? "") ?? "No location" : "No location";
  const riskInsight = getRiskInsight(risk, anomalies, recommendations);
  const latestAnomaly = anomalies[0] ?? null;
  const sourceId = deviceId ? localStorage.getItem(`voltcanary.deviceSource.${deviceId}`) ?? "plug-001" : "plug-001";
  const availableChartMetrics = Object.keys(charts)
    .map((key) => CHART_METRIC_LOOKUP.get(key) ?? createMetricConfig(key))
    .filter((metric) => charts[metric.key]?.points.length > 0)
    .sort((first, second) => metricSortWeight(first.key) - metricSortWeight(second.key) || first.label.localeCompare(second.label));
  const availableChartKeySignature = availableChartMetrics.map((metric) => metric.key).join("|");
  const chartMetrics = availableChartMetrics.filter((metric) => selectedChartMetricKeys.includes(metric.key));
  const filteredAvailableChartMetrics = availableChartMetrics.filter((metric) => {
    const query = metricSearch.trim().toLowerCase();
    return !query || metric.key.toLowerCase().includes(query) || metric.label.toLowerCase().includes(query);
  });
  const expandedChartMetric = availableChartMetrics.find((metric) => metric.key === expandedChartMetricKey) ?? null;
  const simulationSettings = useMemo(() => readSimulationSettings(simulationYaml), [simulationYaml]);
  const chartTimeWindow = getChartTimeWindow(chartTimeRange, customChartFrom, customChartTo);
  const telemetryOverview = useMemo(() => {
    if (!deviceId || !chartTimeWindow) {
      return null;
    }
    return mapTelemetryOverview({
      deviceId,
      charts,
      latestTimestamp: latest?.timestamp ?? null,
      selectedMetricKeys: selectedChartMetricKeys,
      range: {
        from: chartTimeWindow.from,
        to: chartTimeWindow.to,
        preset: chartTimeRange,
      },
      baseline,
      anomalies,
    });
  }, [deviceId, charts, latest?.timestamp, selectedChartMetricKeys, chartTimeRange, chartTimeWindow?.from, chartTimeWindow?.to, baseline, anomalies]);
  const incident = telemetryOverview ? buildIncidentSummary(telemetryOverview, risk?.level ?? null, chartTimeRange) : null;
  const whyBullets = telemetryOverview ? buildWhyBullets(telemetryOverview, riskInsight.reasons) : riskInsight.reasons.slice(0, 4);
  const analyticsSummary = telemetryOverview ? buildAnalyticsSummary(telemetryOverview) : null;
  const predictionInsight = analyticsSummary ? buildPredictionInsight(prediction, analyticsSummary) : null;
  const analyticsFocusedMetric = expandedChartMetric ?? chartMetrics[0] ?? availableChartMetrics[0] ?? null;
  const analyticsFocusedOverview = telemetryOverview?.metrics.find((metric) => metric.definition.key === analyticsFocusedMetric?.key) ?? null;
  const analyticsFocusedEvents = telemetryOverview?.events.filter((event) => !analyticsFocusedMetric || event.metricKey === analyticsFocusedMetric.key) ?? [];
  const analyticsFocusedPoints = analyticsFocusedMetric ? charts[analyticsFocusedMetric.key]?.points ?? [] : [];
  const analyticsBaseline = analyticsFocusedMetric && baseline?.metric === analyticsFocusedMetric.key ? baseline : null;
  const filteredDeviceAlerts = deviceAlerts
    .filter((alert) => {
      if (deviceAlertStatusFilter === "active") return alert.status !== "RESOLVED";
      return deviceAlertStatusFilter === "all" || alert.status === deviceAlertStatusFilter;
    })
    .sort((left, right) => severityRank(right.severity) - severityRank(left.severity) || new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime());
  const visibleDeviceAlerts = showAllDeviceAlerts ? filteredDeviceAlerts : filteredDeviceAlerts.slice(0, 12);

  useEffect(() => {
    const availableKeys = availableChartKeySignature ? availableChartKeySignature.split("|") : [];
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

  async function recalculateDeviceAnalytics() {
    if (!deviceId) {
      return;
    }
    setIsRecalculating(true);
    setError(null);
    try {
      await recalculateAnalytics(deviceId);
      await loadAnalytics(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to recalculate risk");
    } finally {
      setIsRecalculating(false);
    }
  }

  async function updateDeviceAlert(alertId: string, action: "ack" | "resolve") {
    setError(null);
    try {
      if (action === "ack") {
        await acknowledgeAlert(alertId);
      } else {
        const reason = window.prompt("Resolution reason");
        if (reason === null) {
          return;
        }
        await resolveAlert(alertId, reason);
      }
      if (deviceId) {
        setDeviceAlerts((await listAlerts().catch(() => [])).filter((alert) => alert.deviceId === deviceId));
      }
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to update alert");
    }
  }

  async function validateYaml() {
    if (!deviceId) return;
    setIsSimulationBusy(true);
    setSimulationMessage(null);
    try {
      const result = await validateSimulationConfig(deviceId, simulationYaml);
      const message = result.valid ? "YAML is valid" : result.errors.map((item) => `${item.path}: ${item.message}`).join("; ");
      setSimulationValidation({ valid: result.valid, message });
      setSimulationMessage(message);
    } finally {
      setIsSimulationBusy(false);
    }
  }

  async function saveYaml() {
    if (!deviceId || !simulationConfig) return;
    setIsSimulationBusy(true);
    setSimulationMessage(null);
    try {
      const saved = await saveSimulationConfig(deviceId, {
        dataSourceId: simulationConfig.dataSourceId,
        name: simulationConfig.name,
        profile: simulationProfile,
        protocol: simulationProtocol,
        yamlConfig: simulationYaml.replace(/protocol:\s*(HTTP|MQTT)/, `protocol: ${simulationProtocol}`),
      });
      await updateDataSource(deviceId, simulationConfig.dataSourceId, {
        sourceType: "SIMULATOR",
        protocol: simulationProtocol === "MQTT" ? "MQTT" : "REST",
        externalId: sourceId,
      }).catch(() => undefined);
      setSimulationConfig(saved);
      setSimulationProfile(saved.profile);
      setSimulationProtocol(saved.protocol);
      setSimulationMessage("Config saved");
    } finally {
      setIsSimulationBusy(false);
    }
  }

  function updateSimulationField(field: keyof SimulationSettings, value: string | boolean) {
    setSimulationYaml((current) => updateSimulationYaml(current, field, value));
  }

  function changeSimulationProfile(profile: string) {
    setSimulationProfile(profile);
    setSimulationYaml((current) => updateSimulationYaml(current, "profile", profile));
  }

  function changeSimulationProtocol(protocol: string) {
    setSimulationProtocol(protocol);
    setSimulationYaml((current) => updateSimulationYaml(current, "protocol", protocol));
  }

  async function toggleSimulation() {
    if (!deviceId) return;
    setIsSimulationBusy(true);
    setSimulationMessage(null);
    try {
      if (simulationStatus?.status === "RUNNING") {
        setSimulationStatus(await stopSimulation(deviceId));
      } else {
        setSimulationStatus(await startSimulation(deviceId));
      }
      await loadSimulation(deviceId);
    } catch (requestError) {
      setSimulationMessage(requestError instanceof Error ? requestError.message : "Simulation action failed");
    } finally {
      setIsSimulationBusy(false);
    }
  }

  async function resetYamlTemplate() {
    if (!deviceId || !simulationConfig) return;
    setIsSimulationBusy(true);
    setSimulationMessage(null);
    try {
      const template = await resetSimulationTemplate(deviceId, simulationProfile, sourceId);
      setSimulationYaml(template.yamlConfig.replace(/protocol:\s*(HTTP|MQTT)/, `protocol: ${simulationProtocol}`));
      setSimulationMessage("Template loaded");
    } finally {
      setIsSimulationBusy(false);
    }
  }

  async function changeDeviceStatus(action: "enable" | "disable" | "decommission") {
    if (!deviceId) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      const nextDevice =
        action === "enable"
          ? await enableDevice(deviceId)
          : action === "disable"
            ? await disableDevice(deviceId)
            : await decommissionDevice(deviceId);
      setDevice(nextDevice);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to update device status");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function addDataSource() {
    if (!deviceId) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      await createDataSource(deviceId, {
        externalId: dataSourceExternalId,
        sourceType: dataSourceType,
        protocol: dataSourceProtocol,
      });
      setDataSourceExternalId("");
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create data source");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function rotateSourceToken(dataSourceId: string) {
    if (!deviceId) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      const response = await createDataSourceToken(deviceId, dataSourceId);
      setLastSourceToken(response.token);
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to rotate source token");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function revokeSourceToken(dataSourceId: string) {
    if (!deviceId) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      await revokeDataSourceToken(deviceId, dataSourceId);
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to revoke source token");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function disableSource(dataSourceId: string) {
    if (!deviceId) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      await disableDataSource(deviceId, dataSourceId);
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to disable data source");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function importSourcesCsv() {
    if (!deviceId) return;
    setIsRegistryBusy(true);
    setSourceCsvMessage(null);
    setError(null);
    try {
      const response = await importDataSourcesCsv(deviceId, sourceCsvText);
      setSourceCsvMessage(
        response.errors.length > 0
          ? `Imported ${response.importedCount}; ${response.errors.join("; ")}`
          : `Imported ${response.importedCount} data sources`,
      );
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to import data sources");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function exportSourcesCsv() {
    if (!deviceId) return;
    setError(null);
    try {
      downloadText(await exportDataSourcesCsv(deviceId), `device-${deviceId}-sources.csv`, "text/csv");
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to export data sources");
    }
  }

  async function addSensor() {
    if (!deviceId) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      await createSensor(deviceId, {
        name: sensorName,
        type: sensorType,
        unit: sensorUnit || null,
        metricMapping: sensorMetricMapping || null,
        metadata: parseJsonObject(sensorMetadataText),
      });
      setSensorName("");
      setSensorType("");
      setSensorUnit("");
      setSensorMetricMapping("");
      setSensorMetadataText("{}");
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create sensor");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function removeSensor(sensorId: string) {
    if (!deviceId) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      await deleteSensor(deviceId, sensorId);
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to delete sensor");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function saveAnalyticsConfig() {
    if (!deviceId || !analyticsConfig) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      const saved = await updateAnalyticsConfig(deviceId, {
        enabled: analyticsConfig.enabled,
        config: parseJsonObject(analyticsConfigText) ?? {},
      });
      setAnalyticsConfig(saved);
      setAnalyticsConfigText(JSON.stringify(saved.config ?? {}, null, 2));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to save analytics config");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  return (
    <>
      <header className="page-header page-header-row">
        <div>
          <Link className="back-link" to="/devices">
            Devices
          </Link>
          <h1>{device?.name ?? "Device details"}</h1>
          <p>
            {device
              ? `${locationName} · ${formatEnum(device.status)} · Last telemetry: ${latest ? formatShortTime(latest.timestamp) : "No data"}`
              : "Loading device status"}
          </p>
        </div>
        <div className="header-actions">
          <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
            Refresh device
          </button>
          {device?.status === "DISABLED" ? (
            <button className="ghost-button" type="button" onClick={() => void changeDeviceStatus("enable")} disabled={isRegistryBusy}>
              Enable
            </button>
          ) : null}
          {device && device.status !== "DISABLED" && device.status !== "DECOMMISSIONED" ? (
            <button className="ghost-button" type="button" onClick={() => void changeDeviceStatus("disable")} disabled={isRegistryBusy}>
              Disable
            </button>
          ) : null}
          {device && device.status !== "DECOMMISSIONED" ? (
            <button className="ghost-button" type="button" onClick={() => void changeDeviceStatus("decommission")} disabled={isRegistryBusy}>
              Decommission
            </button>
          ) : null}
          <button
            className="primary-button"
            type="button"
            onClick={() => void recalculateDeviceAnalytics()}
            disabled={isRecalculating || isLoading}
          >
            {isRecalculating ? "Recalculating..." : "Recalculate risk"}
          </button>
        </div>
      </header>

      {error ? <div className="error-message">{error}</div> : null}
      {isLoading ? <section className="panel">Loading device...</section> : null}

      {!isLoading && device ? (
        <>
          {activeTab === "overview" && incident ? (
            <>
              <section className={`incident-summary incident-summary-${incident.riskTone}`}>
                <div>
                  <div className="incident-title-row">
                    <span className="status-pill">{incident.riskLabel}</span>
                    <h2>{incident.title}</h2>
                  </div>
                  <p className="incident-metrics">
                    {incident.eventCount} events in {incident.timeRangeLabel} - Peak {incident.peakValue} - Current value {incident.currentValue}
                  </p>
                  <p className="incident-conclusion">{incident.conclusion}</p>
                </div>
                <div className="action-row">
                  <Link className="ghost-button button-link" to="/alerts">
                    View alerts
                  </Link>
                  <button className="primary-button" type="button" onClick={() => setIsChecked(true)}>
                    {isChecked ? "Checked" : "Mark as checked"}
                  </button>
                </div>
              </section>

              <section className="metric-grid device-overview-grid compact-kpi-row">
                <Metric label={incident.problemMetricName} valueText={incident.currentValue} emphasis />
                <Metric label="Risk score" valueText={risk ? `${Math.round(risk.score)} / 100 - ${formatEnum(risk.level)}` : "No score"} />
                <Metric label="Last activity" valueText={latest ? formatShortTime(latest.timestamp) : "No telemetry"} />
                <Metric label="Samples analyzed" value={incident.sampleCount} unit="" />
              </section>
            </>
          ) : null}

          <nav className="tab-row" aria-label="Device sections">
            {["overview", "telemetry", "registry", "analytics", "alerts", "simulation"].map((tab) => (
              <button
                key={tab}
                className={activeTab === tab ? "tab-button tab-button-active" : "tab-button"}
                type="button"
                onClick={() => setSearchParams(tab === "overview" ? {} : { tab })}
              >
                {formatEnum(tab)}
              </button>
            ))}
          </nav>

          {activeTab === "simulation" ? (
            <section className="panel simulation-panel">
              <div className="section-header">
                <div>
                  <h2>Simulation</h2>
                  <p>
                    {simulationConfig
                      ? `${formatEnum(simulationConfig.profile)} - ${formatEnum(simulationConfig.status)} - Version ${simulationConfig.yamlVersion}`
                      : "No simulation config exists for this device."}
                  </p>
                </div>
                <div className="action-row">
                  <button className="ghost-button" type="button" onClick={() => void validateYaml()} disabled={!simulationConfig || isSimulationBusy}>
                    Validate YAML
                  </button>
                  <button className="ghost-button" type="button" onClick={() => void saveYaml()} disabled={!simulationConfig || isSimulationBusy || !simulationValidation.valid}>
                    Save Config
                  </button>
                  <button className="ghost-button" type="button" onClick={() => void resetYamlTemplate()} disabled={!simulationConfig || isSimulationBusy}>
                    Reset Template
                  </button>
                  <button className="primary-button" type="button" onClick={() => void toggleSimulation()} disabled={!simulationConfig || isSimulationBusy || !simulationValidation.valid}>
                    {simulationStatus?.status === "RUNNING" ? "Stop" : "Start"}
                  </button>
                </div>
              </div>

              {simulationMessage ? <div className="status-note">{simulationMessage}</div> : null}
              {simulationConfig ? (
                <div className={simulationValidation.valid ? "status-note validation-note validation-note-ok" : "status-note validation-note validation-note-error"}>
                  {simulationValidation.message}
                </div>
              ) : null}

              {simulationConfig ? (
                <>
                  <section className="metric-grid">
                    <Metric label="Status" valueText={formatEnum(simulationStatus?.status ?? "STOPPED")} />
                    <Metric label="Protocol" valueText={simulationConfig.protocol} />
                    <Metric label="Generated points" value={simulationStatus?.generatedPoints ?? 0} unit="" />
                    <Metric label="Started" valueText={simulationStatus?.startedAt ? formatShortDateTime(simulationStatus.startedAt) : "Not running"} />
                  </section>
                  <div className="simulation-control-grid">
                    <label>
                      Profile
                      <select value={simulationProfile} onChange={(event) => changeSimulationProfile(event.target.value)}>
                        {["GENERIC_APPLIANCE", "FRIDGE", "AIR_CONDITIONER", "BOILER", "PUMP", "HEATER"].map((profile) => (
                          <option value={profile} key={profile}>
                            {formatEnum(profile)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Protocol
                      <select value={simulationProtocol} onChange={(event) => changeSimulationProtocol(event.target.value)}>
                        <option value="HTTP">HTTP</option>
                        <option value="MQTT">MQTT</option>
                      </select>
                    </label>
                  </div>

                  <section className="sim-config-form" aria-label="Simulation settings">
                    <div className="sim-form-section">
                      <h3>Device signal</h3>
                      <div className="sim-form-grid">
                        <label>
                          Simulator name
                          <input value={simulationSettings.name} onChange={(event) => updateSimulationField("name", event.target.value)} />
                        </label>
                        <label>
                          Source ID
                          <input value={simulationSettings.sourceId} onChange={(event) => updateSimulationField("sourceId", event.target.value)} />
                        </label>
                        <label>
                          Interval
                          <DurationInput value={simulationSettings.interval} onChange={(value) => updateSimulationField("interval", value)} />
                        </label>
                        <label>
                          Duration
                          <DurationInput value={simulationSettings.duration} onChange={(value) => updateSimulationField("duration", value)} />
                        </label>
                        <label>
                          Speed
                          <input type="number" step="0.1" value={simulationSettings.speed} onChange={(event) => updateSimulationField("speed", event.target.value)} />
                        </label>
                      </div>
                    </div>

                    <div className="sim-form-section">
                      <h3>Normal behavior</h3>
                      <div className="sim-form-grid">
                        <label>
                          Voltage
                          <input type="number" step="0.1" value={simulationSettings.voltageV} onChange={(event) => updateSimulationField("voltageV", event.target.value)} />
                        </label>
                        <label>
                          Signal RSSI
                          <input type="number" step="1" value={simulationSettings.rssi} onChange={(event) => updateSimulationField("rssi", event.target.value)} />
                        </label>
                        <label>
                          Idle power
                          <input type="number" step="0.1" value={simulationSettings.idlePowerW} onChange={(event) => updateSimulationField("idlePowerW", event.target.value)} />
                        </label>
                        <label>
                          Active power
                          <input type="number" step="0.1" value={simulationSettings.activePowerW} onChange={(event) => updateSimulationField("activePowerW", event.target.value)} />
                        </label>
                        <label>
                          Active noise
                          <input type="number" step="0.1" value={simulationSettings.activeNoiseW} onChange={(event) => updateSimulationField("activeNoiseW", event.target.value)} />
                        </label>
                      </div>
                    </div>

                    <div className="sim-form-section">
                      <h3>Cycle timing</h3>
                      <div className="sim-form-grid">
                        <label>
                          On min
                          <DurationInput value={simulationSettings.onMin} onChange={(value) => updateSimulationField("onMin", value)} />
                        </label>
                        <label>
                          On max
                          <DurationInput value={simulationSettings.onMax} onChange={(value) => updateSimulationField("onMax", value)} />
                        </label>
                        <label>
                          Off min
                          <DurationInput value={simulationSettings.offMin} onChange={(value) => updateSimulationField("offMin", value)} />
                        </label>
                        <label>
                          Off max
                          <DurationInput value={simulationSettings.offMax} onChange={(value) => updateSimulationField("offMax", value)} />
                        </label>
                      </div>
                    </div>

                    <div className="sim-form-section sim-anomaly-section">
                      <h3>Anomalies</h3>
                      <div className="sim-anomaly-grid">
                        <article>
                          <label className="sim-toggle">
                            <input type="checkbox" checked={simulationSettings.powerSpikeEnabled} onChange={(event) => updateSimulationField("powerSpikeEnabled", event.target.checked)} />
                            Power spike
                          </label>
                          <label>
                            Start after
                            <DurationInput value={simulationSettings.powerSpikeAfter} onChange={(value) => updateSimulationField("powerSpikeAfter", value)} />
                          </label>
                          <label>
                            Duration
                            <DurationInput value={simulationSettings.powerSpikeDuration} onChange={(value) => updateSimulationField("powerSpikeDuration", value)} />
                          </label>
                          <label>
                            Spike power
                            <input type="number" value={simulationSettings.powerSpikeWatts} onChange={(event) => updateSimulationField("powerSpikeWatts", event.target.value)} />
                          </label>
                        </article>
                        <article>
                          <label className="sim-toggle">
                            <input type="checkbox" checked={simulationSettings.offlineEnabled} onChange={(event) => updateSimulationField("offlineEnabled", event.target.checked)} />
                            Offline window
                          </label>
                          <label>
                            Start after
                            <DurationInput value={simulationSettings.offlineAfter} onChange={(value) => updateSimulationField("offlineAfter", value)} />
                          </label>
                          <label>
                            Duration
                            <DurationInput value={simulationSettings.offlineDuration} onChange={(value) => updateSimulationField("offlineDuration", value)} />
                          </label>
                        </article>
                        <article>
                          <label className="sim-toggle">
                            <input type="checkbox" checked={simulationSettings.growthEnabled} onChange={(event) => updateSimulationField("growthEnabled", event.target.checked)} />
                            Consumption growth
                          </label>
                          <label>
                            Start after
                            <DurationInput value={simulationSettings.growthAfter} onChange={(value) => updateSimulationField("growthAfter", value)} />
                          </label>
                          <label>
                            Growth percent
                            <input type="number" value={simulationSettings.growthPercent} onChange={(event) => updateSimulationField("growthPercent", event.target.value)} />
                          </label>
                          <label>
                            Ramp duration
                            <DurationInput value={simulationSettings.growthRamp} onChange={(value) => updateSimulationField("growthRamp", value)} />
                          </label>
                        </article>
                      </div>
                    </div>
                  </section>

                  <details className="advanced-yaml">
                    <summary>Advanced YAML</summary>
                    <textarea
                      className="yaml-editor"
                      value={simulationYaml}
                      onChange={(event) => setSimulationYaml(event.target.value)}
                      spellCheck={false}
                    />
                  </details>
                  <div className="run-history">
                    <h3>Run history</h3>
                    {simulationRuns.length > 0 ? (
                      <ul className="plain-list compact-list">
                        {simulationRuns.map((run) => (
                          <li key={run.runId ?? `${run.startedAt}-${run.status}`}>
                            <strong>{formatEnum(run.status)}</strong>
                            <span>
                              {run.generatedPoints} points - {run.startedAt ? formatShortDateTime(run.startedAt) : "No start time"}
                            </span>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p className="muted-text">No runs yet.</p>
                    )}
                  </div>
                </>
              ) : (
                <div className="empty-state">
                  <strong>No simulator attached</strong>
                  <span>Create a device with data source Simulator to manage runs here.</span>
                </div>
              )}
            </section>
          ) : null}

          {activeTab === "registry" ? (
            <section className="registry-grid">
              <section className="panel">
                <div className="section-header">
                  <div>
                    <h2>Data sources</h2>
                    <p>Manage ingestion identities and source credentials.</p>
                  </div>
                  <button className="ghost-button" type="button" onClick={() => void exportSourcesCsv()}>
                    Export CSV
                  </button>
                </div>
                {lastSourceToken ? (
                  <div className="success-message">
                    New source token: <code>{lastSourceToken}</code>
                  </div>
                ) : null}
                <div className="form-stack registry-form">
                  <div className="form-grid-three">
                    <label>
                      External ID
                      <input value={dataSourceExternalId} onChange={(event) => setDataSourceExternalId(event.target.value)} placeholder="plug-001" />
                    </label>
                    <label>
                      Source type
                      <select value={dataSourceType} onChange={(event) => setDataSourceType(event.target.value)}>
                        {["SMART_PLUG", "SENSOR", "SIMULATOR", "MQTT", "API", "CSV", "CUSTOM"].map((value) => (
                          <option value={value} key={value}>
                            {formatEnum(value)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Protocol
                      <select value={dataSourceProtocol} onChange={(event) => setDataSourceProtocol(event.target.value)}>
                        {["REST", "MQTT", "CSV", "HTTP_POLLING", "WEBHOOK", "VENDOR_API"].map((value) => (
                          <option value={value} key={value}>
                            {formatEnum(value)}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                  <button className="primary-button" type="button" onClick={() => void addDataSource()} disabled={isRegistryBusy || !dataSourceExternalId.trim()}>
                    Add source
                  </button>
                </div>
                <div className="registry-list">
                  {dataSources.map((source) => (
                    <article className="registry-row" key={source.id}>
                      <div>
                        <strong>{source.externalId}</strong>
                        <span>{formatEnum(source.sourceType)} - {formatEnum(source.protocol)}</span>
                      </div>
                      <div className="action-row">
                        <button className="ghost-button" type="button" onClick={() => void rotateSourceToken(source.id)} disabled={isRegistryBusy}>
                          Rotate token
                        </button>
                        <button className="ghost-button" type="button" onClick={() => void revokeSourceToken(source.id)} disabled={isRegistryBusy}>
                          Revoke
                        </button>
                        <button className="ghost-button" type="button" onClick={() => void disableSource(source.id)} disabled={isRegistryBusy}>
                          Disable
                        </button>
                      </div>
                    </article>
                  ))}
                  {dataSources.length === 0 ? <div className="empty-state">No data sources configured.</div> : null}
                </div>
                <details className="advanced-yaml">
                  <summary>Import data sources CSV</summary>
                  <div className="form-stack">
                    <p className="muted-text">Header: externalId,sourceType,protocol</p>
                    <textarea className="csv-textarea" value={sourceCsvText} onChange={(event) => setSourceCsvText(event.target.value)} />
                    {sourceCsvMessage ? <div className="success-message">{sourceCsvMessage}</div> : null}
                    <button className="primary-button" type="button" onClick={() => void importSourcesCsv()} disabled={isRegistryBusy}>
                      Import sources
                    </button>
                  </div>
                </details>
              </section>

              <section className="panel">
                <div className="section-header">
                  <div>
                    <h2>Sensors</h2>
                    <p>Map physical sensors to telemetry metrics.</p>
                  </div>
                </div>
                <div className="form-stack registry-form">
                  <div className="form-grid-two">
                    <label>
                      Name
                      <input value={sensorName} onChange={(event) => setSensorName(event.target.value)} placeholder="Main power clamp" />
                    </label>
                    <label>
                      Type
                      <input value={sensorType} onChange={(event) => setSensorType(event.target.value)} placeholder="power" />
                    </label>
                    <label>
                      Unit
                      <input value={sensorUnit} onChange={(event) => setSensorUnit(event.target.value)} placeholder="W" />
                    </label>
                    <label>
                      Metric mapping
                      <input value={sensorMetricMapping} onChange={(event) => setSensorMetricMapping(event.target.value)} placeholder="activePowerW" />
                    </label>
                  </div>
                  <label>
                    Metadata JSON
                    <textarea className="small-textarea" value={sensorMetadataText} onChange={(event) => setSensorMetadataText(event.target.value)} />
                  </label>
                  <button className="primary-button" type="button" onClick={() => void addSensor()} disabled={isRegistryBusy || !sensorName.trim() || !sensorType.trim()}>
                    Add sensor
                  </button>
                </div>
                <div className="registry-list">
                  {sensors.map((sensor) => (
                    <article className="registry-row" key={sensor.id}>
                      <div>
                        <strong>{sensor.name}</strong>
                        <span>{sensor.type} - {sensor.metricMapping ?? "No metric"} {sensor.unit ? `(${sensor.unit})` : ""}</span>
                      </div>
                      <button className="ghost-button" type="button" onClick={() => void removeSensor(sensor.id)} disabled={isRegistryBusy}>
                        Delete
                      </button>
                    </article>
                  ))}
                  {sensors.length === 0 ? <div className="empty-state">No sensors configured.</div> : null}
                </div>
              </section>

              <section className="panel">
                <div className="section-header">
                  <div>
                    <h2>Analytics config</h2>
                    <p>Device-specific analytics settings.</p>
                  </div>
                  <label className="inline-toggle">
                    <input
                      type="checkbox"
                      checked={analyticsConfig?.enabled ?? true}
                      onChange={(event) => setAnalyticsConfig((current) => ({ enabled: event.target.checked, config: current?.config ?? {} }))}
                    />
                    Enabled
                  </label>
                </div>
                <textarea className="csv-textarea" value={analyticsConfigText} onChange={(event) => setAnalyticsConfigText(event.target.value)} />
                <div className="action-row action-row-start">
                  <button className="primary-button" type="button" onClick={() => void saveAnalyticsConfig()} disabled={isRegistryBusy || !analyticsConfig}>
                    Save analytics config
                  </button>
                </div>
              </section>
            </section>
          ) : null}

          {activeTab === "overview" && telemetryOverview ? (
            <section className="monitoring-dashboard-grid">
              <div className="monitoring-main-column">
                <TelemetryOverviewPage
                  overview={telemetryOverview}
                  range={chartTimeRange}
                  customFrom={customChartFrom}
                  customTo={customChartTo}
                  density={chartDensity}
                  showPoints={showChartPoints}
                  selectedMetricKeys={selectedChartMetricKeys}
                  metricSearch={metricSearch}
                  openedMetricKey={expandedChartMetricKey}
                  selectedEventId={selectedTelemetryEventId}
                  maxDisplayPoints={CHART_DENSITY_LIMITS[chartDensity]}
                  onRangeChange={setChartTimeRange}
                  onCustomFromChange={setCustomChartFrom}
                  onCustomToChange={setCustomChartTo}
                  onDensityChange={setChartDensity}
                  onShowPointsChange={setShowChartPoints}
                  onMetricSearchChange={setMetricSearch}
                  onToggleMetric={toggleChartMetric}
                  onSelectAll={selectAllChartMetrics}
                  onResetMetrics={resetChartMetrics}
                  onClearMetrics={() => setSelectedChartMetricKeys([])}
                  onOpenMetric={setExpandedChartMetricKey}
                  onCloseMetric={() => setExpandedChartMetricKey(null)}
                  onSelectEvent={(eventId) => {
                    setSelectedTelemetryEventId(eventId);
                    const event = telemetryOverview.events.find((item) => item.id === eventId);
                    if (event?.metricKey) {
                      setExpandedChartMetricKey(event.metricKey);
                    }
                  }}
                />
              </div>
              <aside className="monitoring-side-column">
                <section className="panel compact-side-panel analytics-grid">
                  <h2>Risk analysis</h2>

                  {risk ? (
                    <div className="compact-risk-layout">
                      <div className="risk-score-card">
                        <strong>{Math.round(risk.score)} / 100</strong>
                        <span>{formatEnum(risk.level)}</span>
                      </div>
                      <ul className="plain-list compact-list">
                        {riskInsight.reasons.slice(0, 3).map((reason) => (
                          <li key={reason}>{reason}</li>
                        ))}
                      </ul>
                    </div>
                  ) : (
                    <div className="empty-state">
                      <strong>No analytics yet</strong>
                      <span>{latest ? "Analytics is being recalculated from the latest telemetry." : "Analytics will appear after telemetry arrives."}</span>
                    </div>
                  )}
                </section>

                <section className="panel compact-side-panel">
                  <h2>Why this matters</h2>
                  <ul className="explanation-list compact-bullet-list">
                    {whyBullets.slice(0, 5).map((reason) => (
                      <li key={reason}>{reason}</li>
                    ))}
                  </ul>
                </section>

                <section className="panel compact-side-panel recommended-action-panel">
                  <h2>Recommended action</h2>
                  <p className="muted-text">{riskInsight.nextAction}</p>
                  <button className="ghost-button" type="button" onClick={() => setIsChecked(true)} disabled={isChecked}>
                    {isChecked ? "Checked" : "Mark as checked"}
                  </button>
                </section>

                <details className="panel compact-side-panel technical-details">
            <summary>Technical details</summary>
            <div className="technical-grid">
              <div>
                <h3>Baseline</h3>
                {baseline ? (
                  <div className="baseline-grid">
                    <Metric label="Typical power" value={baseline.mean} unit="W" />
                    <Metric label="Median" value={baseline.median} unit="W" />
                    <Metric label="P90 threshold" value={baseline.p90} unit="W" />
                    <Metric label="Samples" value={baseline.sampleCount} unit="" />
                  </div>
                ) : (
                  <p className="muted-text">No baseline calculated.</p>
                )}
              </div>

              <div>
                <h3>Prediction</h3>
                {prediction ? (
                  <div className="prediction-grid">
                    <Metric label="Model version" valueText={prediction.modelVersion} />
                    <Metric label="Confidence" value={prediction.confidence * 100} unit="%" />
                    <Metric label="Anomaly score" value={prediction.anomalyScore} unit="" />
                    <Metric label="Degradation" value={prediction.degradationScore} unit="" />
                  </div>
                ) : (
                  <p className="muted-text">No ML prediction calculated.</p>
                )}
              </div>

              <div>
                <h3>Anomaly events</h3>
                {anomalies.length > 0 ? (
                  <ul className="plain-list compact-list">
                    {anomalies.map((anomaly) => (
                      <li key={anomaly.id}>
                        <strong>{formatEnum(anomaly.type)}</strong>
                        <span>
                          {anomaly.description} · {formatShortDateTime(anomaly.detectedAt)}
                        </span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="muted-text">No anomalies detected.</p>
                )}
              </div>

              <div>
                <h3>Source</h3>
                <dl className="metadata-list">
                  <div>
                    <dt>Device ID</dt>
                    <dd>{device.id}</dd>
                  </div>
                  <div>
                    <dt>Source ID</dt>
                    <dd>{sourceId}</dd>
                  </div>
                </dl>
              </div>
            </div>
                </details>
              </aside>
            </section>
          ) : null}

          {activeTab === "telemetry" && telemetryOverview ? (
            <TelemetryOverviewPage
              overview={telemetryOverview}
              range={chartTimeRange}
              customFrom={customChartFrom}
              customTo={customChartTo}
              density={chartDensity}
              showPoints={showChartPoints}
              selectedMetricKeys={selectedChartMetricKeys}
              metricSearch={metricSearch}
              openedMetricKey={expandedChartMetricKey}
              selectedEventId={selectedTelemetryEventId}
              maxDisplayPoints={CHART_DENSITY_LIMITS[chartDensity]}
              onRangeChange={setChartTimeRange}
              onCustomFromChange={setCustomChartFrom}
              onCustomToChange={setCustomChartTo}
              onDensityChange={setChartDensity}
              onShowPointsChange={setShowChartPoints}
              onMetricSearchChange={setMetricSearch}
              onToggleMetric={toggleChartMetric}
              onSelectAll={selectAllChartMetrics}
              onResetMetrics={resetChartMetrics}
              onClearMetrics={() => setSelectedChartMetricKeys([])}
              onOpenMetric={setExpandedChartMetricKey}
              onCloseMetric={() => setExpandedChartMetricKey(null)}
              onSelectEvent={(eventId) => {
                setSelectedTelemetryEventId(eventId);
                const event = telemetryOverview.events.find((item) => item.id === eventId);
                if (event?.metricKey) {
                  setExpandedChartMetricKey(event.metricKey);
                }
              }}
            />
          ) : null}

          {activeTab === "analytics" ? (
            <section className="analytics-workbench">
              <div className="analytics-workbench-header">
                {predictionInsight ? (
                  <section className={`panel prediction-hero prediction-hero-${predictionInsight.tone}`}>
                    <div className="prediction-hero-head">
                      <div>
                        <span className="telemetry-eyebrow">Prediction</span>
                        <h2>{predictionInsight.title}</h2>
                        <p>{predictionInsight.summary}</p>
                      </div>
                      <span className="prediction-confidence">{predictionInsight.confidence}</span>
                    </div>
                    <div className="prediction-score-grid">
                      <article>
                        <span>Anomaly score</span>
                        <strong>{predictionInsight.anomalyScore}</strong>
                      </article>
                      <article>
                        <span>Degradation</span>
                        <strong>{predictionInsight.degradationScore}</strong>
                      </article>
                      <article>
                        <span>Primary signal</span>
                        <strong>{predictionInsight.primarySignal}</strong>
                      </article>
                      <article>
                        <span>Model</span>
                        <strong>{predictionInsight.modelVersion}</strong>
                      </article>
                    </div>
                    <div className="prediction-evidence-grid">
                      <div>
                        <h3>Evidence</h3>
                        <ul className="compact-bullet-list">
                          {predictionInsight.evidence.map((item) => (
                            <li key={item}>{item}</li>
                          ))}
                        </ul>
                      </div>
                      <div>
                        <h3>Next check</h3>
                        <p>{predictionInsight.nextCheck}</p>
                      </div>
                    </div>
                  </section>
                ) : null}
                {analyticsSummary ? (
                  <section className="panel analytics-score-panel">
                    <div className="risk-score-card">
                      <strong>{risk ? Math.round(risk.score) : analyticsSummary.estimatedScore} / 100</strong>
                      <span>{risk ? formatEnum(risk.level) : `${formatEnum(analyticsSummary.healthStatus)} health`}</span>
                    </div>
                    <div className="analytics-score-reasons">
                      <h3>Main reasons</h3>
                      <ul className="plain-list compact-list">
                        {(risk ? riskInsight.reasons : analyticsSummary.reasons).slice(0, 4).map((reason) => (
                          <li key={reason}>{reason}</li>
                        ))}
                      </ul>
                    </div>
                  </section>
                ) : null}
              </div>

              <section className="panel analytics-chart-panel">
                <div className="section-header analytics-chart-header">
                  <div>
                    <span className="telemetry-eyebrow">Analyze</span>
                    <h2>{analyticsFocusedMetric ? analyticsFocusedMetric.label : "No metric selected"}</h2>
                    <p>
                      {analyticsFocusedOverview
                        ? `${analyticsFocusedOverview.statistics.samplesCount} samples, ${analyticsFocusedEvents.length} events in selected range.`
                        : isTelemetryLoading
                          ? "Loading telemetry samples."
                          : "Select a metric with telemetry samples."}
                    </p>
                  </div>
                  <div className="analytics-chart-actions">
                    <button className="primary-button" type="button" onClick={() => void recalculateDeviceAnalytics()} disabled={isRecalculating}>
                      {isRecalculating ? "Recalculating" : "Recalculate"}
                    </button>
                    <button className="ghost-button" type="button" onClick={selectAllChartMetrics} disabled={availableChartMetrics.length === 0}>
                      Select all
                    </button>
                    <button className="ghost-button" type="button" onClick={resetChartMetrics} disabled={availableChartMetrics.length === 0}>
                      Reset
                    </button>
                  </div>
                </div>

                <div className="analytics-tool-row">
                  <label className="chart-select analytics-metric-select">
                    <span>Signal</span>
                    <select
                      value={analyticsFocusedMetric?.key ?? ""}
                      onChange={(event) => {
                        const metricKey = event.target.value;
                        setExpandedChartMetricKey(metricKey || null);
                        if (metricKey) {
                          setSelectedChartMetricKeys((current) => (current.includes(metricKey) ? current : [...current, metricKey]));
                        }
                      }}
                      disabled={availableChartMetrics.length === 0}
                    >
                      {availableChartMetrics.length === 0 ? <option value="">No metrics</option> : null}
                      {availableChartMetrics.map((metric) => (
                        <option value={metric.key} key={metric.key}>
                          {metric.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <div className="chart-time-controls">
                    <div className="chart-range-buttons" aria-label="Analysis time range">
                      {CHART_TIME_RANGES.map((range) => (
                        <button
                          className={`chart-range-button${chartTimeRange === range.value ? " chart-range-button-active" : ""}`}
                          type="button"
                          key={range.value}
                          onClick={() => setChartTimeRange(range.value)}
                        >
                          {range.label}
                        </button>
                      ))}
                    </div>
                    {chartTimeRange === "custom" ? (
                      <div className="chart-custom-range">
                        <label>
                          <span>From</span>
                          <input type="datetime-local" value={customChartFrom} onChange={(event) => setCustomChartFrom(event.target.value)} />
                        </label>
                        <label>
                          <span>To</span>
                          <input type="datetime-local" value={customChartTo} onChange={(event) => setCustomChartTo(event.target.value)} />
                        </label>
                      </div>
                    ) : null}
                  </div>
                  <div className="analytics-chart-settings">
                    <label className="chart-option">
                      <input type="checkbox" checked={showChartPoints} onChange={(event) => setShowChartPoints(event.target.checked)} />
                      <span>Show points</span>
                    </label>
                    <label className="chart-select">
                      <span>Detail</span>
                      <select value={chartDensity} onChange={(event) => setChartDensity(event.target.value as ChartDensity)}>
                        <option value="compact">Compact</option>
                        <option value="balanced">Balanced</option>
                        <option value="detailed">Detailed</option>
                      </select>
                    </label>
                  </div>
                </div>

                <MetricChart
                  points={analyticsFocusedPoints}
                  label={analyticsFocusedMetric?.label ?? "Metric"}
                  unit={analyticsFocusedMetric?.unit ?? ""}
                  baseline={analyticsBaseline?.mean}
                  threshold={
                    analyticsFocusedOverview?.threshold?.criticalMax ??
                    analyticsFocusedOverview?.threshold?.warningMax ??
                    analyticsBaseline?.p90
                  }
                  anomaly={latestAnomaly}
                  showPoints={showChartPoints}
                  maxDisplayPoints={CHART_DENSITY_LIMITS[chartDensity] * 2}
                  size="large"
                />

                {analyticsFocusedOverview ? (
                  <div className="analytics-stat-strip">
                    <Metric label="Latest" valueText={formatTelemetryMetricValue(analyticsFocusedOverview.statistics.latest, analyticsFocusedMetric?.unit ?? "")} />
                    <Metric label="Average" valueText={formatTelemetryMetricValue(analyticsFocusedOverview.statistics.avg, analyticsFocusedMetric?.unit ?? "")} />
                    <Metric label="Peak" valueText={formatTelemetryMetricValue(analyticsFocusedOverview.statistics.max, analyticsFocusedMetric?.unit ?? "")} />
                    <Metric label="Events" value={analyticsFocusedEvents.length} unit="" />
                  </div>
                ) : null}
              </section>

              <section className="analytics-workbench-grid">
                <section className="panel analytics-grid">
                  <h2>Risk analysis</h2>
                {analyticsSummary ? (
                  <div className="analytics-tab-stack">
                    <div className="analytics-metric-table">
                      <div className="analytics-metric-row analytics-metric-row-head">
                        <span>Metric</span>
                        <span>Status</span>
                        <span>Latest</span>
                        <span>Peak</span>
                        <span>Events</span>
                      </div>
                      {analyticsSummary.metrics.map((metric) => (
                        <button
                          className={`analytics-metric-row${analyticsFocusedMetric?.key === metric.key ? " analytics-metric-row-active" : ""}`}
                          type="button"
                          key={metric.key}
                          onClick={() => {
                            setExpandedChartMetricKey(metric.key);
                            setSelectedChartMetricKeys((current) => (current.includes(metric.key) ? current : [...current, metric.key]));
                          }}
                        >
                          <span>{metric.name}</span>
                          <span>{formatEnum(metric.status)}</span>
                          <span>{metric.latest}</span>
                          <span>{metric.peak}</span>
                          <span>{metric.events}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                ) : (
                  <div className="empty-state">
                    <strong>No telemetry analytics yet</strong>
                    <span>Start the simulator or connect telemetry to calculate metric status, events, and trends.</span>
                  </div>
                )}
              </section>

              <aside className="analytics-side-tools">
                <section className="panel compact-side-panel">
                  <h2>Why this matters</h2>
                  <ul className="explanation-list compact-bullet-list">
                    {whyBullets.slice(0, 5).map((reason) => (
                      <li key={reason}>{reason}</li>
                    ))}
                  </ul>
                </section>
                <section className="panel compact-side-panel recommended-action-panel">
                  <h2>Recommended action</h2>
                  <p className="muted-text">{riskInsight.nextAction}</p>
                  <button className="ghost-button" type="button" onClick={() => setIsChecked(true)} disabled={isChecked}>
                    {isChecked ? "Checked" : "Mark as checked"}
                  </button>
                </section>
                <section className="panel compact-side-panel">
                  <h2>Recent events</h2>
                  {analyticsFocusedEvents.length > 0 ? (
                    <ul className="plain-list compact-list">
                      {analyticsFocusedEvents.slice(0, 6).map((event) => (
                        <li key={event.id}>
                          <strong>{event.title}</strong>
                          <span>
                            {formatEnum(event.severity)} - {formatShortDateTime(event.timestamp)}
                          </span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="muted-text">No events for selected signal.</p>
                  )}
                </section>
                {analyticsSummary ? (
                  <section className="panel compact-side-panel">
                    <h2>Signals</h2>
                    <div className="analytics-signal-grid">
                      <Metric label="Events" value={analyticsSummary.eventCount} unit="" />
                      <Metric label="Warning metrics" value={analyticsSummary.warningCount} unit="" />
                      <Metric label="Critical metrics" value={analyticsSummary.criticalCount} unit="" />
                    </div>
                  </section>
                ) : null}
              </aside>
              </section>
            </section>
          ) : null}

          {activeTab === "alerts" ? (
            <section className="panel device-alerts-panel">
              <div className="section-header">
                <div>
                  <h2>Device alerts</h2>
                  <p>
                    {filteredDeviceAlerts.length} shown from {deviceAlerts.length} alerts for this device.
                  </p>
                </div>
                <div className="action-row">
                  <select value={deviceAlertStatusFilter} onChange={(event) => setDeviceAlertStatusFilter(event.target.value)}>
                    <option value="active">Active only</option>
                    <option value="all">All statuses</option>
                    <option value="OPEN">Open</option>
                    <option value="ACKNOWLEDGED">Acknowledged</option>
                    <option value="RESOLVED">Resolved</option>
                  </select>
                  <Link className="ghost-button button-link" to="/alerts">
                    All alerts
                  </Link>
                </div>
              </div>
              <div className="alert-summary-counts device-alert-summary-counts">
                <article>
                  <strong>{deviceAlerts.filter((alert) => alert.status === "OPEN").length}</strong>
                  <span>Open</span>
                </article>
                <article>
                  <strong>{deviceAlerts.filter((alert) => alert.status === "ACKNOWLEDGED").length}</strong>
                  <span>Acknowledged</span>
                </article>
                <article>
                  <strong>{deviceAlerts.filter((alert) => alert.status === "RESOLVED").length}</strong>
                  <span>Resolved</span>
                </article>
              </div>
              {filteredDeviceAlerts.length > 0 ? (
                <div className="device-alert-list">
                  <div className="device-alert-row device-alert-row-head">
                    <span>Created</span>
                    <span>Alert</span>
                    <span>Severity</span>
                    <span>Status</span>
                    <span>Action</span>
                  </div>
                  {visibleDeviceAlerts.map((alert) => (
                    <article className={`device-alert-row severity-${alert.severity.toLowerCase()}`} key={alert.id}>
                      <span>{formatShortDateTime(alert.createdAt)}</span>
                      <div>
                        <strong>{alert.title || formatEnum(alert.alertType)}</strong>
                        <small>{alert.description || "The device behavior moved outside its recent normal range."}</small>
                      </div>
                      <span>{formatEnum(alert.severity)}</span>
                      <span>{formatEnum(alert.status)}</span>
                      <div className="action-row">
                        {alert.status === "OPEN" ? (
                          <button className="ghost-button" type="button" onClick={() => void updateDeviceAlert(alert.id, "ack")}>
                            Acknowledge
                          </button>
                        ) : null}
                        {alert.status === "ACKNOWLEDGED" ? (
                          <button className="ghost-button" type="button" onClick={() => void updateDeviceAlert(alert.id, "resolve")}>
                            Resolve
                          </button>
                        ) : null}
                        {alert.status === "RESOLVED" ? <span className="muted-text">Done</span> : null}
                      </div>
                    </article>
                  ))}
                  {filteredDeviceAlerts.length > visibleDeviceAlerts.length ? (
                    <button className="ghost-button" type="button" onClick={() => setShowAllDeviceAlerts(true)}>
                      Show all {filteredDeviceAlerts.length} alerts
                    </button>
                  ) : null}
                </div>
              ) : (
                <div className="empty-state">
                  <strong>No alerts for this device</strong>
                  <span>Threshold events can still appear in telemetry, but no alert records are open for this device.</span>
                </div>
              )}
            </section>
          ) : null}
        </>
      ) : null}
    </>
  );
}

function Metric({
  label,
  value,
  unit = "",
  valueText,
  emphasis = false,
}: {
  label: string;
  value?: number;
  unit?: string;
  valueText?: string;
  emphasis?: boolean;
}) {
  return (
    <article className={`metric-card${emphasis ? " metric-card-emphasis" : ""}`}>
      <span>{label}</span>
      <strong>{valueText ?? (value === undefined ? "No data" : `${formatNumber(value)}${unit ? ` ${unit}` : ""}`)}</strong>
    </article>
  );
}

function buildIncidentSummary(overview: TelemetryOverviewResponse, riskLevel: string | null, range: ChartTimeRange) {
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

function buildIncidentConclusion(overview: TelemetryOverviewResponse, metricKey?: string) {
  const voltage = overview.metrics.find((metric) => metric.definition.key === "voltageV");
  const current = overview.metrics.find((metric) => metric.definition.key === "currentA");
  if (metricKey === "activePowerW" && current?.status !== "no_data" && voltage?.status === "normal") {
    return "Power changed with current while voltage stayed stable, so this is likely load-related.";
  }
  const problem = overview.metrics.find((metric) => metric.definition.key === metricKey);
  return problem?.conclusion ?? overview.health.reason;
}

function buildWhyBullets(overview: TelemetryOverviewResponse, fallback: string[]) {
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

function buildAnalyticsSummary(overview: TelemetryOverviewResponse) {
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
  const estimatedScore = Math.min(100, criticalCount * 35 + warningCount * 18 + overview.events.length * 3 + noDataCount * 8);
  const worst = overview.metrics.find((metric) => metric.status === "critical") ??
    overview.metrics.find((metric) => metric.status === "warning" || metric.status === "stale") ??
    overview.metrics[0];
  return {
    metrics,
    warningCount,
    criticalCount,
    eventCount: overview.events.length,
    healthStatus: overview.health.status,
    estimatedScore,
    reasons: [
      `${overview.events.length} telemetry events were detected in the selected range.`,
      `${warningCount + criticalCount} metrics are outside normal state.`,
      `${worst.definition.name} is the strongest signal: latest ${formatTelemetryMetricValue(worst.statistics.latest, worst.definition.unit)}, peak ${formatTelemetryMetricValue(worst.statistics.max, worst.definition.unit)}.`,
      overview.health.reason,
    ],
  };
}

function buildPredictionInsight(prediction: PredictionResponse | null, analytics: NonNullable<ReturnType<typeof buildAnalyticsSummary>>) {
  const primary = analytics.metrics
    .filter((metric) => metric.status !== "normal")
    .sort((left, right) => right.events - left.events)[0] ?? analytics.metrics[0];
  const anomalyScore = prediction?.anomalyScore ?? Math.min(1, analytics.eventCount / 10 + analytics.warningCount * 0.12 + analytics.criticalCount * 0.24);
  const degradationScore = prediction?.degradationScore ?? Math.min(1, analytics.warningCount * 0.16 + analytics.criticalCount * 0.32);
  const confidence = prediction?.confidence ?? (analytics.eventCount > 0 ? 0.78 : 0.64);
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
    summary: prediction
      ? `Model prediction is based on the latest telemetry window with ${Math.round(confidence * 100)}% confidence.`
      : "Backend prediction is not available yet, so this estimate is derived from current telemetry events and metric status.",
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

function normalizeRiskTone(value: string) {
  const normalized = value.toLowerCase();
  if (normalized.includes("critical") || normalized.includes("high")) return "critical";
  if (normalized.includes("medium") || normalized.includes("warning")) return "high";
  if (normalized.includes("low") || normalized.includes("normal")) return "low";
  return "neutral";
}

function severityRank(value: string) {
  const normalized = value.toUpperCase();
  if (normalized === "CRITICAL") return 4;
  if (normalized === "HIGH") return 3;
  if (normalized === "MEDIUM" || normalized === "WARNING") return 2;
  if (normalized === "LOW") return 1;
  return 0;
}

function ChartTimeControls({
  range,
  customFrom,
  customTo,
  onRangeChange,
  onCustomFromChange,
  onCustomToChange,
}: {
  range: ChartTimeRange;
  customFrom: string;
  customTo: string;
  onRangeChange: (range: ChartTimeRange) => void;
  onCustomFromChange: (value: string) => void;
  onCustomToChange: (value: string) => void;
}) {
  return (
    <div className="chart-time-controls">
      <div className="chart-range-buttons" aria-label="Chart time range">
        {CHART_TIME_RANGES.map((item) => (
          <button
            className={`chart-range-button${range === item.value ? " chart-range-button-active" : ""}`}
            type="button"
            key={item.value}
            onClick={() => onRangeChange(item.value)}
          >
            {item.label}
          </button>
        ))}
      </div>
      {range === "custom" ? (
        <div className="chart-custom-range">
          <label>
            <span>From</span>
            <input type="datetime-local" value={customFrom} onChange={(event) => onCustomFromChange(event.target.value)} />
          </label>
          <label>
            <span>To</span>
            <input type="datetime-local" value={customTo} onChange={(event) => onCustomToChange(event.target.value)} />
          </label>
        </div>
      ) : null}
    </div>
  );
}

function DurationInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const parsed = parseDurationInput(value);
  const [amount, setAmount] = useState(parsed.amount);
  const [unit, setUnit] = useState(parsed.unit);

  useEffect(() => {
    const next = parseDurationInput(value);
    setAmount(next.amount);
    setUnit(next.unit);
  }, [value]);

  function emit(nextAmount: string, nextUnit: string) {
    setAmount(nextAmount);
    setUnit(nextUnit);
    onChange(nextAmount === "" ? "" : `${nextAmount}${nextUnit}`);
  }

  return (
    <div className="duration-input">
      <input
        min="0"
        step="1"
        type="number"
        value={amount}
        onChange={(event) => emit(event.target.value, unit)}
      />
      <select value={unit} onChange={(event) => emit(amount, event.target.value)}>
        <option value="s">sec</option>
        <option value="m">min</option>
        <option value="h">hr</option>
      </select>
    </div>
  );
}

function parseDurationInput(value: string) {
  const match = value.trim().match(/^(\d+(?:\.\d+)?)(ms|s|m|h)?$/);
  return {
    amount: match?.[1] ?? "",
    unit: match?.[2] && match[2] !== "ms" ? match[2] : "s",
  };
}

function readSimulationSettings(yaml: string): SimulationSettings {
  return {
    name: readYamlString(yaml, /name:[ \t]*"?([^"\n]*)"?/),
    sourceId: readYamlString(yaml, /sourceId:[ \t]*"?([^"\n]*)"?/),
    interval: readYamlString(yaml, /interval:[ \t]*([^\n]*)/),
    duration: readYamlString(yaml, /duration:[ \t]*([^\n]*)/),
    speed: readYamlString(yaml, /speed:[ \t]*([^\n]*)/),
    voltageV: readYamlString(yaml, /voltageV:[ \t]*\n[ \t]+type:[ \t]+constant[ \t]*\n[ \t]+value:[ \t]*([^\n]*)/),
    rssi: readYamlString(yaml, /rssi:[ \t]*\n[ \t]+type:[ \t]+constant[ \t]*\n[ \t]+value:[ \t]*([^\n]*)/),
    idlePowerW: readYamlString(yaml, /offValue:[ \t]*\n[ \t]+mean:[ \t]*([^\n]*)/),
    activePowerW: readYamlString(yaml, /onValue:[ \t]*\n[ \t]+mean:[ \t]*([^\n]*)/),
    activeNoiseW: readYamlString(yaml, /onValue:[ \t]*\n[ \t]+mean:[ \t]*[^\n]*\n[ \t]+noise:[ \t]*([^\n]*)/),
    onMin: readYamlString(yaml, /onDuration:[ \t]*\n[ \t]+min:[ \t]*([^\n]*)/),
    onMax: readYamlString(yaml, /onDuration:[ \t]*\n[ \t]+min:[ \t]*[^\n]*\n[ \t]+max:[ \t]*([^\n]*)/),
    offMin: readYamlString(yaml, /offDuration:[ \t]*\n[ \t]+min:[ \t]*([^\n]*)/),
    offMax: readYamlString(yaml, /offDuration:[ \t]*\n[ \t]+min:[ \t]*[^\n]*\n[ \t]+max:[ \t]*([^\n]*)/),
    powerSpikeEnabled: readAnomalyBoolean(yaml, "POWER_SPIKE", "enabled"),
    powerSpikeAfter: readAnomalyString(yaml, "POWER_SPIKE", "startAfter"),
    powerSpikeDuration: readAnomalyString(yaml, "POWER_SPIKE", "duration"),
    powerSpikeWatts: readAnomalyString(yaml, "POWER_SPIKE", "powerW"),
    offlineEnabled: readAnomalyBoolean(yaml, "OFFLINE", "enabled"),
    offlineAfter: readAnomalyString(yaml, "OFFLINE", "startAfter"),
    offlineDuration: readAnomalyString(yaml, "OFFLINE", "duration"),
    growthEnabled: readAnomalyBoolean(yaml, "CONSUMPTION_GROWTH", "enabled"),
    growthAfter: readAnomalyString(yaml, "CONSUMPTION_GROWTH", "startAfter"),
    growthPercent: readAnomalyString(yaml, "CONSUMPTION_GROWTH", "growthPercent"),
    growthRamp: readAnomalyString(yaml, "CONSUMPTION_GROWTH", "rampDuration"),
  };
}

function readYamlString(yaml: string, pattern: RegExp) {
  return yaml.match(pattern)?.[1]?.trim() ?? "";
}

function readAnomalyString(yaml: string, type: string, field: string) {
  const block = anomalyBlock(yaml, type);
  return block.match(new RegExp(`${field}:[ \\t]*([^\\n]*)`))?.[1]?.trim() ?? "";
}

function readAnomalyBoolean(yaml: string, type: string, field: string) {
  return readAnomalyString(yaml, type, field).toLowerCase() === "true";
}

function updateSimulationYaml(yaml: string, field: keyof SimulationSettings | "profile" | "protocol", value: string | boolean) {
  const textValue = typeof value === "boolean" ? String(value) : value;
  switch (field) {
    case "name":
      return replaceYaml(yaml, /(name:[ \t]*)"?([^"\n]*)"?/, `$1${textValue}`);
    case "profile":
      return replaceYaml(yaml, /(profile:[ \t]*)[A-Z_]*/, `$1${textValue}`);
    case "protocol":
      return replaceYaml(yaml, /(protocol:[ \t]*)[A-Z_]*/, `$1${textValue}`);
    case "sourceId":
      return replaceYaml(yaml, /(sourceId:[ \t]*)"?([^"\n]*)"?/, `$1"${textValue}"`);
    case "interval":
      return replaceYaml(yaml, /(interval:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "duration":
      return replaceYaml(yaml, /(duration:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "speed":
      return replaceYaml(yaml, /(speed:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "voltageV":
      return replaceYaml(yaml, /(voltageV:[ \t]*\n[ \t]+type:[ \t]+constant[ \t]*\n[ \t]+value:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "rssi":
      return replaceYaml(yaml, /(rssi:[ \t]*\n[ \t]+type:[ \t]+constant[ \t]*\n[ \t]+value:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "idlePowerW":
      return replaceYaml(yaml, /(offValue:[ \t]*\n[ \t]+mean:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "activePowerW":
      return replaceYaml(yaml, /(onValue:[ \t]*\n[ \t]+mean:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "activeNoiseW":
      return replaceYaml(yaml, /(onValue:[ \t]*\n[ \t]+mean:[ \t]*[^\n]*\n[ \t]+noise:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "onMin":
      return replaceYaml(yaml, /(onDuration:[ \t]*\n[ \t]+min:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "onMax":
      return replaceYaml(yaml, /(onDuration:[ \t]*\n[ \t]+min:[ \t]*[^\n]*\n[ \t]+max:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "offMin":
      return replaceYaml(yaml, /(offDuration:[ \t]*\n[ \t]+min:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "offMax":
      return replaceYaml(yaml, /(offDuration:[ \t]*\n[ \t]+min:[ \t]*[^\n]*\n[ \t]+max:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "powerSpikeEnabled":
      return updateAnomalyYaml(yaml, "POWER_SPIKE", "enabled", textValue);
    case "powerSpikeAfter":
      return updateAnomalyYaml(yaml, "POWER_SPIKE", "startAfter", textValue);
    case "powerSpikeDuration":
      return updateAnomalyYaml(yaml, "POWER_SPIKE", "duration", textValue);
    case "powerSpikeWatts":
      return updateAnomalyYaml(yaml, "POWER_SPIKE", "powerW", textValue);
    case "offlineEnabled":
      return updateAnomalyYaml(yaml, "OFFLINE", "enabled", textValue);
    case "offlineAfter":
      return updateAnomalyYaml(yaml, "OFFLINE", "startAfter", textValue);
    case "offlineDuration":
      return updateAnomalyYaml(yaml, "OFFLINE", "duration", textValue);
    case "growthEnabled":
      return updateAnomalyYaml(yaml, "CONSUMPTION_GROWTH", "enabled", textValue);
    case "growthAfter":
      return updateAnomalyYaml(yaml, "CONSUMPTION_GROWTH", "startAfter", textValue);
    case "growthPercent":
      return updateAnomalyYaml(yaml, "CONSUMPTION_GROWTH", "growthPercent", textValue);
    case "growthRamp":
      return updateAnomalyYaml(yaml, "CONSUMPTION_GROWTH", "rampDuration", textValue);
    default:
      return yaml;
  }
}

function replaceYaml(yaml: string, pattern: RegExp, replacement: string) {
  if (!pattern.test(yaml)) {
    return yaml;
  }
  if (replacement.startsWith("$1")) {
    const value = replacement.slice(2);
    return yaml.replace(pattern, (_match, prefix: string) => `${prefix}${value}`);
  }
  return yaml.replace(pattern, replacement);
}

function anomalyBlock(yaml: string, type: string) {
  const match = yaml.match(new RegExp(`  - type: ${type}[\\s\\S]*?(?=\\n  - type:|\\n?$)`));
  return match?.[0] ?? "";
}

function updateAnomalyYaml(yaml: string, type: string, field: string, value: string) {
  const block = anomalyBlock(yaml, type);
  if (!block) {
    return yaml;
  }
  const updatedBlock = new RegExp(`(\\n[ \\t]+${field}:[ \\t]*)[^\\n]*`).test(block)
    ? block.replace(new RegExp(`(\\n[ \\t]+${field}:[ \\t]*)[^\\n]*`), (_match, prefix: string) => `${prefix}${value}`)
    : block.replace(new RegExp(`(  - type: ${type})`), `$1\n    ${field}: ${value}`);
  return yaml.replace(block, updatedBlock);
}

function arraysEqual(first: string[], second: string[]) {
  return first.length === second.length && first.every((item, index) => item === second[index]);
}

function buildChartMetrics(readings: Record<string, number>): ChartMetricConfig[] {
  return Object.entries(readings)
    .filter(([, value]) => typeof value === "number" && Number.isFinite(value))
    .map(([key]) => CHART_METRIC_LOOKUP.get(key) ?? createMetricConfig(key))
    .sort((first, second) => metricSortWeight(first.key) - metricSortWeight(second.key) || first.label.localeCompare(second.label));
}

function createMetricConfig(key: string): ChartMetricConfig {
  return {
    key,
    label: humanizeMetricKey(key),
    unit: inferMetricUnit(key),
  };
}

function metricSortWeight(key: string) {
  const index = DEFAULT_CHART_METRICS.indexOf(key);
  return index >= 0 ? index : DEFAULT_CHART_METRICS.length + 1;
}

function humanizeMetricKey(key: string) {
  return key
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function inferMetricUnit(key: string) {
  const normalized = key.toLowerCase();
  if (normalized.endsWith("w") || normalized.includes("power")) return "W";
  if (normalized.endsWith("v") || normalized.includes("voltage")) return "V";
  if (normalized.endsWith("a") || normalized.includes("current")) return "A";
  if (normalized.includes("energy") || normalized.endsWith("wh")) return "Wh";
  if (normalized.includes("rssi") || normalized.includes("signal")) return "dBm";
  if (normalized.includes("percent") || normalized.endsWith("pct")) return "%";
  if (normalized.includes("temperature") || normalized.endsWith("c")) return "C";
  return "";
}

function getChartTimeWindow(range: ChartTimeRange, customFrom: string, customTo: string) {
  if (range === "custom") {
    const fromDate = new Date(customFrom);
    const toDate = new Date(customTo);
    if (Number.isNaN(fromDate.getTime()) || Number.isNaN(toDate.getTime()) || fromDate >= toDate) {
      return null;
    }
    return { from: fromDate.toISOString(), to: toDate.toISOString() };
  }

  const preset = CHART_TIME_RANGES.find((item) => item.value === range);
  const to = new Date();
  const from = new Date(to.getTime() - (preset?.durationMs ?? 24 * 60 * 60 * 1000));
  return { from: from.toISOString(), to: to.toISOString() };
}

function toDateTimeLocalValue(date: Date) {
  const offsetMs = date.getTimezoneOffset() * 60 * 1000;
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 16);
}

function sampleChartPoints(points: ChartPoint[], maxPoints: number): SampledChartPoint[] {
  if (points.length <= maxPoints) {
    return points.map((point, sourceIndex) => ({ point, sourceIndex }));
  }

  const lastIndex = points.length - 1;
  const maxIndex = points.reduce((currentIndex, point, index) => (point.value > points[currentIndex].value ? index : currentIndex), 0);
  const indexes = new Set<number>([0, lastIndex, maxIndex]);
  for (let index = 1; index < maxPoints - 1; index += 1) {
    indexes.add(Math.round((index / (maxPoints - 1)) * lastIndex));
  }

  return [...indexes].sort((first, second) => first - second).map((sourceIndex) => ({ point: points[sourceIndex], sourceIndex }));
}

function MetricChart({
  points,
  label,
  unit,
  baseline,
  threshold,
  anomaly,
  showPoints,
  maxDisplayPoints,
  interactive = true,
  size = "normal",
}: {
  points: ChartPoint[];
  label: string;
  unit: string;
  baseline?: number;
  threshold?: number;
  anomaly?: AnomalyResponse | null;
  showPoints: boolean;
  maxDisplayPoints: number;
  interactive?: boolean;
  size?: "normal" | "large";
}) {
  const [hovered, setHovered] = useState<{ point: ChartPoint; x: number; y: number } | null>(null);
  if (points.length === 0) {
    return (
      <div className={`chart-wrap annotated-chart chart-${size}`}>
        <div className="empty-state">
          <strong>No samples</strong>
          <span>Try a wider time range.</span>
        </div>
      </div>
    );
  }

  const width = 820;
  const height = 330;
  const padding = { top: 28, right: 28, bottom: 54, left: 58 };
  const values = [...points.map((point) => point.value), baseline, threshold].filter((value): value is number => value !== undefined);
  const rawMin = Math.min(...values);
  const rawMax = Math.max(...values);
  const rawRange = rawMax - rawMin || Math.max(Math.abs(rawMax), 1);
  const paddingValue = rawRange * 0.08;
  const includeZero = ["W", "Wh", "A"].includes(unit) && rawMin >= 0;
  const minValue = includeZero ? Math.min(0, rawMin - paddingValue) : rawMin - paddingValue;
  const maxValue = rawMax + paddingValue;
  const range = maxValue - minValue || 1;
  const yForValue = (value: number) => height - padding.bottom - ((value - minValue) / range) * (height - padding.top - padding.bottom);
  const xForIndex = (index: number) =>
    padding.left + (index / Math.max(points.length - 1, 1)) * (width - padding.left - padding.right);
  const sampledPoints = sampleChartPoints(points, maxDisplayPoints);
  const coordinates = sampledPoints.map(({ point, sourceIndex }) => ({
    point,
    x: xForIndex(sourceIndex),
    y: yForValue(point.value),
  }));
  const spike = coordinates.reduce((current, next) => (next.point.value > current.point.value ? next : current), coordinates[0]);
  const visiblePointCoordinates = showPoints
    ? coordinates
    : threshold !== undefined && spike.point.value >= threshold
      ? [spike]
      : [];
  const yTicks = [minValue, minValue + range / 2, maxValue];
  const xTicks = [coordinates[0], coordinates[Math.floor(coordinates.length / 2)], coordinates[coordinates.length - 1]].filter(Boolean);

  return (
    <div className={`chart-wrap annotated-chart chart-${size}`}>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${label} chart`}>
        {yTicks.map((tick) => {
          const y = yForValue(tick);
          return (
            <g key={tick}>
              <line className="chart-grid-line" x1={padding.left} y1={y} x2={width - padding.right} y2={y} />
              <text className="chart-axis-label" x={padding.left - 10} y={y + 4} textAnchor="end">
                {formatCompactNumber(tick)} {unit}
              </text>
            </g>
          );
        })}

        <line className="chart-axis" x1={padding.left} y1={height - padding.bottom} x2={width - padding.right} y2={height - padding.bottom} />
        <line className="chart-axis" x1={padding.left} y1={padding.top} x2={padding.left} y2={height - padding.bottom} />

        {baseline !== undefined ? (
          <g>
            <line className="chart-baseline" x1={padding.left} y1={yForValue(baseline)} x2={width - padding.right} y2={yForValue(baseline)} />
            <text className="chart-line-label" x={width - padding.right - 8} y={yForValue(baseline) - 6} textAnchor="end">
              Typical range
            </text>
          </g>
        ) : null}

        {threshold !== undefined ? (
          <g>
            <line className="chart-threshold" x1={padding.left} y1={yForValue(threshold)} x2={width - padding.right} y2={yForValue(threshold)} />
            <text className="chart-line-label threshold-label" x={width - padding.right - 8} y={yForValue(threshold) - 6} textAnchor="end">
              Alert threshold
            </text>
          </g>
        ) : null}

        <polyline points={coordinates.map((coordinate) => `${coordinate.x},${coordinate.y}`).join(" ")} />

        {visiblePointCoordinates.map((coordinate, index) => {
          const isSpike = coordinate === spike && (threshold === undefined || coordinate.point.value >= threshold);
          return (
            <circle
              key={`visible-${coordinate.point.timestamp}-${index}`}
              className={isSpike ? "chart-spike-point" : undefined}
              cx={coordinate.x}
              cy={coordinate.y}
              r={isSpike ? 6 : 4}
              onMouseEnter={interactive ? () => setHovered(coordinate) : undefined}
              onMouseLeave={interactive ? () => setHovered(null) : undefined}
              onFocus={interactive ? () => setHovered(coordinate) : undefined}
              tabIndex={interactive ? 0 : -1}
            >
              <title>{`${formatShortTime(coordinate.point.timestamp)} - ${formatMetricValue(coordinate.point.value, unit)}`}</title>
            </circle>
          );
        })}

        {coordinates.map((coordinate, index) => (
          <circle
            key={`hit-${coordinate.point.timestamp}-${index}`}
            className="chart-hit-point"
            cx={coordinate.x}
            cy={coordinate.y}
            r={8}
            onMouseEnter={interactive ? () => setHovered(coordinate) : undefined}
            onMouseLeave={interactive ? () => setHovered(null) : undefined}
            onFocus={interactive ? () => setHovered(coordinate) : undefined}
            tabIndex={interactive ? 0 : -1}
          >
            <title>{`${formatShortTime(coordinate.point.timestamp)} - ${formatMetricValue(coordinate.point.value, unit)}`}</title>
          </circle>
        ))}

        {anomaly && spike ? (
          <text className="chart-spike-label" x={spike.x} y={Math.max(spike.y - 14, 16)} textAnchor="middle">
            Power spike
          </text>
        ) : null}

        {xTicks.map((tick) => (
          <text key={tick.point.timestamp} className="chart-axis-label" x={tick.x} y={height - 20} textAnchor="middle">
            {formatShortTime(tick.point.timestamp)}
          </text>
        ))}
      </svg>
      {hovered ? (
        <div className="chart-tooltip" style={{ left: `${(hovered.x / width) * 100}%`, top: `${(hovered.y / height) * 100}%` }}>
          <strong>{formatShortTime(hovered.point.timestamp)}</strong>
          <span>{formatMetricValue(hovered.point.value, unit)}</span>
          {threshold !== undefined && hovered.point.value >= threshold ? <em>Above threshold</em> : null}
        </div>
      ) : null}
    </div>
  );
}

function getRiskInsight(
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

function formatInsightText(value: string) {
  return formatEnum(value)
    .replace("P90", "typical range")
    .replace("Ml", "ML")
    .replace("Power Spike", "Power spike")
    .replace("Consumption Growth", "Consumption growth");
}

function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function formatNumber(value: number) {
  return Number.isInteger(value) ? value.toLocaleString() : value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

function formatCompactNumber(value: number) {
  const abs = Math.abs(value);
  const maximumFractionDigits = abs < 10 ? 2 : abs < 100 ? 1 : 0;
  return value.toLocaleString(undefined, { maximumFractionDigits });
}

function formatMetricValue(value: unknown, unit: string) {
  return typeof value === "number" ? `${formatCompactNumber(value)} ${unit}` : "No data";
}

function formatShortTime(value: string) {
  return new Date(value).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

function formatShortDateTime(value: string) {
  return new Date(value).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

function locationLabel(location: Location) {
  const hierarchy = [location.building, location.floor ? `Floor ${location.floor}` : null, location.room, location.zone]
    .filter(Boolean)
    .join(" / ");
  return hierarchy ? `${location.name} - ${hierarchy}` : location.name;
}

function parseJsonObject(value: string) {
  const trimmed = value.trim();
  if (!trimmed || trimmed === "{}") {
    return null;
  }
  return JSON.parse(trimmed) as Record<string, unknown>;
}

function downloadText(text: string, filename: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
