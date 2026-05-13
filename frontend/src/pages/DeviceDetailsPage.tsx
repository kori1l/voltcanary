import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Device, getDevice } from "../devices/devicesApi";
import { getLatestTelemetry, getTelemetryReadings, LatestTelemetry, TelemetryChart } from "../telemetry/telemetryApi";

export function DeviceDetailsPage() {
  const { deviceId } = useParams();
  const [device, setDevice] = useState<Device | null>(null);
  const [latest, setLatest] = useState<LatestTelemetry | null>(null);
  const [chart, setChart] = useState<TelemetryChart | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

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
        </>
      ) : null}
    </>
  );
}

function Metric({ label, value, unit }: { label: string; value: number | undefined; unit: string }) {
  return (
    <article className="metric-card">
      <span>{label}</span>
      <strong>{value === undefined ? "No data" : `${value.toLocaleString()} ${unit}`}</strong>
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
