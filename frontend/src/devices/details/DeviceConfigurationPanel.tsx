import { formatEnum } from "./deviceDetailsFormatting";
import { CsvImportPreview, inspectImportCsv } from "../CsvImportPreview";
import { AnalyticsConfigurationFields } from "../AnalyticsConfigurationFields";

import { DeviceMetricCard as Metric } from "./DeviceMetricCard";
import { useDeviceRegistryActions } from "./useDeviceRegistryActions";
import { DEFAULT_METRIC_DEFINITIONS } from "../../features/telemetry/model/telemetry.constants";
export function DeviceConfigurationPanel({ registry, sourceToken }: { registry: ReturnType<typeof useDeviceRegistryActions>; sourceToken: string | null }) {
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
  const lastSourceToken = registry.lastSourceToken ?? sourceToken;
  return (<section className="registry-grid">
              {registry.confirmationDialog}
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
                  {registry.registryLoaded && dataSources.length === 0 ? <div className="empty-state">No data sources configured.</div> : null}
                </div>
                <details className="advanced-yaml">
                  <summary>Import data sources CSV</summary>
                  <div className="form-stack">
                    <p className="muted-text">Header: externalId,sourceType,protocol</p>
                    <CsvImportPreview text={sourceCsvText} onChange={setSourceCsvText} columns={["externalId", "sourceType", "protocol"]} example="meter-001,SMART_PLUG,REST" busy={isRegistryBusy} />
                    {sourceCsvMessage ? <div className="status-note" role="status">{sourceCsvMessage}</div> : null}
                    <button className="primary-button" type="button" onClick={() => void importSourcesCsv()} disabled={isRegistryBusy || !inspectImportCsv(sourceCsvText, ["externalId", "sourceType", "protocol"]).valid}>
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
                        <input list="sensor-metric-options" value={sensorMetricMapping} onChange={(event) => { setSensorMetricMapping(event.target.value); const metric = DEFAULT_METRIC_DEFINITIONS.find((item) => item.key === event.target.value); if (metric) setSensorUnit(metric.unit); }} placeholder="activePowerW" />
                        <datalist id="sensor-metric-options">{DEFAULT_METRIC_DEFINITIONS.map((metric) => <option key={metric.key} value={metric.key}>{metric.name} · {metric.unit}</option>)}</datalist><small>Choosing a known metric fills its unit. Custom mappings remain supported.</small>
                    </label>
                  </div>
                  <details><summary>Advanced sensor metadata</summary><label>
                    Metadata JSON
                    <textarea className="small-textarea" value={sensorMetadataText} onChange={(event) => setSensorMetadataText(event.target.value)} />
                  </label></details>
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
                  {registry.registryLoaded && sensors.length === 0 ? <div className="empty-state">No sensors configured.</div> : null}
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
                      checked={analyticsConfig?.enabled ?? false}
                      disabled={!analyticsConfig}
                      onChange={(event) => setAnalyticsConfig((current) => ({ enabled: event.target.checked, config: current?.config ?? {} }))}
                    />
                    Enabled
                  </label>
                </div>
                <AnalyticsConfigurationFields text={analyticsConfigText} onChange={setAnalyticsConfigText} />
                <div className="action-row action-row-start">
                  <button className="primary-button" type="button" onClick={() => void saveAnalyticsConfig()} disabled={isRegistryBusy || !analyticsConfig}>
                    Save analytics config
                  </button>
                </div>
              </section>
            </section>);
}
