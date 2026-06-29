import { Link } from "react-router-dom";

export interface DeviceIncident {
  riskTone: string;
  riskLabel: string;
  title: string;
  eventCount: number;
  timeRangeLabel: string;
  peakValue: string;
  currentValue: string;
  conclusion: string;
}

interface DeviceIncidentSummaryProps {
  incident: DeviceIncident;
  isChecked: boolean;
  onChecked: () => void;
}

export function DeviceIncidentSummary({ incident, isChecked, onChecked }: DeviceIncidentSummaryProps) {
  return (
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
        <button className="primary-button" type="button" onClick={onChecked}>
          {isChecked ? "Checked" : "Mark as checked"}
        </button>
      </div>
    </section>
  );
}
