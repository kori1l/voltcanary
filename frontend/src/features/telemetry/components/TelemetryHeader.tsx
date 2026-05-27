import { DeviceHealth } from "../model/telemetry.types";
import { formatShortDateTime } from "../model/telemetry.utils";
import { StatusBadge } from "./StatusBadge";

export function TelemetryHeader({ health, selectedRangeLabel }: { health: DeviceHealth; selectedRangeLabel: string }) {
  return (
    <div className="telemetry-monitor-header">
      <div>
        <span className="telemetry-eyebrow">Telemetry monitoring</span>
        <div className="telemetry-monitor-title">
          <h2>Device health</h2>
          <StatusBadge status={health.status} />
        </div>
        <p>{health.reason}</p>
      </div>
      <div className="telemetry-health-meta">
        <span>
          Last sample <strong>{health.lastSampleAt ? formatShortDateTime(health.lastSampleAt) : "No data"}</strong>
        </span>
        <span>
          Data quality <strong>{health.dataQuality}</strong>
        </span>
        <span>
          Active alerts <strong>{health.activeAlertsCount}</strong>
        </span>
        <span>
          Range <strong>{selectedRangeLabel}</strong>
        </span>
      </div>
    </div>
  );
}
