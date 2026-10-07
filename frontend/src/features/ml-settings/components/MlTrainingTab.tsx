import { FormEvent, useEffect, useRef, useState } from "react";
import { cancelTrainingRun, createTrainingRun, MlDatasetSummary, MlFeatureSnapshotResponse, MlLifecycleStatusFilter, MlTrainingRun, retryTrainingRun } from "../../../analytics/mlModuleApi";
import { safeMlError } from "../../../analytics/mlSettingsApi";
import { trainingBlockReason, useTrainingDraft } from "../../../features/ml-settings/hooks/useTrainingDraft";

import { ArchiveDeleteAction, TrainingTemplate, trainingTemplates, humanizeAlgorithm, snapshotLabel } from "../model/mlConsolePresentation";
import { StatusFilterSelect } from "./MlLifecycleFilter";
export function TrainingTab(props: {
  accountId: string;
  datasets: MlDatasetSummary[];
  featureSnapshots: MlFeatureSnapshotResponse[];
  runs: MlTrainingRun[];
  runsAvailable?: boolean;
  statusFilter: MlLifecycleStatusFilter;
  onChanged: () => Promise<void>;
  onFilterChange: (value: MlLifecycleStatusFilter) => Promise<void>;
  onLifecycle: (run: MlTrainingRun, action: ArchiveDeleteAction) => Promise<void>;
  setError: (value: string | null) => void;
}) {
  const pending = useRef(false);
  const [queueing, setQueueing] = useState(false);
  const readyDatasets = props.datasets.filter((dataset) => dataset.status === "READY");
  const [draft, setDraft, clearDraft] = useTrainingDraft(props.accountId);
  const { datasetVersionId, featureSnapshotId, modelFamily, algorithm, failureHorizonDays, minimumPositiveLabels, minimumLabelCoverage } = draft;
  const selectedTemplate = trainingTemplates[modelFamily];
  const selectedDataset = readyDatasets.find((dataset) => dataset.datasetVersionId === datasetVersionId);
  const availableSnapshots = props.featureSnapshots.filter((snapshot) => snapshot.datasetVersionId === datasetVersionId);
  const compatibleSnapshots = availableSnapshots.filter((snapshot) => (snapshot.modelFamily ?? "ANOMALY_DETECTION") === modelFamily);
  const selectedSnapshot = availableSnapshots.find((snapshot) => snapshot.id === featureSnapshotId);
  const trainingDisabledReason = trainingBlockReason(
    datasetVersionId,
    featureSnapshotId,
    selectedSnapshot,
    modelFamily,
    failureHorizonDays,
  );

  useEffect(() => {
    if (!datasetVersionId && readyDatasets[0]) {
      setDraft((current) => ({ ...current, datasetVersionId: readyDatasets[0].datasetVersionId }));
    }
  }, [datasetVersionId, readyDatasets, setDraft]);

  useEffect(() => {
    if (datasetVersionId && featureSnapshotId && !availableSnapshots.some((snapshot) => snapshot.id === featureSnapshotId)) {
      setDraft((current) => ({ ...current, featureSnapshotId: "" }));
    }
  }, [availableSnapshots, datasetVersionId, featureSnapshotId, setDraft]);

  useEffect(() => {
    if (datasetVersionId && !featureSnapshotId && compatibleSnapshots[0]) {
      setDraft((current) => ({
        ...current,
        featureSnapshotId: compatibleSnapshots[0].id,
        failureHorizonDays: modelFamily === "FAILURE_PREDICTION"
          ? compatibleSnapshots[0].horizonDays ?? current.failureHorizonDays
          : current.failureHorizonDays,
      }));
    }
  }, [compatibleSnapshots, datasetVersionId, featureSnapshotId, modelFamily, setDraft]);

  function chooseTemplate(nextModelFamily: "ANOMALY_DETECTION" | "FAILURE_PREDICTION") {
    const nextSnapshot = availableSnapshots.find((snapshot) => (snapshot.modelFamily ?? "ANOMALY_DETECTION") === nextModelFamily);
    setDraft((current) => ({
      ...current,
      modelFamily: nextModelFamily,
      featureSnapshotId: nextSnapshot?.id ?? "",
      algorithm: trainingTemplates[nextModelFamily].defaultAlgorithm,
      failureHorizonDays: nextModelFamily === "FAILURE_PREDICTION"
        ? nextSnapshot?.horizonDays ?? current.failureHorizonDays
        : current.failureHorizonDays,
    }));
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (pending.current) return;
    if (trainingDisabledReason) {
      props.setError(trainingDisabledReason);
      return;
    }
    if (selectedSnapshot?.modelFamily && selectedSnapshot.modelFamily !== modelFamily) {
      props.setError(`Feature snapshot family ${selectedSnapshot.modelFamily} must match training family ${modelFamily}.`);
      return;
    }
    if (modelFamily === "FAILURE_PREDICTION" && (!failureHorizonDays || failureHorizonDays < 1)) {
      props.setError("Failure prediction requires a positive horizon.");
      return;
    }
    if (modelFamily === "FAILURE_PREDICTION" && selectedSnapshot?.horizonDays && selectedSnapshot.horizonDays !== failureHorizonDays) {
      props.setError(`Feature snapshot horizon ${selectedSnapshot.horizonDays} must match training horizon ${failureHorizonDays}.`);
      return;
    }
    pending.current = true;
    setQueueing(true);
    try {
      await createTrainingRun({
        datasetVersionId,
        featureSnapshotId: featureSnapshotId || null,
        modelFamily,
        algorithm,
        failureHorizonDays: modelFamily === "FAILURE_PREDICTION" ? failureHorizonDays : null,
        minimumPositiveLabels: modelFamily === "FAILURE_PREDICTION" ? minimumPositiveLabels : null,
        minimumLabelCoverage: modelFamily === "FAILURE_PREDICTION" ? minimumLabelCoverage : null,
        hyperparametersJson: "{}",
      });
      await props.onChanged();
      clearDraft();
    } catch (error) {
      props.setError(safeMlError(error));
    } finally {
      pending.current = false;
      setQueueing(false);
    }
  }

  return (
    <div className="ml-workflow-grid">
      <form className="panel ml-workflow-form" onSubmit={submit}>
        <div className="section-header">
          <div>
            <h2>Create training run</h2>
            <p>Choose a template, confirm the data prerequisites, then queue a worker run.</p>
          </div>
          <span className="status-badge">{selectedTemplate.badge}</span>
        </div>
        <div className="ml-template-grid" role="group" aria-label="Training template">
          {(Object.values(trainingTemplates) as TrainingTemplate[]).map((template) => (
            <button
              className={`ml-template-option ${modelFamily === template.modelFamily ? "ml-template-option-active" : ""}`}
              key={template.modelFamily}
              onClick={() => chooseTemplate(template.modelFamily)}
              type="button"
            >
              <strong>{template.title}</strong>
              <span>{template.description}</span>
            </button>
          ))}
        </div>
        <section className="ml-inline-panel">
          <h3>{selectedTemplate.title} checklist</h3>
          <ul className="ml-checklist">
            {selectedTemplate.checklist.map((item) => <li key={item}>{item}</li>)}
          </ul>
        </section>
        <div className="ml-control-grid">
          <label>Dataset version<select value={datasetVersionId} onChange={(e) => setDraft((current) => ({ ...current, datasetVersionId: e.target.value }))}>{readyDatasets.map((dataset) => <option key={dataset.datasetVersionId} value={dataset.datasetVersionId}>{dataset.name} v{dataset.version}</option>)}</select></label>
          <label>
            Feature snapshot
            <select value={featureSnapshotId} onChange={(e) => setDraft((current) => ({ ...current, featureSnapshotId: e.target.value }))}>
              <option value="">No snapshot selected</option>
              {availableSnapshots.map((snapshot) => <option key={snapshot.id} value={snapshot.id}>{snapshotLabel(snapshot)}</option>)}
            </select>
          </label>
          <label>Algorithm<select value={algorithm} onChange={(e) => setDraft((current) => ({ ...current, algorithm: e.target.value }))}>
            {selectedTemplate.algorithms.map((value) => <option key={value} value={value}>{humanizeAlgorithm(value)}</option>)}
          </select></label>
        </div>
        {selectedDataset && (
          <div className="ml-readout">
            <strong>{selectedDataset.name} v{selectedDataset.version}</strong>
            <span>{selectedDataset.sourceType} / {selectedDataset.splitStrategy} / {selectedDataset.immutableRowReference ? "immutable rows ready" : "artifact-backed"}</span>
          </div>
        )}
        {compatibleSnapshots.length > 0 && (
          <div className="ml-readout">
            <strong>{compatibleSnapshots.length} compatible feature snapshots</strong>
            <span>{compatibleSnapshots.map(snapshotLabel).join(" | ")}</span>
          </div>
        )}
        {modelFamily === "FAILURE_PREDICTION" && (
          <section className="ml-inline-panel">
            <h3>Failure prediction gates</h3>
            <div className="ml-form-grid">
              <label>Prediction horizon<input min="1" max="365" type="number" value={failureHorizonDays} onChange={(e) => setDraft((current) => ({ ...current, failureHorizonDays: Number(e.target.value) }))} /></label>
              <label>Minimum positive labels<input min="1" type="number" value={minimumPositiveLabels} onChange={(e) => setDraft((current) => ({ ...current, minimumPositiveLabels: Number(e.target.value) }))} /></label>
              <label>Minimum label coverage<input max="1" min="0" step="0.01" type="number" value={minimumLabelCoverage} onChange={(e) => setDraft((current) => ({ ...current, minimumLabelCoverage: Number(e.target.value) }))} /></label>
            </div>
            {selectedSnapshot?.horizonDays && selectedSnapshot.horizonDays !== failureHorizonDays && (
              <div className="warning-message">Feature snapshot horizon {selectedSnapshot.horizonDays} differs from training horizon {failureHorizonDays}.</div>
            )}
            {selectedSnapshot?.modelFamily && selectedSnapshot.modelFamily !== modelFamily && (
              <div className="warning-message">Feature snapshot family {selectedSnapshot.modelFamily} differs from training family {modelFamily}.</div>
            )}
            <div className="ml-readout">
              <strong>Before queueing</strong>
              <span>Generate horizon labels for {failureHorizonDays} days, then build a failure-prediction feature snapshot from the same dataset version.</span>
            </div>
          </section>
        )}
        {trainingDisabledReason && <div className="warning-message">{trainingDisabledReason}</div>}
        <button className="primary-button" disabled={queueing || Boolean(trainingDisabledReason)} title={trainingDisabledReason ?? undefined}>{queueing ? "Queueing..." : "Queue training"}</button>
      </form>
      <section className="panel">
        <div className="section-header">
          <div>
            <h2>Training runs</h2>
          </div>
          <StatusFilterSelect label="Training status filter" value={props.statusFilter} onChange={props.onFilterChange} />
        </div>
        {props.runsAvailable === false ? <p>Training records unavailable or loading.</p> : props.runs.length === 0 ? <div className="empty-state">No training runs yet.</div> : props.runs.map((run) => (
          <div className="ml-readout" key={run.id}>
            <strong>{run.modelFamily} / {run.algorithm}</strong>
            <span>{run.status} / {run.logSummary ?? run.failureReason ?? "Waiting for worker"}</span>
            <div className="action-row action-row-start">
              {["QUEUED", "RUNNING"].includes(run.status) && <button className="ghost-button" onClick={() => void cancelTrainingRun(run.id).then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Cancel</button>}
              {["FAILED", "CANCELED"].includes(run.status) && <button className="ghost-button" onClick={() => void retryTrainingRun(run.id).then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Retry</button>}
              {["SUCCEEDED", "FAILED", "CANCELED"].includes(run.status) && <button className="ghost-button" onClick={() => void props.onLifecycle(run, "archive")} type="button">Archive</button>}
              {["FAILED", "CANCELED"].includes(run.status) && <button className="ghost-button" onClick={() => void props.onLifecycle(run, "delete")} type="button">Delete</button>}
            </div>
          </div>
        ))}
      </section>
    </div>
  );
}

