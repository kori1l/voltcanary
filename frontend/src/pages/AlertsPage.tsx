import { useEffect, useMemo, useState } from "react";
import { acknowledgeAlert, AlertResponse, listAlerts, resolveAlert } from "../alerts/alertsApi";

export function AlertsPage() {
  const [alerts, setAlerts] = useState<AlertResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setIsLoading(true);
    setError(null);
    try {
      setAlerts(await listAlerts());
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load alerts");
    } finally {
      setIsLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function updateStatus(alertId: string, action: "ack" | "resolve") {
    setError(null);
    try {
      if (action === "ack") {
        await acknowledgeAlert(alertId);
      } else {
        await resolveAlert(alertId);
      }
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to update alert");
    }
  }

  const grouped = useMemo(
    () => ({
      new: alerts.filter((alert) => alert.status === "OPEN"),
      acknowledged: alerts.filter((alert) => alert.status === "ACKNOWLEDGED"),
      resolved: alerts.filter((alert) => alert.status === "RESOLVED"),
    }),
    [alerts],
  );

  return (
    <>
      <header className="page-header page-header-row">
        <div>
          <h1>Alerts</h1>
          <p>Open, acknowledged, and resolved alerts.</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
          Refresh
        </button>
      </header>

      {error ? <div className="error-message">{error}</div> : null}
      {isLoading ? <section className="panel">Loading alerts...</section> : null}

      {!isLoading ? (
        <section className="content-grid alerts-grid">
          <AlertColumn title="New" alerts={grouped.new} onUpdate={updateStatus} />
          <AlertColumn title="Acknowledged" alerts={grouped.acknowledged} onUpdate={updateStatus} />
          <AlertColumn title="Resolved" alerts={grouped.resolved} onUpdate={updateStatus} />
        </section>
      ) : null}
    </>
  );
}

function AlertColumn({
  title,
  alerts,
  onUpdate,
}: {
  title: string;
  alerts: AlertResponse[];
  onUpdate: (alertId: string, action: "ack" | "resolve") => Promise<void>;
}) {
  return (
    <div className="panel alert-column">
      <h2>{title}</h2>
      {alerts.length === 0 ? (
        <p className="muted-text">No alerts.</p>
      ) : (
        <div className="alert-list">
          {alerts.map((alert) => (
            <article className="alert-card" key={alert.id}>
              <div className="alert-card-header">
                <strong>{alert.title}</strong>
                <span>{alert.severity}</span>
              </div>
              <p>{alert.description}</p>
              <div className="device-meta">
                <span>{alert.alertType}</span>
                <span>{new Date(alert.createdAt).toLocaleString()}</span>
              </div>
              {alert.status === "OPEN" ? (
                <div className="action-row">
                  <button className="ghost-button" type="button" onClick={() => void onUpdate(alert.id, "ack")}>
                    Ack
                  </button>
                  <button className="primary-button" type="button" onClick={() => void onUpdate(alert.id, "resolve")}>
                    Resolve
                  </button>
                </div>
              ) : null}
              {alert.status === "ACKNOWLEDGED" ? (
                <div className="action-row">
                  <button className="primary-button" type="button" onClick={() => void onUpdate(alert.id, "resolve")}>
                    Resolve
                  </button>
                </div>
              ) : null}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
