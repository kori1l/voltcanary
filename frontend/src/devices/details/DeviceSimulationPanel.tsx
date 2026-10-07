import { formatEnum, formatShortDateTime } from "./deviceDetailsFormatting";


import { DurationInput } from "./DurationInput";
import { DeviceMetricCard as Metric } from "./DeviceMetricCard";
import { useDeviceSimulationWorkspace } from "./useDeviceSimulationWorkspace";
export function DeviceSimulationPanel({ simulation }: { simulation: ReturnType<typeof useDeviceSimulationWorkspace> }) {
  const {
    simulationConfig,
    simulationStatus,
    simulationRuns,
    simulationYaml,
    setSimulationYaml,
    simulationProfile,
    simulationProtocol,
    simulationMessage,
    simulationValidation,
    isSimulationBusy,
    simulationSettings,
    simulationSourceId,
    loadSimulation,
    validateYaml,
    saveYaml,
    updateSimulationField,
    changeSimulationProfile,
    changeSimulationProtocol,
    toggleSimulation,
    resetYamlTemplate,
  } = simulation;
  return (<section className="panel simulation-panel">
              {simulation.confirmationDialog}
              <p className="status-note">Configuration: {simulation.hasUnsavedSimulation ? "Unsaved draft" : simulationConfig ? "Saved" : "Unavailable"}. Runtime: {simulationStatus ? formatEnum(simulationStatus.status) : "Unknown"}. Loading a preset does not start a run.</p>
              {simulationConfig ? <div className="form-stack"><label>Scenario preset<select value={simulation.simulationPreset} onChange={(event) => simulation.setSimulationPreset(event.target.value)}>{simulation.simulationPresets.map((preset) => <option key={preset.id} value={preset.id}>{preset.name}</option>)}</select></label><p>{simulation.simulationPresets.find((preset) => preset.id === simulation.simulationPreset)?.description ?? "Presets unavailable"}</p><button type="button" className="ghost-button" disabled={isSimulationBusy || !simulation.simulationPresets.length} onClick={() => void simulation.applyPreset()}>Load preset as draft</button></div> : null}
              <div className="section-header">
                <div>
                  <h2>Demo tools</h2>
                  <p>
                    {simulationConfig
                      ? `${formatEnum(simulationConfig.profile)} - ${formatEnum(simulationConfig.status)} - Version ${simulationConfig.yamlVersion}`
                      : "No simulation config exists for this device."}
                  </p>
                </div>
                <div className="action-row">
                  <button className="ghost-button" type="button" onClick={() => void validateYaml()} disabled={!simulationConfig || isSimulationBusy}>
                    Validate YAML
                  </button>
                  <button className="ghost-button" type="button" onClick={() => void saveYaml()} disabled={!simulationConfig || isSimulationBusy || !simulationValidation.valid}>
                    Save Config
                  </button>
                  <button className="ghost-button" type="button" onClick={() => void resetYamlTemplate()} disabled={!simulationConfig || isSimulationBusy}>
                    Reset Template
                  </button>
                  <button className="primary-button" type="button" onClick={() => void toggleSimulation()} disabled={!simulationConfig || isSimulationBusy || !simulationValidation.valid}>
                    {simulationStatus?.status === "RUNNING" ? "Stop" : "Start"}
                  </button>
                </div>
              </div>

              {simulationMessage ? <div className="status-note">{simulationMessage}</div> : null}
              {simulationConfig ? (
                <div className={simulationValidation.valid ? "status-note validation-note validation-note-ok" : "status-note validation-note validation-note-error"}>
                  {simulationValidation.message}
                </div>
                ) : null}

              {simulationConfig ? (
                <>
                  <section className="metric-grid">
                    <Metric label="Status" valueText={simulationStatus ? formatEnum(simulationStatus.status) : "Unknown"} />
                    <Metric label="Protocol" valueText={simulationConfig.protocol} />
                    <Metric label="Generated points" value={simulationStatus?.generatedPoints ?? 0} unit="" />
                    <Metric label="Started" valueText={simulationStatus?.startedAt ? formatShortDateTime(simulationStatus.startedAt) : "Not running"} />
                  </section>
                  <div className="simulation-control-grid">
                    <label>
                      Profile
                      <select value={simulationProfile} onChange={(event) => changeSimulationProfile(event.target.value)}>
                        {["GENERIC_APPLIANCE", "FRIDGE", "AIR_CONDITIONER", "BOILER", "PUMP", "HEATER"].map((profile) => (
                          <option value={profile} key={profile}>
                            {formatEnum(profile)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Protocol
                      <select value={simulationProtocol} onChange={(event) => changeSimulationProtocol(event.target.value)}>
                        <option value="HTTP">HTTP</option>
                        <option value="MQTT">MQTT</option>
                      </select>
                    </label>
                  </div>

                  <section className="sim-config-form" aria-label="Simulation settings">
                    <div className="sim-form-section">
                      <h3>Device signal</h3>
                      <div className="sim-form-grid">
                        <label>
                          Simulator name
                          <input value={simulationSettings.name} onChange={(event) => updateSimulationField("name", event.target.value)} />
                        </label>
                        <label>
                          Source ID
                          <input value={simulationSettings.sourceId} onChange={(event) => updateSimulationField("sourceId", event.target.value)} />
                        </label>
                        <label>
                          Interval
                          <DurationInput value={simulationSettings.interval} onChange={(value) => updateSimulationField("interval", value)} />
                        </label>
                        <label>
                          Duration
                          <DurationInput value={simulationSettings.duration} onChange={(value) => updateSimulationField("duration", value)} />
                        </label>
                        <label>
                          Speed
                          <input type="number" step="0.1" value={simulationSettings.speed} onChange={(event) => updateSimulationField("speed", event.target.value)} />
                        </label>
                      </div>
                    </div>

                    <div className="sim-form-section">
                      <h3>Normal behavior</h3>
                      <div className="sim-form-grid">
                        <label>
                          Voltage
                          <input type="number" step="0.1" value={simulationSettings.voltageV} onChange={(event) => updateSimulationField("voltageV", event.target.value)} />
                        </label>
                        <label>
                          Signal RSSI
                          <input type="number" step="1" value={simulationSettings.rssi} onChange={(event) => updateSimulationField("rssi", event.target.value)} />
                        </label>
                        <label>
                          Idle power
                          <input type="number" step="0.1" value={simulationSettings.idlePowerW} onChange={(event) => updateSimulationField("idlePowerW", event.target.value)} />
                        </label>
                        <label>
                          Active power
                          <input type="number" step="0.1" value={simulationSettings.activePowerW} onChange={(event) => updateSimulationField("activePowerW", event.target.value)} />
                        </label>
                        <label>
                          Active noise
                          <input type="number" step="0.1" value={simulationSettings.activeNoiseW} onChange={(event) => updateSimulationField("activeNoiseW", event.target.value)} />
                        </label>
                      </div>
                    </div>

                    <div className="sim-form-section">
                      <h3>Cycle timing</h3>
                      <div className="sim-form-grid">
                        <label>
                          On min
                          <DurationInput value={simulationSettings.onMin} onChange={(value) => updateSimulationField("onMin", value)} />
                        </label>
                        <label>
                          On max
                          <DurationInput value={simulationSettings.onMax} onChange={(value) => updateSimulationField("onMax", value)} />
                        </label>
                        <label>
                          Off min
                          <DurationInput value={simulationSettings.offMin} onChange={(value) => updateSimulationField("offMin", value)} />
                        </label>
                        <label>
                          Off max
                          <DurationInput value={simulationSettings.offMax} onChange={(value) => updateSimulationField("offMax", value)} />
                        </label>
                      </div>
                    </div>

                    <div className="sim-form-section sim-anomaly-section">
                      <h3>Anomalies</h3>
                      <div className="sim-anomaly-grid">
                        <article>
                          <label className="sim-toggle">
                            <input type="checkbox" checked={simulationSettings.powerSpikeEnabled} onChange={(event) => updateSimulationField("powerSpikeEnabled", event.target.checked)} />
                            Power spike
                          </label>
                          <label>
                            Start after
                            <DurationInput value={simulationSettings.powerSpikeAfter} onChange={(value) => updateSimulationField("powerSpikeAfter", value)} />
                          </label>
                          <label>
                            Duration
                            <DurationInput value={simulationSettings.powerSpikeDuration} onChange={(value) => updateSimulationField("powerSpikeDuration", value)} />
                          </label>
                          <label>
                            Spike power
                            <input type="number" value={simulationSettings.powerSpikeWatts} onChange={(event) => updateSimulationField("powerSpikeWatts", event.target.value)} />
                          </label>
                        </article>
                        <article>
                          <label className="sim-toggle">
                            <input type="checkbox" checked={simulationSettings.offlineEnabled} onChange={(event) => updateSimulationField("offlineEnabled", event.target.checked)} />
                            Offline window
                          </label>
                          <label>
                            Start after
                            <DurationInput value={simulationSettings.offlineAfter} onChange={(value) => updateSimulationField("offlineAfter", value)} />
                          </label>
                          <label>
                            Duration
                            <DurationInput value={simulationSettings.offlineDuration} onChange={(value) => updateSimulationField("offlineDuration", value)} />
                          </label>
                        </article>
                        <article>
                          <label className="sim-toggle">
                            <input type="checkbox" checked={simulationSettings.growthEnabled} onChange={(event) => updateSimulationField("growthEnabled", event.target.checked)} />
                            Consumption growth
                          </label>
                          <label>
                            Start after
                            <DurationInput value={simulationSettings.growthAfter} onChange={(value) => updateSimulationField("growthAfter", value)} />
                          </label>
                          <label>
                            Growth percent
                            <input type="number" value={simulationSettings.growthPercent} onChange={(event) => updateSimulationField("growthPercent", event.target.value)} />
                          </label>
                          <label>
                            Ramp duration
                            <DurationInput value={simulationSettings.growthRamp} onChange={(value) => updateSimulationField("growthRamp", value)} />
                          </label>
                        </article>
                      </div>
                    </div>
                  </section>

                  <details className="advanced-yaml">
                    <summary>Advanced YAML</summary>
                    <textarea
                      className="yaml-editor"
                      value={simulationYaml}
                      onChange={(event) => setSimulationYaml(event.target.value)}
                      spellCheck={false}
                    />
                  </details>
                  <div className="run-history">
                    <h3>Run history</h3>
                    {simulationRuns.length > 0 ? (
                      <ul className="plain-list compact-list">
                        {simulationRuns.map((run) => (
                          <li key={run.runId ?? `${run.startedAt}-${run.status}`}>
                            <strong>{formatEnum(run.status)}</strong>
                            <span>
                              {run.generatedPoints} points - {run.startedAt ? formatShortDateTime(run.startedAt) : "No start time"}
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
            </section>);
}
