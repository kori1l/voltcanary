import { EntitySelector } from "../../../components/EntitySelector";
import { TimezoneNote } from "../../../components/TimeDisplay";
import { DEFAULT_METRIC_DEFINITIONS } from "../../../features/telemetry/model/telemetry.constants";
import { FormEvent, useEffect, useState } from "react";
import { correctMlLabel, createMlLabel, MlDatasetSummary, MlLabel, MlLabelAudit, MlLabelType } from "../../../analytics/mlModuleApi";
import { safeMlError } from "../../../analytics/mlSettingsApi";
import { Device } from "../../../devices/devicesApi";
import { useHorizonLabelGeneration } from "../../../features/ml-settings/hooks/useHorizonLabelGeneration";
import { useActionConfirmation } from "../../../components/useActionConfirmation";

import { summarizeLabels, labelOptions, toInstant } from "../model/mlConsolePresentation";
export function LabelsTab(props: {
  devices: Device[];
  audit: MlLabelAudit[];
  datasets: MlDatasetSummary[];
  labels: MlLabel[];
  onAudit: (labelId: string) => Promise<void>;
  onChanged: (datasetVersionId: string) => Promise<void>;
  onDelete: (label: MlLabel) => Promise<void>;
  setError: (value: string | null) => void;
}) {
  const readyDatasets = props.datasets.filter((dataset) => dataset.status === "READY");
  const [datasetVersionId, setDatasetVersionId] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [externalDeviceKey, setExternalDeviceKey] = useState("external-device-1");
  const [metricName, setMetricName] = useState("activePowerW");
  const [labelStartAt, setLabelStartAt] = useState("2026-07-01T00:00");
  const [labelEndAt, setLabelEndAt] = useState("2026-07-01T01:00");
  const [label, setLabel] = useState<MlLabelType>("ANOMALY");
  const [reason, setReason] = useState("Operator review");
  const labelConfirmation = useActionConfirmation();
  const [horizonDays, setHorizonDays] = useState(30);
  const [includePreventiveMaintenance, setIncludePreventiveMaintenance] = useState(false);
  const {
    generationJob,
    generationStatusMessage,
    generationResult,
    generationActive,
    generateLabels,
    refreshGenerationStatus,
  } = useHorizonLabelGeneration({
    datasetVersionId,
    horizonDays,
    includePreventiveMaintenance,
    onChanged: props.onChanged,
    setError: props.setError,
  });

  useEffect(() => {
    if (!datasetVersionId && readyDatasets[0]) {
      setDatasetVersionId(readyDatasets[0].datasetVersionId);
    }
  }, [datasetVersionId, readyDatasets]);

  async function loadLabels(nextDatasetVersionId = datasetVersionId) {
    if (!nextDatasetVersionId) {
      props.setError("Select a dataset version before reviewing labels");
      return;
    }
    try {
      await props.onChanged(nextDatasetVersionId);
    } catch (error) {
      props.setError(safeMlError(error));
    }
  }

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!datasetVersionId) {
      props.setError("Select a dataset version before creating labels");
      return;
    }
    try {
      await createMlLabel({
        datasetVersionId,
        deviceId: deviceId.trim() || null,
        externalDeviceKey: externalDeviceKey.trim() || null,
        metricName: metricName.trim() || null,
        labelStartAt: toInstant(labelStartAt),
        labelEndAt: toInstant(labelEndAt),
        label,
        reason,
      });
      await props.onChanged(datasetVersionId);
    } catch (error) {
      props.setError(safeMlError(error));
    }
  }

  async function correct(item: MlLabel, nextLabel: MlLabelType) {
    const correctionReason = await labelConfirmation.confirm({ title: "Correct label", object: `${item.label} → ${nextLabel}`, impact: "Updates the operator label and records an audit entry. Training data may change when snapshots are regenerated.", reasonRequired: true });
    if (correctionReason === null) return;
    try {
      await correctMlLabel(item.id, { label: nextLabel, reason: correctionReason });
      await props.onChanged(item.datasetVersionId);
    } catch (error) {
      props.setError(safeMlError(error));
    }
  }

  const scopedLabels = props.labels.filter((item) => item.datasetVersionId === datasetVersionId);
  const scopedAudit = props.audit.filter((item) => scopedLabels.some((label) => label.id === item.labelId));
  const coverage = summarizeLabels(scopedLabels);

  return (
    <div className="ml-workflow-grid">
      {labelConfirmation.dialog}
      <form className="panel ml-workflow-form" onSubmit={create}>
        <div className="section-header">
          <div>
            <h2>Label review</h2>
            <p>Manual corrections are audit logged for retraining lineage.</p>
          </div>
        </div>
        <label>
          Dataset version
          <select
            value={datasetVersionId}
            onChange={(event) => {
              setDatasetVersionId(event.target.value);
              void loadLabels(event.target.value);
            }}
          >
            <option value="">Select dataset</option>
            {readyDatasets.map((dataset) => <option key={dataset.datasetVersionId} value={dataset.datasetVersionId}>{dataset.name} v{dataset.version}</option>)}
          </select>
        </label>
        <div className="ml-form-grid">
          <EntitySelector label="Label device" value={deviceId} onChange={setDeviceId} options={props.devices.map((device) => ({ id: device.id, name: device.name }))} />
          <label>External device key<input value={externalDeviceKey} onChange={(event) => setExternalDeviceKey(event.target.value)} /></label>
          <label>Metric<input list="label-metrics" value={metricName} onChange={(event) => setMetricName(event.target.value)} /><datalist id="label-metrics">{DEFAULT_METRIC_DEFINITIONS.map((metric) => <option key={metric.key} value={metric.key}>{metric.name} ({metric.unit})</option>)}</datalist></label>
          <label>Label<select value={label} onChange={(event) => setLabel(event.target.value as MlLabelType)}>{labelOptions.map((value) => <option key={value} value={value}>{value.replace(/_/g," ")}</option>)}</select></label>
          <label>Start<input type="datetime-local" value={labelStartAt} onChange={(event) => setLabelStartAt(event.target.value)} /></label>
          <label>End<input type="datetime-local" value={labelEndAt} onChange={(event) => setLabelEndAt(event.target.value)} /></label>
        </div>
        <label>Reason<input value={reason} onChange={(event) => setReason(event.target.value)} /></label>
        <TimezoneNote />
        <div className="action-row action-row-start">
          <button className="primary-button">Create manual label</button>
          <button className="ghost-button" onClick={() => void loadLabels()} type="button">Refresh labels</button>
        </div>
        <section className="ml-inline-panel">
          <h3>Horizon labels</h3>
          <div className="ml-form-grid">
            <label>Horizon days<input min="1" max="365" type="number" value={horizonDays} onChange={(event) => setHorizonDays(Number(event.target.value))} /></label>
            <label className="checkbox-label"><input checked={includePreventiveMaintenance} onChange={(event) => setIncludePreventiveMaintenance(event.target.checked)} type="checkbox" /> Include preventive maintenance</label>
          </div>
          <div className="action-row action-row-start">
            <button className="ghost-button" disabled={generationActive} onClick={() => void generateLabels()} type="button">
              {generationActive ? "Generation active" : generationJob?.status === "FAILED" ? "Retry horizon labels" : "Generate horizon labels"}
            </button>
            {generationJob && <button className="ghost-button" onClick={() => void refreshGenerationStatus()} type="button">Check generation status</button>}
          </div>
          {generationStatusMessage && (
            <div className={generationJob?.status === "COMPLETED" ? "success-message" : "warning-message"}>{generationStatusMessage}</div>
          )}
          {generationJob && (
            <dl className="ml-quality-grid">
              <div><dt>Status</dt><dd>{generationJob.status}</dd></div>
              <div><dt>Job</dt><dd className="mono-value">{generationJob.jobId}</dd></div>
              <div><dt>Submitted</dt><dd>{new Date(generationJob.submittedAt).toLocaleString()}</dd></div>
              <div><dt>Progress</dt><dd>{generationJob.progress === null ? "Unavailable" : `${generationJob.progress}%`}</dd></div>
              <div><dt>Generation scope</dt><dd>{props.datasets.find((dataset) => dataset.datasetVersionId === generationJob.datasetVersionId)?.name ?? "Dataset context unavailable"} · {generationJob.horizonDays} day horizon</dd></div>
            </dl>
          )}
          {generationResult && (
            <dl className="ml-quality-grid">
              <div><dt>Generated</dt><dd>{generationResult.generatedLabels}</dd></div>
              <div><dt>Positive</dt><dd>{generationResult.positiveLabels}</dd></div>
              <div><dt>Normal</dt><dd>{generationResult.normalLabels}</dd></div>
              <div><dt>Unobservable</dt><dd>{generationResult.unobservableRows}</dd></div>
              <div><dt>Lineage</dt><dd className="mono-value">{generationResult.lineageHash}</dd></div>
            </dl>
          )}
        </section>
      </form>
      <section className="panel">
        <h2>Dataset labels</h2>
        {scopedLabels.length > 0 && (
          <dl className="ml-quality-grid">
            <div><dt>Label coverage</dt><dd>{scopedLabels.length} labels</dd></div>
            <div><dt>Positive labels</dt><dd>{coverage.positive}</dd></div>
            <div><dt>Outcome labels</dt><dd>{coverage.outcome}</dd></div>
            <div><dt>Unknown/unresolved</dt><dd>{coverage.unresolved}</dd></div>
            <div><dt>False positives</dt><dd>{coverage.falsePositive}</dd></div>
          </dl>
        )}
        {scopedLabels.length === 0 ? <div className="empty-state">No labels loaded for the selected dataset.</div> : scopedLabels.map((item) => (
          <div className="ml-model-row" key={item.id}>
            <div>
              <strong>{item.label.replace(/_/g," ")} / {item.metricName ?? "metric"}</strong>
              <span>{props.devices.find((device) => device.id === item.deviceId)?.name ?? item.externalDeviceKey ?? "Device context unavailable"} / {item.source} / {item.reason ?? "No reason"}</span>
              <small>{new Date(item.labelStartAt).toLocaleString()} - {new Date(item.labelEndAt).toLocaleString()}</small>
              {item.lineageHash && <small className="mono-value">h{item.horizonDays} {item.generationVersion} {item.lineageHash}</small>}
            </div>
            <div className="action-row">
              <select aria-label={`Correct label ${item.id}`} value={item.label} onChange={(event) => void correct(item, event.target.value as MlLabelType)}>
                {labelOptions.map((value) => <option key={value} value={value}>{value}</option>)}
              </select>
              <button className="ghost-button" onClick={() => void props.onAudit(item.id)} type="button">Audit</button>
              <button className="ghost-button" onClick={() => void props.onDelete(item)} type="button">Delete</button>
            </div>
          </div>
        ))}
        {scopedAudit.length > 0 && (
          <>
            <h3>Audit trail</h3>
            {scopedAudit.map((item) => (
              <div className="ml-readout" key={item.id}>
                <strong>{item.previousLabel ?? "NEW"} {"->"} {item.newLabel}</strong>
                <span>{item.reason ?? "No reason"} / {new Date(item.editedAt).toLocaleString()}</span>
              </div>
            ))}
          </>
        )}
      </section>
    </div>
  );
}

