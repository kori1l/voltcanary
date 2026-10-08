import { Link } from "react-router-dom";
import { Device } from "../devicesApi";
import { LatestTelemetry } from "../../telemetry/telemetryApi";
import { RiskResponse } from "../../analytics/analyticsApi";
import { TimeDisplay } from "../../components/TimeDisplay";
import { useEvidenceClock } from "../../components/useEvidenceClock";
export function DeviceOverview({ device, latest, risk }: { device: Device; latest: LatestTelemetry | null; risk: RiskResponse | null }) {
  useEvidenceClock();
  const fresh = latest && Date.now() - Date.parse(latest.timestamp) < 300_000 && Date.parse(latest.timestamp) <= Date.now() + 60_000;
  return <section className="panel form-stack"><h2>Current evidence</h2><dl className="metadata-list">
    <div><dt>Registration</dt><dd>{device.status}</dd></div><div><dt>Telemetry freshness</dt><dd>{latest ? fresh ? "Recent sample" : "Old sample" : "Unavailable"}</dd></div>
    <div><dt>Last measurement</dt><dd>{latest ? <TimeDisplay value={latest.timestamp} /> : "Unavailable"}</dd></div>
    <div><dt>Inferred risk</dt><dd>{risk ? `${risk.level} · ${risk.score} / 100` : "Unavailable — no risk evidence"}</dd></div>
  </dl><p>{latest ? "Review measured signals, then compare analytics and its confidence before deciding on a check." : "Check the configured source and sender. Device registration alone does not establish healthy operation."}</p>
  <div className="action-row"><Link to={`?tab=${latest ? "telemetry" : "configuration"}`}>{latest ? "Review telemetry" : "Check data source"}</Link><Link to="?tab=analytics">Review analytics</Link><Link to="?tab=alerts">Review incidents</Link></div></section>;
}
