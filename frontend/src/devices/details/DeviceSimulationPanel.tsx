import { SimulatorStatusIcon } from "../../simulation/SimulatorStatusIcon";
import { VehicleRouteEditor } from "../../vehicles/VehicleRouteEditor";
import { formatEnum, formatShortDateTime } from "./deviceDetailsFormatting";
import { SimulationSettingsForm } from "./SimulationSettingsForm";
import { DeviceMetricCard as Metric } from "./DeviceMetricCard";
import { useDeviceSimulationWorkspace } from "./useDeviceSimulationWorkspace";
export function DeviceSimulationPanel({
  simulation,
}: {
  simulation: ReturnType<typeof useDeviceSimulationWorkspace>;
}) {
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
    validateYaml,
    saveYaml,
    updateSimulationField,
    changeSimulationProfile,
    changeSimulationProtocol,
    toggleSimulation,
    resetYamlTemplate,
  } = simulation;
  return (
    <section className="panel simulation-panel">
      {simulationProfile === "VEHICLE_GPS" ? (
        <VehicleRouteEditor yaml={simulationYaml} onChange={setSimulationYaml} />
      ) : null}
      {simulation.confirmationDialog}
      <p className="status-note">
        Configuration:{" "}
        {simulation.hasUnsavedSimulation
          ? "Unsaved draft"
          : simulationConfig
            ? "Saved"
            : "Unavailable"}
        . Runtime: {simulationStatus ? formatEnum(simulationStatus.status) : "Unknown"}. Loading a
        preset does not start a run.
      </p>
      {simulationConfig ? (
        <div className="form-stack">
          <label>
            Scenario preset
            <select
              value={simulation.simulationPreset}
              onChange={(event) => simulation.setSimulationPreset(event.target.value)}
            >
              {simulation.simulationPresets.map((preset) => (
                <option key={preset.id} value={preset.id}>
                  {preset.name}
                </option>
              ))}
            </select>
          </label>
          <p>
            {simulation.simulationPresets.find(
              (preset) => preset.id === simulation.simulationPreset,
            )?.description ?? "Presets unavailable"}
          </p>
          <button
            type="button"
            className="ghost-button"
            disabled={isSimulationBusy || !simulation.simulationPresets.length}
            onClick={() => void simulation.applyPreset()}
          >
            Load preset as draft
          </button>
        </div>
      ) : null}
      <div className="section-header">
        <div>
          <h2>
            Demo tools <SimulatorStatusIcon resource={simulation.presetResource} />
          </h2>
          <p>
            {simulationConfig
              ? `${formatEnum(simulationConfig.profile)} - ${formatEnum(simulationConfig.status)} - Version ${simulationConfig.yamlVersion}`
              : "No simulation config exists for this device."}
          </p>
        </div>
        <div className="action-row">
          <button
            type="button"
            className="ghost-button"
            onClick={() => void simulation.presetResource.retry()}
            disabled={simulation.presetResource.refreshing}
          >
            {simulation.presetResource.error ? "Retry simulator" : "Check simulator"}
          </button>
          <button
            className="ghost-button"
            type="button"
            onClick={() => void validateYaml()}
            disabled={!simulationConfig || isSimulationBusy}
          >
            Validate YAML
          </button>
          <button
            className="ghost-button"
            type="button"
            onClick={() => void saveYaml()}
            disabled={!simulationConfig || isSimulationBusy || !simulationValidation.valid}
          >
            Save Config
          </button>
          <button
            className="ghost-button"
            type="button"
            onClick={() => void resetYamlTemplate()}
            disabled={!simulationConfig || isSimulationBusy}
          >
            Reset Template
          </button>
          <button
            className="primary-button"
            type="button"
            onClick={() => void toggleSimulation()}
            disabled={!simulationConfig || isSimulationBusy || !simulationValidation.valid}
          >
            {simulationStatus?.status === "RUNNING" ? "Stop" : "Start"}
          </button>
        </div>
      </div>
      {simulationMessage ? <div className="status-note">{simulationMessage}</div> : null}
      {simulationConfig ? (
        <div
          className={
            simulationValidation.valid
              ? "status-note validation-note validation-note-ok"
              : "status-note validation-note validation-note-error"
          }
        >
          {simulationValidation.message}
        </div>
      ) : null}
      {simulationConfig ? (
        <>
          <section className="metric-grid">
            <Metric
              label="Status"
              valueText={simulationStatus ? formatEnum(simulationStatus.status) : "Unknown"}
            />
            <Metric label="Protocol" valueText={simulationConfig.protocol} />
            <Metric
              label="Generated points"
              value={simulationStatus?.generatedPoints ?? 0}
              unit=""
            />
            <Metric
              label="Started"
              valueText={
                simulationStatus?.startedAt
                  ? formatShortDateTime(simulationStatus.startedAt)
                  : "Not running"
              }
            />
          </section>
          <div className="simulation-control-grid">
            <label>
              Profile
              <select
                value={simulationProfile}
                onChange={(event) => changeSimulationProfile(event.target.value)}
              >
                {(simulationProfile === "VEHICLE_GPS"
                  ? ["VEHICLE_GPS"]
                  : ["GENERIC_APPLIANCE", "FRIDGE", "AIR_CONDITIONER", "BOILER", "PUMP", "HEATER"]
                ).map((profile) => (
                  <option value={profile} key={profile}>
                    {formatEnum(profile)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Protocol
              <select
                disabled={simulationProfile === "VEHICLE_GPS"}
                value={simulationProtocol}
                onChange={(event) => changeSimulationProtocol(event.target.value)}
              >
                <option value="HTTP">HTTP</option>
                <option value="MQTT">MQTT</option>
              </select>
            </label>
          </div>
          <SimulationSettingsForm
            settings={simulationSettings}
            profile={simulationProfile}
            onChange={updateSimulationField}
          />
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
            <div className="button-row" aria-label="Run history pages">
              <button
                type="button"
                disabled={
                  simulation.simulationHistory.loading || simulation.simulationHistory.page === 0
                }
                onClick={() =>
                  simulation.simulationHistory.selectPage(simulation.simulationHistory.page - 1)
                }
              >
                Previous
              </button>
              <span>
                Page {simulation.simulationHistory.page + 1} of{" "}
                {Math.max(1, simulation.simulationHistory.totalPages)}
              </span>
              <button
                type="button"
                disabled={
                  simulation.simulationHistory.loading ||
                  simulation.simulationHistory.page + 1 >= simulation.simulationHistory.totalPages
                }
                onClick={() =>
                  simulation.simulationHistory.selectPage(simulation.simulationHistory.page + 1)
                }
              >
                Next
              </button>
            </div>
            {simulationRuns.length > 0 ? (
              <ul className="plain-list compact-list">
                {simulationRuns.map((run) => (
                  <li key={run.runId ?? `${run.startedAt}-${run.status}`}>
                    <strong>{formatEnum(run.status)}</strong>
                    <span>
                      {run.generatedPoints} points -{" "}
                      {run.startedAt ? formatShortDateTime(run.startedAt) : "No start time"}
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
  );
}
