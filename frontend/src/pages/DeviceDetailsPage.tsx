import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
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
import { Device, getDevice } from "../devices/devicesApi";
import {
  getLatestTelemetry,
  getTelemetryReadings,
  LatestTelemetry,
  sendTelemetryReading,
  TelemetryChart,
} from "../telemetry/telemetryApi";

export function DeviceDetailsPage() {
  const { deviceId } = useParams();
  const [device, setDevice] = useState<Device | null>(null);
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
  const [error, setError] = useState<string | null>(null);

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
      const deviceResponse = await getDevice(deviceId);
      setDevice(deviceResponse);
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
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load device");
    } finally {
      setIsLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [deviceId]);

  const readings = latest?.readings ?? {};
  const curlHint = useMemo(() => {
    if (!deviceId) {
      return "";
    }
    const sourceId = localStorage.getItem(`voltcanary.deviceSource.${deviceId}`) ?? "plug-001";
    return `curl -X POST http://localhost:8080/ingestion/readings -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d "{\\"deviceId\\":\\"${deviceId}\\",\\"sourceId\\":\\"${sourceId}\\",\\"timestamp\\":\\"${new Date().toISOString()}\\",\\"metrics\\":{\\"activePowerW\\":840.2,\\"voltageV\\":230.1,\\"currentA\\":3.65,\\"energyWh\\":12450.7,\\"rssi\\":-61}}"`;
  }, [deviceId]);

  async function sendTestTelemetry() {
    if (!deviceId) {
      return;
    }
    setIsSendingTelemetry(true);
    setError(null);
    try {
      const sourceId = localStorage.getItem(`voltcanary.deviceSource.${deviceId}`) ?? "plud1";
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
      setError(requestError instanceof Error ? requestError.message : "Failed to send test telemetry");
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
      setError(requestError instanceof Error ? requestError.message : "Failed to recalculate analytics");
    } finally {
      setIsRecalculating(false);
    }
  }

  return (
    <>
      <header className="page-header page-header-row">
        <div>
          <Link className="back-link" to="/devices">
            Devices
          </Link>
          <h1>{device?.name ?? "Device Details"}</h1>
          <p>{deviceId}</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
          Refresh
        </button>
      </header>

      {error ? <div className="error-message">{error}</div> : null}
      {isLoading ? <section className="panel">Loading device...</section> : null}

      {!isLoading && device ? (
        <>
          <section className="metric-grid">
            <Metric label="Active power" value={readings.activePowerW} unit="W" />
            <Metric label="Voltage" value={readings.voltageV} unit="V" />
            <Metric label="Current" value={readings.currentA} unit="A" />
            <Metric label="Energy" value={readings.energyWh} unit="Wh" />
          </section>

          <section className="panel">
            <div className="section-header">
              <div>
                <h2>Power</h2>
                <p>{latest ? `Latest sample ${new Date(latest.timestamp).toLocaleString()}` : "No telemetry yet"}</p>
              </div>
              <button className="primary-button" type="button" onClick={() => void sendTestTelemetry()} disabled={isSendingTelemetry}>
                {isSendingTelemetry ? "Sending..." : "Send test telemetry"}
              </button>
            </div>
            {chart && chart.points.length > 0 ? (
              <PowerChart points={chart.points} />
            ) : (
              <div className="empty-state">
                <strong>No telemetry yet</strong>
                <span>Send test telemetry to see chart</span>
                <code>{curlHint}</code>
              </div>
            )}
          </section>

          <section className="content-grid analytics-grid">
            <div className="panel">
              <div className="section-header">
                <div>
                  <h2>Risk</h2>
                  <p>{risk ? `Calculated ${new Date(risk.calculatedAt).toLocaleString()}` : "No analytics yet"}</p>
                </div>
                <button
                  className="primary-button"
                  type="button"
                  onClick={() => void recalculateDeviceAnalytics()}
                  disabled={isRecalculating}
                >
                  {isRecalculating ? "Recalculating..." : "Recalculate"}
                </button>
              </div>

              {risk ? (
                <>
                  <div className="risk-summary">
                    <strong>{Math.round(risk.score)}</strong>
                    <span>{risk.level}</span>
                  </div>
                  {risk.explanations.length > 0 ? (
                    <ul className="plain-list">
                      {risk.explanations.map((explanation) => (
                        <li key={explanation}>{explanation}</li>
                      ))}
                    </ul>
                  ) : (
                    <p className="muted-text">No anomalies detected in the current analytics window.</p>
                  )}
                </>
              ) : (
                <div className="empty-state">
                  <strong>No analytics yet</strong>
                  <span>Recalculate analytics after telemetry arrives</span>
                </div>
              )}
            </div>

            <div className="panel">
              <h2>Baseline</h2>
              {baseline ? (
                <div className="baseline-grid">
                  <Metric label="Mean" value={baseline.mean} unit="W" />
                  <Metric label="Median" value={baseline.median} unit="W" />
                  <Metric label="P90" value={baseline.p90} unit="W" />
                  <Metric label="Samples" value={baseline.sampleCount} unit="" />
                </div>
              ) : (
                <p className="muted-text">No baseline calculated.</p>
              )}
            </div>
          </section>

          <section className="panel analytics-grid">
            <h2>ML Prediction</h2>
            {prediction ? (
              <div className="prediction-grid">
                <Metric label="Model" valueText={prediction.modelVersion} />
                <Metric label="Confidence" value={prediction.confidence * 100} unit="%" />
                <Metric label="Anomaly score" value={prediction.anomalyScore} unit="" />
                <Metric label="Degradation" value={prediction.degradationScore} unit="" />
              </div>
            ) : (
              <p className="muted-text">No ML prediction calculated.</p>
            )}
          </section>

          <section className="content-grid analytics-grid">
            <div className="panel">
              <h2>Anomalies</h2>
              {anomalies.length > 0 ? (
                <ul className="plain-list">
                  {anomalies.map((anomaly) => (
                    <li key={anomaly.id}>
                      <strong>{anomaly.type}</strong>
                      <span>{anomaly.description}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="muted-text">No anomalies detected.</p>
              )}
            </div>

            <div className="panel">
              <h2>Recommendations</h2>
              {recommendations.length > 0 ? (
                <ul className="plain-list">
                  {recommendations.map((recommendation) => (
                    <li key={recommendation.id}>
                      <strong>{recommendation.title}</strong>
                      <span>{recommendation.description}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="muted-text">No recommendations yet.</p>
              )}
            </div>
          </section>
        </>
      ) : null}
    </>
  );
}

function Metric({ label, value, unit = "", valueText }: { label: string; value?: number; unit?: string; valueText?: string }) {
  return (
    <article className="metric-card">
      <span>{label}</span>
      <strong>{valueText ?? (value === undefined ? "No data" : `${value.toLocaleString()}${unit ? ` ${unit}` : ""}`)}</strong>
    </article>
  );
}

function PowerChart({ points }: { points: { timestamp: string; value: number }[] }) {
  const width = 720;
  const height = 260;
  const padding = 28;
  const values = points.map((point) => point.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const coordinates = points.map((point, index) => {
    const x = padding + (index / Math.max(points.length - 1, 1)) * (width - padding * 2);
    const y = height - padding - ((point.value - min) / range) * (height - padding * 2);
    return `${x},${y}`;
  });

  return (
    <div className="chart-wrap">
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Active power chart">
        <line x1={padding} y1={height - padding} x2={width - padding} y2={height - padding} />
        <line x1={padding} y1={padding} x2={padding} y2={height - padding} />
        <polyline points={coordinates.join(" ")} />
        {points.map((point, index) => {
          const [x, y] = coordinates[index].split(",").map(Number);
          return <circle key={`${point.timestamp}-${index}`} cx={x} cy={y} r="4" />;
        })}
      </svg>
    </div>
  );
}
