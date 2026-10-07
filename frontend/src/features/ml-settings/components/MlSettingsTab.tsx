import { FormEvent } from "react";
import { PredictionResponse } from "../../../analytics/analyticsApi";
import { MlSettingsResponse, MlSettingsValues } from "../../../analytics/mlSettingsApi";
import { Device } from "../../../devices/devicesApi";
import { EntitySelector } from "../../../components/EntitySelector";
import { useActionConfirmation } from "../../../components/useActionConfirmation";

import { deviceLabel } from "../model/mlConsolePresentation";
import { MlStatus } from "./MlPredictionStatus";
export function SettingsTab(props: {
  busy: boolean;
  clearOverride: () => Promise<void>;
  deviceId: string;
  deviceMode: boolean;
  devices: Device[];
  effective: MlSettingsResponse | null;
  loadAccount: () => Promise<void>;
  loadDevice: () => Promise<void>;
  prediction: PredictionResponse | null;
  save: (e: FormEvent) => Promise<void>;
  setDeviceId: (value: string) => void;
  setValue: <K extends keyof MlSettingsValues>(key: K, value: MlSettingsValues[K]) => void;
  statusError: string | null;
  values: MlSettingsValues;
}) {
  const confirmation = useActionConfirmation();
  const scopeReady = Boolean(props.effective && (!props.deviceMode || props.effective.deviceId === props.deviceId.trim()));
  return (
    <>
      {confirmation.dialog}
      <section className="panel ml-scope">
        <div className="section-header">
          <div>
            <h2>Scope</h2>
            <p>Account defaults or a per-device override.</p>
          </div>
          <span className="status-badge">{props.effective?.source ?? "LOADING"}</span>
        </div>
        <div className="action-row action-row-start">
          <button className="ghost-button" onClick={() => void props.loadAccount()} type="button">Account defaults</button>
          <EntitySelector label="Prediction device" options={props.devices.map((device) => ({ id: device.id, name: deviceLabel(device), status: device.status }))} value={props.deviceId} onChange={props.setDeviceId} />
          <details><summary>Advanced device identifier</summary><label>Device ID<input value={props.deviceId} onChange={(e) => props.setDeviceId(e.target.value)} /></label></details>
          <button className="ghost-button" onClick={() => void props.loadDevice()} type="button">Load device</button>
        </div>
      </section>
      <div className="settings-grid">
        <form className="panel ml-settings-form" onSubmit={props.save}>
          <div className="section-header">
            <div>
              <h2>Prediction preferences</h2>
              <p>Saved server-side and applied to analytics recalculation.</p>
            </div>
          </div>
          <label>
            <input
              checked={props.values.mlEnabled}
              onChange={(e) => {
                props.setValue("mlEnabled", e.target.checked);
                if (!e.target.checked) props.setValue("predictionMode", "RULE_BASED_ONLY");
              }}
              type="checkbox"
            />{" "}
            Enable ML-assisted predictions
          </label>
          <label>
            Allowed prediction mode
            <select value={props.values.predictionMode} onChange={(e) => props.setValue("predictionMode", e.target.value as MlSettingsValues["predictionMode"])}>
              <option value="MODEL_ONLY">Trained model only</option>
              <option value="MODEL_OR_BASELINE_HEURISTIC">Model or baseline heuristic</option>
              <option value="RULE_BASED_ONLY">Rule-based fallback only</option>
            </select>
            <small>Controls whether trained model, baseline heuristic, or only deterministic rules may be used.</small>
          </label>
          <label>
            Confidence warning threshold
            <input
              max="1"
              min="0"
              onChange={(e) => props.setValue("confidenceWarningThreshold", Number(e.target.value))}
              step="0.01"
              type="number"
              value={props.values.confidenceWarningThreshold}
            />
            <small>A threshold for warning on inference confidence. It is not measured prediction accuracy.</small>
          </label>
          <label>
            Maintenance exclusions
            <select value={props.values.maintenanceBehavior} onChange={(e) => props.setValue("maintenanceBehavior", e.target.value as MlSettingsValues["maintenanceBehavior"])}>
              <option value="SUPPRESS_ML">Suppress ML calls</option>
              <option value="REDUCE_CONFIDENCE">Reduce confidence</option>
            </select>
            <small>Controls whether excluded maintenance data prevents inference or lowers confidence.</small>
          </label>
          <label>
            <input checked={props.values.explanationsVisible} onChange={(e) => props.setValue("explanationsVisible", e.target.checked)} type="checkbox" /> Show prediction explanations
          </label>
          <div className="action-row action-row-start">
            <button className="primary-button" disabled={props.busy || !scopeReady}>Save settings</button>
            {props.deviceMode && props.effective?.source === "DEVICE_OVERRIDE" && (
              <button className="ghost-button" disabled={props.busy || !scopeReady} onClick={async () => { if (await confirmation.confirm({ title: "Clear device override", object: props.devices.find((device) => device.id === props.deviceId)?.name ?? "Selected device", impact: "Remove this device's prediction preferences. The device will inherit the current account defaults." }) !== null) await props.clearOverride(); }} type="button">Clear override</button>
            )}
          </div>
        </form>
        <MlStatus deviceMode={props.deviceMode} error={props.statusError} prediction={props.prediction} />
      </div>
    </>
  );
}
