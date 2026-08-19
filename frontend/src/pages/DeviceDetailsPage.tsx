import { useEffect, useMemo, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { AnomalyResponse, PredictionResponse, RecommendationResponse, RiskResponse } from "../analytics/analyticsApi";
import { TelemetryOverviewPage } from "../features/telemetry/pages/TelemetryOverviewPage";
import { mapTelemetryOverview } from "../features/telemetry/model/telemetry.mappers";
import { TelemetryOverviewResponse } from "../features/telemetry/model/telemetry.types";
import { formatMetricValue as formatTelemetryMetricValue } from "../features/telemetry/model/telemetry.utils";
import { DeviceMetricChart as MetricChart } from "../devices/details/DeviceMetricChart";
import { DurationInput } from "../devices/details/DurationInput";
import { DeviceIncidentSummary } from "../devices/details/DeviceIncidentSummary";
import { DeviceDetailsTab, DeviceTabs } from "../devices/details/DeviceTabs";
import { CHART_DENSITY_LIMITS, CHART_TIME_RANGES, ChartTimeRange } from "../devices/details/deviceTelemetryModel";
import { formatCompactNumber, formatEnum, formatNumber, formatShortDateTime, formatShortTime } from "../devices/details/deviceDetailsFormatting";
import { useDeviceAlertsWorkspace } from "../devices/details/useDeviceAlertsWorkspace";
import { useDeviceAnalyticsWorkspace } from "../devices/details/useDeviceAnalyticsWorkspace";
import { useDeviceDetailsData } from "../devices/details/useDeviceDetailsData";
import { useDeviceMaintenanceActions } from "../devices/details/useDeviceMaintenanceActions";
import { useDeviceRegistryActions } from "../devices/details/useDeviceRegistryActions";
import { useDeviceSimulationWorkspace } from "../devices/details/useDeviceSimulationWorkspace";
import { useDeviceTelemetryWorkspace } from "../devices/details/useDeviceTelemetryWorkspace";

export function DeviceDetailsPage() {
  const { deviceId } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = normalizeDeviceTab(searchParams.get("tab"));
  const [isChecked, setIsChecked] = useState(false);
  const [simulationSourceToken, setSimulationSourceToken] = useState<string | null>(null);
  const details = useDeviceDetailsData(deviceId);
  const telemetry = useDeviceTelemetryWorkspace({ deviceId, activeTab, searchParams });
  const analytics = useDeviceAnalyticsWorkspace(deviceId);
  const maintenance = useDeviceMaintenanceActions(deviceId);
  const alerts = useDeviceAlertsWorkspace(deviceId);
  const simulation = useDeviceSimulationWorkspace({
    deviceId,
    activeTab,
    onSourceTokenGenerated: setSimulationSourceToken,
  });
  const registry = useDeviceRegistryActions({
    deviceId,
    onSourceTokenRotated: simulation.syncSourceToken,
  });

  const {
    device,
    locationName,
    isLoading,
    setIsLoading,
    loadDeviceContext,
    changeDeviceStatus,
  } = details;
  const {
    latest,
    charts,
    selectedChartMetricKeys,
    setSelectedChartMetricKeys,
    showChartPoints,
    setShowChartPoints,
    chartDensity,
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
  } = telemetry;
  const {
    baseline,
    risk,
    anomalies,
    recommendations,
    prediction,
    analyticsJob,
    isRecalculating,
    loadAnalytics,
    enqueueAndWaitForAnalyticsJob,
    recalculateDeviceAnalytics,
  } = analytics;
  const {
    maintenanceWindows,
    maintenanceTitle,
    setMaintenanceTitle,
    maintenanceDescription,
    setMaintenanceDescription,
    maintenanceStartAt,
    setMaintenanceStartAt,
    maintenanceEndAt,
    setMaintenanceEndAt,
    maintenanceSuppressAlerts,
    setMaintenanceSuppressAlerts,
    maintenanceExcludeAnalytics,
    setMaintenanceExcludeAnalytics,
    maintenanceExcludeMl,
    setMaintenanceExcludeMl,
    maintenanceMessage,
    isMaintenanceBusy,
    sortedMaintenanceWindows,
    activeMaintenanceWindows,
    upcomingMaintenanceWindows,
    suppressingMaintenanceWindow,
    refreshMaintenanceWindows,
    submitMaintenanceWindow,
    cancelWindow,
    finishWindowNow,
  } = maintenance;
  const {
    deviceAlerts,
    deviceAlertStatusFilter,
    setDeviceAlertStatusFilter,
    setShowAllDeviceAlerts,
    filteredDeviceAlerts,
    visibleDeviceAlerts,
    loadDeviceAlerts,
    updateDeviceAlert,
  } = alerts;
  const {
    simulationConfig,
    simulationStatus,
    simulationRuns,
    simulationYaml,
    setSimulationYaml,
    simulationProfile,
    simulationProtocol,
    simulationMessage,
    simulationValidation,
    isSimulationBusy,
    simulationSettings,
    simulationSourceId,
    loadSimulation,
    validateYaml,
    saveYaml,
    updateSimulationField,
    changeSimulationProfile,
    changeSimulationProtocol,
    toggleSimulation,
    resetYamlTemplate,
  } = simulation;
  const {
    dataSources,
    sensors,
    analyticsConfig,
    setAnalyticsConfig,
    analyticsConfigText,
    setAnalyticsConfigText,
    dataSourceExternalId,
    setDataSourceExternalId,
    dataSourceType,
    setDataSourceType,
    dataSourceProtocol,
    setDataSourceProtocol,
    sourceCsvText,
    setSourceCsvText,
    sourceCsvMessage,
    lastSourceToken: registryLastSourceToken,
    sensorName,
    setSensorName,
    sensorType,
    setSensorType,
    sensorUnit,
    setSensorUnit,
    sensorMetricMapping,
    setSensorMetricMapping,
    sensorMetadataText,
    setSensorMetadataText,
    isRegistryBusy,
    loadRegistry,
    addDataSource,
    rotateSourceToken,
    revokeSourceToken,
    disableSource,
    importSourcesCsv,
    exportSourcesCsv,
    addSensor,
    removeSensor,
    saveAnalyticsConfig,
  } = registry;
  const lastSourceToken = registryLastSourceToken ?? simulationSourceToken;
  const error = details.error ?? analytics.error ?? alerts.error ?? registry.error;

  async function load() {
    if (!deviceId) {
      return;
    }
    setIsLoading(true);
    details.setError(null);
    analytics.setError(null);
    alerts.setError(null);
    registry.setError(null);
    try {
      await Promise.all([
        loadDeviceContext(deviceId),
        refreshMaintenanceWindows(deviceId),
      ]);
      await loadDeviceAlerts(deviceId);
      const hasTelemetry = await loadTelemetry(deviceId);
      const loadedRisk = await loadAnalytics(deviceId);
      if (hasTelemetry && !loadedRisk) {
        await enqueueAndWaitForAnalyticsJob(deviceId).catch(() => undefined);
        await loadAnalytics(deviceId);
      }
      await loadRegistry(deviceId);
      await loadSimulation(deviceId);
    } catch (requestError) {
      details.setError(requestError instanceof Error ? requestError.message : "Failed to load device");
    } finally {
      setIsLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [deviceId]);
  const riskInsight = getRiskInsight(risk, anomalies, recommendations);
  const latestAnomaly = anomalies[0] ?? null;
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
  const configureAlertRoute = deviceId && analyticsFocusedMetric && chartTimeWindow
    ? `/alerts?view=rules&deviceId=${encodeURIComponent(deviceId)}&metric=${encodeURIComponent(analyticsFocusedMetric.key)}&from=${encodeURIComponent(chartTimeWindow.from)}&to=${encodeURIComponent(chartTimeWindow.to)}`
    : null;

  function changeTab(tab: DeviceDetailsTab) {
    setSearchParams(tab === "overview" ? {} : { tab });
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
          {suppressingMaintenanceWindow ? (
            <span className="status-pill status-pill-maintenance">Maintenance active</span>
          ) : upcomingMaintenanceWindows.length > 0 ? (
            <span className="status-pill status-pill-pending">{upcomingMaintenanceWindows.length} scheduled</span>
          ) : null}
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
          {analyticsJob ? (
            <span className={`status-pill status-pill-${analyticsJob.status.toLowerCase()}`}>
              Job {formatEnum(analyticsJob.status)}
            </span>
          ) : null}
        </div>
      </header>

      {error ? <div className="error-message">{error}</div> : null}
      {isLoading ? <section className="panel">Loading device...</section> : null}

      {!isLoading && device ? (
        <>
          {activeTab === "overview" && incident ? (
            <>
              <DeviceIncidentSummary incident={incident} isChecked={isChecked} onChecked={() => setIsChecked(true)} />

              <section className="metric-grid device-overview-grid compact-kpi-row">
                <Metric label={incident.problemMetricName} valueText={incident.currentValue} emphasis />
                <Metric label="Risk score" valueText={risk ? `${Math.round(risk.score)} / 100 - ${formatEnum(risk.level)}` : "No score"} />
                <Metric label="Last activity" valueText={latest ? formatShortTime(latest.timestamp) : "No telemetry"} />
                <Metric label="Samples analyzed" value={incident.sampleCount} unit="" />
              </section>
            </>
          ) : null}

          <DeviceTabs activeTab={activeTab} onChange={changeTab} />

          {suppressingMaintenanceWindow && (activeTab === "telemetry" || activeTab === "analytics" || activeTab === "alerts") ? (
            <section className="maintenance-notice">
              <div>
                <strong>{suppressingMaintenanceWindow.title}</strong>
                <span>
                  {formatShortDateTime(suppressingMaintenanceWindow.startAt)} - {formatShortDateTime(suppressingMaintenanceWindow.endAt)}
                </span>
              </div>
              <button className="ghost-button" type="button" onClick={() => changeTab("maintenance")}>
                Open maintenance
              </button>
            </section>
          ) : null}

          {activeTab === "maintenance" ? (
            <section className="maintenance-layout">
              <section className="panel maintenance-form-panel">
                <div className="section-header">
                  <div>
                    <h2>Maintenance window</h2>
                    <p>Schedule device maintenance and define how downstream processing treats the gap.</p>
                  </div>
                  <button className="ghost-button" type="button" onClick={() => void refreshMaintenanceWindows()} disabled={isMaintenanceBusy}>
                    Refresh
                  </button>
                </div>
                {maintenanceMessage ? <div className="status-note">{maintenanceMessage}</div> : null}
                <div className="maintenance-form-grid">
                  <label>
                    <span>Title</span>
                    <input value={maintenanceTitle} onChange={(event) => setMaintenanceTitle(event.target.value)} />
                  </label>
                  <label>
                    <span>Description</span>
                    <input value={maintenanceDescription} onChange={(event) => setMaintenanceDescription(event.target.value)} />
                  </label>
                  <label>
                    <span>Start</span>
                    <input type="datetime-local" value={maintenanceStartAt} onChange={(event) => setMaintenanceStartAt(event.target.value)} />
                  </label>
                  <label>
                    <span>End</span>
                    <input type="datetime-local" value={maintenanceEndAt} onChange={(event) => setMaintenanceEndAt(event.target.value)} />
                  </label>
                </div>
                <div className="maintenance-policy-grid">
                  <label>
                    <input type="checkbox" checked={maintenanceSuppressAlerts} onChange={(event) => setMaintenanceSuppressAlerts(event.target.checked)} />
                    <span>Suppress alerts</span>
                  </label>
                  <label>
                    <input type="checkbox" checked={maintenanceExcludeAnalytics} onChange={(event) => setMaintenanceExcludeAnalytics(event.target.checked)} />
                    <span>Exclude analytics</span>
                  </label>
                  <label>
                    <input type="checkbox" checked={maintenanceExcludeMl} onChange={(event) => setMaintenanceExcludeMl(event.target.checked)} />
                    <span>Exclude ML</span>
                  </label>
                </div>
                <button className="primary-button" type="button" onClick={() => void submitMaintenanceWindow()} disabled={isMaintenanceBusy}>
                  {isMaintenanceBusy ? "Saving..." : "Schedule maintenance"}
                </button>
              </section>

              <section className="panel maintenance-list-panel">
                <div className="section-header">
                  <div>
                    <h2>Windows</h2>
                    <p>{activeMaintenanceWindows.length} active, {upcomingMaintenanceWindows.length} scheduled.</p>
                  </div>
                </div>
                {sortedMaintenanceWindows.length > 0 ? (
                  <div className="maintenance-window-list">
                    {sortedMaintenanceWindows.map((window) => (
                      <article className={`maintenance-window-row maintenance-window-${window.status.toLowerCase()}`} key={window.id}>
                        <div>
                          <strong>{window.title}</strong>
                          <span>{window.description || "No description"}</span>
                          <small>
                            {formatShortDateTime(window.startAt)} - {formatShortDateTime(window.endAt)}
                          </small>
                        </div>
                        <div className="maintenance-window-meta">
                          <span className={`status-pill status-pill-${window.status.toLowerCase()}`}>{formatEnum(window.status)}</span>
                          <span>{window.suppressAlerts ? "Alerts off" : "Alerts on"}</span>
                          <span>{window.excludeFromAnalytics ? "Analytics excluded" : "Analytics included"}</span>
                          <span>{window.excludeFromMl ? "ML excluded" : "ML included"}</span>
                        </div>
                        <div className="action-row">
                          {window.status === "ACTIVE" ? (
                            <button className="ghost-button" type="button" onClick={() => void finishWindowNow(window.id)} disabled={isMaintenanceBusy}>
                              Finish now
                            </button>
                          ) : null}
                          {window.status === "ACTIVE" || window.status === "SCHEDULED" ? (
                            <button className="ghost-button" type="button" onClick={() => void cancelWindow(window.id)} disabled={isMaintenanceBusy}>
                              Cancel
                            </button>
                          ) : null}
                        </div>
                      </article>
                    ))}
                  </div>
                ) : (
                  <div className="empty-state">
                    <strong>No maintenance windows</strong>
                    <span>Schedule maintenance before planned telemetry gaps, device inspections, or firmware work.</span>
                  </div>
                )}
              </section>
            </section>
          ) : null}

          {activeTab === "simulation" ? (
            <section className="panel simulation-panel">
              <div className="section-header">
                <div>
                  <h2>Demo tools</h2>
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

          {activeTab === "configuration" ? (
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
                  showPoints={showChartPoints}
                  selectedMetricKeys={selectedChartMetricKeys}
                  metricSearch={metricSearch}
                  openedMetricKey={expandedChartMetricKey}
                  selectedEventId={selectedTelemetryEventId}
                  maxDisplayPoints={CHART_DENSITY_LIMITS[chartDensity]}
                  onRangeChange={setChartTimeRange}
                  onCustomFromChange={setCustomChartFrom}
                  onCustomToChange={setCustomChartTo}
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
                    <dd>{simulationSourceId}</dd>
                  </div>
                </dl>
              </div>
            </div>
                </details>
              </aside>
            </section>
          ) : null}

          {activeTab === "telemetry" && telemetryOverview ? (
            <>
              <section className="panel telemetry-action-panel">
                <div>
                  <strong>{analyticsFocusedMetric?.label ?? "Telemetry"} alerting</strong>
                  <span>Create or tune a rule from the current metric and time window.</span>
                </div>
                {configureAlertRoute ? (
                  <Link className="primary-button button-link" to={configureAlertRoute}>
                    Configure alert
                  </Link>
                ) : null}
              </section>
              <TelemetryOverviewPage
                overview={telemetryOverview}
                range={chartTimeRange}
                customFrom={customChartFrom}
                customTo={customChartTo}
                showPoints={showChartPoints}
                selectedMetricKeys={selectedChartMetricKeys}
                metricSearch={metricSearch}
                openedMetricKey={expandedChartMetricKey}
                selectedEventId={selectedTelemetryEventId}
                maxDisplayPoints={CHART_DENSITY_LIMITS[chartDensity]}
                onRangeChange={setChartTimeRange}
                onCustomFromChange={setCustomChartFrom}
                onCustomToChange={setCustomChartTo}
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
            </>
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
                    {analyticsJob ? (
                      <span className={`status-pill status-pill-${analyticsJob.status.toLowerCase()}`}>
                        Job {formatEnum(analyticsJob.status)}
                      </span>
                    ) : null}
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
                  </div>
                </div>

                <MetricChart
                  points={analyticsFocusedPoints}
                  label={analyticsFocusedMetric?.label ?? "Metric"}
                  unit={analyticsFocusedMetric?.unit ?? ""}
                  timeRange={chartTimeWindow}
                  baseline={analyticsBaseline?.mean}
                  threshold={
                    analyticsFocusedOverview?.threshold?.criticalMax ??
                    analyticsFocusedOverview?.threshold?.warningMax ??
                    analyticsBaseline?.p90
                  }
                  anomaly={latestAnomaly}
                  maintenanceWindows={maintenanceWindows}
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

function normalizeDeviceTab(value: string | null): DeviceDetailsTab {
  if (value === "telemetry" || value === "analytics" || value === "alerts" || value === "maintenance" || value === "configuration" || value === "simulation") {
    return value;
  }
  if (value === "registry" || value === "config") {
    return "configuration";
  }
  return "overview";
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
