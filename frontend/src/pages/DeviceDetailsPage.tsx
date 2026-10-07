import { listLocations as detailsLoadLocations } from "../devices/devicesApi";
import { DeviceAlertsPanel } from "../devices/details/DeviceAlertsPanel";
import { normalizeDeviceTab, buildIncidentSummary, buildIncidentConclusion, buildWhyBullets, buildAnalyticsSummary, buildPredictionInsight, normalizeRiskTone, getRiskInsight, formatInsightText } from "../devices/details/deviceInsightPresentation";
import { DeviceConfigurationPanel } from "../devices/details/DeviceConfigurationPanel";
import { DeviceSimulationPanel } from "../devices/details/DeviceSimulationPanel";
import { DeviceMetricCard as Metric } from "../devices/details/DeviceMetricCard";
import { DeviceMaintenancePanel } from "../devices/details/DeviceMaintenancePanel";
import { useEffect, useMemo, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { AnomalyResponse, PredictionResponse, RecommendationResponse, RiskResponse } from "../analytics/analyticsApi";
import { TelemetryOverviewPage } from "../features/telemetry/pages/TelemetryOverviewPage";
import { mapTelemetryOverview } from "../features/telemetry/model/telemetry.mappers";
import { TelemetryOverviewResponse } from "../features/telemetry/model/telemetry.types";
import { formatMetricValue as formatTelemetryMetricValue } from "../features/telemetry/model/telemetry.utils";
import { DeviceMetricChart as MetricChart } from "../devices/details/DeviceMetricChart";


import { DeviceDetailsTab, DeviceTabs } from "../devices/details/DeviceTabs";
import { CHART_DENSITY_LIMITS, CHART_TIME_RANGES, ChartTimeRange } from "../devices/details/deviceTelemetryModel";
import { formatEnum, formatShortDateTime, formatShortTime } from "../devices/details/deviceDetailsFormatting";
import { useDeviceAlertsWorkspace } from "../devices/details/useDeviceAlertsWorkspace";
import { useDeviceAnalyticsWorkspace } from "../devices/details/useDeviceAnalyticsWorkspace";
import { useDeviceDetailsData } from "../devices/details/useDeviceDetailsData";
import { useDeviceMaintenanceActions } from "../devices/details/useDeviceMaintenanceActions";
import { useDeviceRegistryActions } from "../devices/details/useDeviceRegistryActions";
import { useDeviceSimulationWorkspace } from "../devices/details/useDeviceSimulationWorkspace";
import { useDeviceTelemetryWorkspace } from "../devices/details/useDeviceTelemetryWorkspace";
import { SectionFeedback } from "../components/SectionFeedback";


import { DeviceOverview } from "../devices/details/DeviceOverview";

export function DeviceDetailsPage() {
  const { deviceId } = useParams();
  return <DeviceDetailsWorkspace key={deviceId} deviceId={deviceId} />;
}

function DeviceDetailsWorkspace({ deviceId }: { deviceId?: string }) {
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

  const { device, locationName, isLoading, setIsLoading, loadDeviceContext, changeDeviceStatus } = details;
  const { latest, charts, selectedChartMetricKeys, setSelectedChartMetricKeys, showChartPoints, setShowChartPoints, chartDensity, chartTimeRange, setChartTimeRange, customChartFrom, setCustomChartFrom, customChartTo, setCustomChartTo, metricSearch, setMetricSearch, expandedChartMetricKey, setExpandedChartMetricKey, selectedTelemetryEventId, setSelectedTelemetryEventId, isTelemetryLoading, chartTimeWindow, availableChartMetrics, chartMetrics, expandedChartMetric, loadTelemetry, toggleChartMetric, selectAllChartMetrics, resetChartMetrics } = telemetry;
  const { baseline, risk, anomalies, recommendations, prediction, analyticsJob, isRecalculating, loadAnalytics, enqueueAndWaitForAnalyticsJob, recalculateDeviceAnalytics } = analytics;
  const { maintenanceWindows, upcomingMaintenanceWindows, suppressingMaintenanceWindow, refreshMaintenanceWindows } = maintenance;
  const { deviceAlerts, deviceAlertStatusFilter, setDeviceAlertStatusFilter, setShowAllDeviceAlerts, filteredDeviceAlerts, visibleDeviceAlerts, loadDeviceAlerts, updateDeviceAlert } = alerts;
  const { loadSimulation } = simulation;
  const { lastSourceToken: registryLastSourceToken, isRegistryBusy, loadRegistry } = registry;
  const lastSourceToken = registryLastSourceToken ?? simulationSourceToken;
  const error = details.actionError ?? analytics.error ?? alerts.error ?? registry.error;

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
      await loadTelemetry(deviceId);
      await loadAnalytics(deviceId);
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
      {details.confirmationDialog}
      <header className="page-header page-header-row">
        <div>
          <Link className="back-link" to="/devices">
            Devices
          </Link>
          <h1>{device?.name ?? "Device details"}</h1>
          <p>
            {device
              ? `${locationName} · ${formatEnum(device.status)} · Last telemetry: ${latest ? formatShortTime(latest.timestamp) : "No data"}`
              : details.resource.status === "error" ? "Device unavailable" : "Loading device status"}
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
            <button className="ghost-button" type="button" onClick={() => void changeDeviceStatus("enable")} disabled={isRegistryBusy || details.statusBusy}>
              Enable
            </button>
          ) : null}
          {device && device.status !== "DISABLED" && device.status !== "DECOMMISSIONED" ? (
            <button className="ghost-button" type="button" onClick={() => void changeDeviceStatus("disable")} disabled={isRegistryBusy || details.statusBusy}>
              Disable
            </button>
          ) : null}
          {device && device.status !== "DECOMMISSIONED" ? (
            <button className="ghost-button" type="button" onClick={() => void changeDeviceStatus("decommission")} disabled={isRegistryBusy || details.statusBusy}>
              Decommission
            </button>
          ) : null}
          <button
            className="primary-button"
            type="button"
            onClick={() => void recalculateDeviceAnalytics()}
            disabled={isRecalculating || isLoading || Boolean(analyticsJob && ["PENDING", "RUNNING"].includes(analyticsJob.status))}
          >
            {isRecalculating ? "Recalculating..." : "Recalculate risk"}
          </button>
          {analyticsJob && ["PENDING", "RUNNING"].includes(analyticsJob.status) ? (
            <button className="ghost-button" type="button" onClick={() => void analytics.refreshAnalyticsJob()}>Check calculation status</button>
          ) : null}
          {analyticsJob ? (
            <span className={`status-pill status-pill-${analyticsJob.status.toLowerCase()}`}>
              Job {formatEnum(analyticsJob.status)}
            </span>
          ) : null}
        </div>
      </header>

      <SectionFeedback resource={details.resource} label="device" onRetry={() => void loadDeviceContext()} />
      <SectionFeedback resource={details.locationResource} label="locations" onRetry={() => void details.locationResource.load(detailsLoadLocations)} />
      {error ? <div className="error-message">{error}</div> : null}
      {alerts.confirmationDialog}
      {activeTab === "alerts" ? <SectionFeedback resource={alerts.resource} label="device alerts" onRetry={() => void loadDeviceAlerts()} /> : null}
      {activeTab === "overview" || activeTab === "telemetry" || activeTab === "analytics" ? <SectionFeedback resource={telemetry.resource} label="telemetry" onRetry={() => void loadTelemetry()} emptyMessage="No samples in this range. Widen the interval or check the telemetry source." /> : null}
      {telemetry.telemetryFailures.length ? <div className="warning-message">Some metrics are unavailable: {telemetry.telemetryFailures.join("; ")}</div> : null}
      {activeTab === "analytics" ? analytics.sections.map(({ label, resource }) => <SectionFeedback key={label} resource={resource} label={label} onRetry={() => void loadAnalytics()} />) : null}
      {isLoading ? <section className="panel">Loading device...</section> : null}

      {!isLoading && device ? (
        <>
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

          {activeTab === "maintenance" ? <DeviceMaintenancePanel workspace={maintenance} /> : null}
          {activeTab === "simulation" ? <DeviceSimulationPanel simulation={simulation} /> : null}

          {activeTab === "configuration" ? <DeviceConfigurationPanel registry={registry} sourceToken={simulationSourceToken} /> : null}

          {activeTab === "overview" ? <DeviceOverview device={device} latest={latest} risk={risk} /> : null}

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
                      <strong>{risk ? `${Math.round(risk.score)} / 100` : "Risk unavailable"}</strong>
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

          {activeTab === "alerts" ? <DeviceAlertsPanel alerts={alerts} /> : null}
        </>
      ) : null}
    </>
  );
}
