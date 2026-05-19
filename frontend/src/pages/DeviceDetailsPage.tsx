import { useEffect, useMemo, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
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
import { Device, getDevice, listLocations, Location, updateDataSource } from "../devices/devicesApi";
import {
  getLatestTelemetry,
  getTelemetryReadings,
  LatestTelemetry,
  sendTelemetryReading,
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

type ChartPoint = { timestamp: string; value: number };

export function DeviceDetailsPage() {
  const { deviceId } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = searchParams.get("tab") ?? "overview";
  const [device, setDevice] = useState<Device | null>(null);
  const [locations, setLocations] = useState<Location[]>([]);
  const [latest, setLatest] = useState<LatestTelemetry | null>(null);
  const [chart, setChart] = useState<TelemetryChart | null>(null);
  const [baseline, setBaseline] = useState<BaselineResponse | null>(null);
  const [risk, setRisk] = useState<RiskResponse | null>(null);
  const [anomalies, setAnomalies] = useState<AnomalyResponse[]>([]);
  const [recommendations, setRecommendations] = useState<RecommendationResponse[]>([]);
  const [prediction, setPrediction] = useState<PredictionResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isSendingTelemetry, setIsSendingTelemetry] = useState(false);
  const [isRecalculating, setIsRecalculating] = useState(false);
  const [isChecked, setIsChecked] = useState(false);
  const [showDemoTools, setShowDemoTools] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [simulationConfig, setSimulationConfig] = useState<SimulationConfig | null>(null);
  const [simulationStatus, setSimulationStatus] = useState<SimulationRun | null>(null);
  const [simulationRuns, setSimulationRuns] = useState<SimulationRun[]>([]);
  const [simulationYaml, setSimulationYaml] = useState("");
  const [simulationProfile, setSimulationProfile] = useState("FRIDGE");
  const [simulationProtocol, setSimulationProtocol] = useState("HTTP");
  const [simulationMessage, setSimulationMessage] = useState<string | null>(null);
  const [isSimulationBusy, setIsSimulationBusy] = useState(false);

  async function loadAnalytics(currentDeviceId: string) {
    try {
      const [baselineResponse, riskResponse, anomaliesResponse, recommendationsResponse, predictionResponse] = await Promise.all([
        getDeviceBaseline(currentDeviceId),
        getDeviceRisk(currentDeviceId),
        getDeviceAnomalies(currentDeviceId),
        getDeviceRecommendations(currentDeviceId),
        getLatestPrediction(currentDeviceId),
      ]);
      setBaseline(baselineResponse);
      setRisk(riskResponse);
      setAnomalies(anomaliesResponse);
      setRecommendations(recommendationsResponse);
      setPrediction(predictionResponse);
    } catch {
      setBaseline(null);
      setRisk(null);
      setAnomalies([]);
      setRecommendations([]);
      setPrediction(null);
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
      try {
        const [latestResponse, chartResponse] = await Promise.all([
          getLatestTelemetry(deviceId),
          getTelemetryReadings(deviceId, "activePowerW"),
        ]);
        setLatest(latestResponse);
        setChart(chartResponse);
      } catch {
        setLatest(null);
        setChart(null);
      }
      await loadAnalytics(deviceId);
      await loadSimulation(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load device");
    } finally {
      setIsLoading(false);
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

  const readings = latest?.readings ?? {};
  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, fixKnownTypos(location.name)])), [locations]);
  const locationName = device ? locationById.get(device.locationId ?? "") ?? "No location" : "No location";
  const riskInsight = getRiskInsight(risk, anomalies, recommendations);
  const latestAnomaly = anomalies[0] ?? null;
  const sourceId = deviceId ? localStorage.getItem(`voltcanary.deviceSource.${deviceId}`) ?? "plug-001" : "plug-001";
  const curlHint = useMemo(() => {
    if (!deviceId) {
      return "";
    }
    return `curl -X POST http://localhost:8080/ingestion/readings -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d "{\\"deviceId\\":\\"${deviceId}\\",\\"sourceId\\":\\"${sourceId}\\",\\"timestamp\\":\\"${new Date().toISOString()}\\",\\"metrics\\":{\\"activePowerW\\":840.2,\\"voltageV\\":230.1,\\"currentA\\":3.65,\\"energyWh\\":12450.7,\\"rssi\\":-61}}"`;
  }, [deviceId, sourceId]);

  async function sendTestTelemetry() {
    if (!deviceId) {
      return;
    }
    setIsSendingTelemetry(true);
    setError(null);
    try {
      await sendTelemetryReading({
        deviceId,
        sourceId,
        timestamp: new Date().toISOString(),
        metrics: {
          activePowerW: 840.2,
          voltageV: 230.1,
          currentA: 3.65,
          energyWh: 12450.7,
          rssi: -61,
        },
      });
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to simulate telemetry");
    } finally {
      setIsSendingTelemetry(false);
    }
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

  async function validateYaml() {
    if (!deviceId) return;
    setIsSimulationBusy(true);
    setSimulationMessage(null);
    try {
      const result = await validateSimulationConfig(deviceId, simulationYaml);
      setSimulationMessage(result.valid ? "YAML is valid" : result.errors.map((item) => `${item.path}: ${item.message}`).join("; "));
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
          <section className={`insight-banner insight-banner-${riskInsight.tone}`}>
            <div>
              <span className="status-pill">{risk ? `${formatEnum(risk.level)} risk` : "No risk score"}</span>
              <h2>{riskInsight.title}</h2>
              <p>{riskInsight.description}</p>
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

          <nav className="tab-row" aria-label="Device sections">
            {["overview", "telemetry", "analytics", "alerts", "simulation"].map((tab) => (
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
                      ? `${formatEnum(simulationConfig.profile)} В· ${formatEnum(simulationConfig.status)} В· Version ${simulationConfig.yamlVersion}`
                      : "No simulation config exists for this device."}
                  </p>
                </div>
                <div className="action-row">
                  <button className="ghost-button" type="button" onClick={() => void validateYaml()} disabled={!simulationConfig || isSimulationBusy}>
                    Validate YAML
                  </button>
                  <button className="ghost-button" type="button" onClick={() => void saveYaml()} disabled={!simulationConfig || isSimulationBusy}>
                    Save Config
                  </button>
                  <button className="ghost-button" type="button" onClick={() => void resetYamlTemplate()} disabled={!simulationConfig || isSimulationBusy}>
                    Reset Template
                  </button>
                  <button className="primary-button" type="button" onClick={() => void toggleSimulation()} disabled={!simulationConfig || isSimulationBusy}>
                    {simulationStatus?.status === "RUNNING" ? "Stop" : "Start"}
                  </button>
                </div>
              </div>

              {simulationMessage ? <div className="status-note">{simulationMessage}</div> : null}

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
                      <select value={simulationProfile} onChange={(event) => setSimulationProfile(event.target.value)}>
                        {["GENERIC_APPLIANCE", "FRIDGE", "AIR_CONDITIONER", "BOILER", "PUMP", "HEATER"].map((profile) => (
                          <option value={profile} key={profile}>
                            {formatEnum(profile)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Protocol
                      <select value={simulationProtocol} onChange={(event) => setSimulationProtocol(event.target.value)}>
                        <option value="HTTP">HTTP</option>
                        <option value="MQTT">MQTT</option>
                      </select>
                    </label>
                  </div>
                  <textarea
                    className="yaml-editor"
                    value={simulationYaml}
                    onChange={(event) => setSimulationYaml(event.target.value)}
                    spellCheck={false}
                  />
                  <div className="run-history">
                    <h3>Run history</h3>
                    {simulationRuns.length > 0 ? (
                      <ul className="plain-list compact-list">
                        {simulationRuns.map((run) => (
                          <li key={run.runId ?? `${run.startedAt}-${run.status}`}>
                            <strong>{formatEnum(run.status)}</strong>
                            <span>
                              {run.generatedPoints} points В· {run.startedAt ? formatShortDateTime(run.startedAt) : "No start time"}
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

          {activeTab !== "simulation" ? <section className="metric-grid device-overview-grid">
            <Metric label="Active power" value={readings.activePowerW} unit="W" emphasis />
            <Metric label="Risk score" valueText={risk ? `${Math.round(risk.score)} / 100` : "No score"} />
            <Metric label="Last activity" valueText={latest ? formatShortTime(latest.timestamp) : "No telemetry"} />
            <Metric label="Samples analyzed" value={baseline?.sampleCount} unit="" />
          </section> : null}

          {activeTab !== "simulation" ? <section className="content-grid action-explanation-grid">
            <div className="panel">
              <h2>Why this matters</h2>
              <ul className="explanation-list">
                {riskInsight.reasons.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
            </div>
            <div className="panel">
              <h2>Next action</h2>
              <p className="muted-text">{riskInsight.nextAction}</p>
              <div className="action-row action-row-start">
                <button className="ghost-button" type="button" onClick={() => setIsChecked(true)}>
                  Check power conditions
                </button>
                <button className="ghost-button" type="button" onClick={() => setIsChecked(true)}>
                  Create maintenance note
                </button>
              </div>
            </div>
          </section> : null}

          {activeTab !== "simulation" ? <section className="panel">
            <div className="section-header">
              <div>
                <h2>Power over time</h2>
                <p>
                  {latest
                    ? `Latest sample: ${formatShortDateTime(latest.timestamp)}. Baseline and threshold explain alerts.`
                    : "No telemetry has arrived for this device yet."}
                </p>
              </div>
            </div>
            {chart && chart.points.length > 0 ? (
              <PowerChart points={chart.points} baseline={baseline?.mean} threshold={baseline?.p90} anomaly={latestAnomaly} />
            ) : (
              <div className="empty-state">
                <strong>No telemetry yet</strong>
                <span>Use Demo tools only when presenting or testing the pipeline.</span>
              </div>
            )}
          </section> : null}

          {activeTab !== "simulation" ? <section className="panel analytics-grid">
            <div className="section-header">
              <div>
                <h2>Risk analysis</h2>
                <p>{risk ? `Last calculated: ${formatShortDateTime(risk.calculatedAt)}` : "No analytics calculated yet."}</p>
              </div>
            </div>

            {risk ? (
              <div className="risk-analysis-layout">
                <div className="risk-score-card">
                  <strong>{Math.round(risk.score)} / 100</strong>
                  <span>{formatEnum(risk.level)}</span>
                </div>
                <div>
                  <h3>Main reasons</h3>
                  <ul className="plain-list compact-list">
                    {riskInsight.reasons.map((reason) => (
                      <li key={reason}>{reason}</li>
                    ))}
                  </ul>
                  <h3>Recommendation</h3>
                  <p className="muted-text">{riskInsight.nextAction}</p>
                </div>
              </div>
            ) : (
              <div className="empty-state">
                <strong>No analytics yet</strong>
                <span>Recalculate risk after telemetry arrives.</span>
              </div>
            )}
          </section> : null}

          {activeTab !== "simulation" ? <details className="panel technical-details">
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
          </details> : null}

          {activeTab !== "simulation" ? <details className="panel demo-tools" open={showDemoTools} onToggle={(event) => setShowDemoTools(event.currentTarget.open)}>
            <summary>Demo tools</summary>
            <div className="section-header">
              <div>
                <h2>Simulate telemetry</h2>
                <p>Use this only for demos or local testing.</p>
              </div>
              <button className="primary-button" type="button" onClick={() => void sendTestTelemetry()} disabled={isSendingTelemetry}>
                {isSendingTelemetry ? "Sending..." : "Simulate telemetry"}
              </button>
            </div>
            <code>{curlHint}</code>
          </details> : null}
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

function PowerChart({
  points,
  baseline,
  threshold,
  anomaly,
}: {
  points: ChartPoint[];
  baseline?: number;
  threshold?: number;
  anomaly?: AnomalyResponse | null;
}) {
  const [hovered, setHovered] = useState<{ point: ChartPoint; x: number; y: number } | null>(null);
  const width = 820;
  const height = 330;
  const padding = { top: 28, right: 28, bottom: 54, left: 58 };
  const values = [...points.map((point) => point.value), baseline, threshold].filter((value): value is number => value !== undefined);
  const minValue = Math.min(...values, 0);
  const maxValue = Math.max(...values, 1);
  const range = maxValue - minValue || 1;
  const yForValue = (value: number) => height - padding.bottom - ((value - minValue) / range) * (height - padding.top - padding.bottom);
  const xForIndex = (index: number) =>
    padding.left + (index / Math.max(points.length - 1, 1)) * (width - padding.left - padding.right);
  const coordinates = points.map((point, index) => ({
    point,
    x: xForIndex(index),
    y: yForValue(point.value),
  }));
  const spike = coordinates.reduce((current, next) => (next.point.value > current.point.value ? next : current), coordinates[0]);
  const yTicks = [minValue, minValue + range / 2, maxValue];
  const xTicks = [coordinates[0], coordinates[Math.floor(coordinates.length / 2)], coordinates[coordinates.length - 1]].filter(Boolean);

  return (
    <div className="chart-wrap annotated-chart">
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Active power chart">
        {yTicks.map((tick) => {
          const y = yForValue(tick);
          return (
            <g key={tick}>
              <line className="chart-grid-line" x1={padding.left} y1={y} x2={width - padding.right} y2={y} />
              <text className="chart-axis-label" x={padding.left - 10} y={y + 4} textAnchor="end">
                {Math.round(tick)} W
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

        {coordinates.map((coordinate, index) => {
          const isSpike = coordinate === spike && (threshold === undefined || coordinate.point.value >= threshold);
          return (
            <circle
              key={`${coordinate.point.timestamp}-${index}`}
              className={isSpike ? "chart-spike-point" : undefined}
              cx={coordinate.x}
              cy={coordinate.y}
              r={isSpike ? 6 : 4}
              onMouseEnter={() => setHovered(coordinate)}
              onMouseLeave={() => setHovered(null)}
              onFocus={() => setHovered(coordinate)}
              tabIndex={0}
            >
              <title>{`${formatShortTime(coordinate.point.timestamp)} - ${formatNumber(coordinate.point.value)} W`}</title>
            </circle>
          );
        })}

        {spike ? (
          <text className="chart-spike-label" x={spike.x} y={Math.max(spike.y - 14, 16)} textAnchor="middle">
            {anomaly ? "Power spike" : "Peak"}
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
          <span>{formatNumber(hovered.point.value)} W</span>
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

function fixKnownTypos(value: string) {
  return value.replace(/\bKithen\b/gi, "Kitchen");
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
