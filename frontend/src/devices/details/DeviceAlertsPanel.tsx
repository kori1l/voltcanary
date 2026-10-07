import { Link } from "react-router-dom";
import { useDeviceAlertsWorkspace } from "./useDeviceAlertsWorkspace";
import { formatEnum, formatShortDateTime } from "./deviceDetailsFormatting";
export function DeviceAlertsPanel({ alerts }: { alerts: ReturnType<typeof useDeviceAlertsWorkspace> }) {
  const { deviceAlerts, deviceAlertStatusFilter, setDeviceAlertStatusFilter, setShowAllDeviceAlerts, filteredDeviceAlerts, visibleDeviceAlerts, loadDeviceAlerts, updateDeviceAlert } = alerts;
  return (<section className="panel device-alerts-panel">
              <div className="section-header">
                <div>
                  <h2>Device alerts</h2>
                  <p>
                    {filteredDeviceAlerts.length} shown from {deviceAlerts.length} alerts for this device.
                  </p>
                </div>
                <div className="action-row">
                  <select aria-label="Device alert status" value={deviceAlertStatusFilter} onChange={(event) => setDeviceAlertStatusFilter(event.target.value)}>
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
                  <strong>{alerts.resource.data === null ? "Unavailable" : deviceAlerts.filter((alert) => alert.status === "OPEN").length}</strong>
                  <span>Open</span>
                </article>
                <article>
                  <strong>{alerts.resource.data === null ? "Unavailable" : deviceAlerts.filter((alert) => alert.status === "ACKNOWLEDGED").length}</strong>
                  <span>Acknowledged</span>
                </article>
                <article>
                  <strong>{alerts.resource.data === null ? "Unavailable" : deviceAlerts.filter((alert) => alert.status === "RESOLVED").length}</strong>
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
              ) : alerts.resource.data !== null ? (
                <div className="empty-state">
                  <strong>No device alerts match this filter</strong>
                  <span>Threshold events can still appear in telemetry, but no alert records are open for this device.</span>
                </div>
              ) : null}
            </section>);
}
