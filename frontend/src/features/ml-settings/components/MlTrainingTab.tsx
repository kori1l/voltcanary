import { FormEvent, useEffect, useRef, useState } from "react";
import { cancelTrainingRun, createTrainingRun, MlDatasetSummary, MlFeatureSnapshotResponse, MlLifecycleStatusFilter, MlTrainingRun, retryTrainingRun } from "../../../analytics/mlModuleApi";
import { safeMlError } from "../../../analytics/mlSettingsApi";
import { trainingBlockReason, useTrainingDraft } from "../../../features/ml-settings/hooks/useTrainingDraft";

import { ArchiveDeleteAction, TrainingTemplate, trainingTemplates, humanizeAlgorithm, snapshotLabel } from "../model/mlConsolePresentation";
import { StatusFilterSelect } from "./MlLifecycleFilter";
import type { PipelineContext } from "../model/pipelineContext";
import { markWorkflowDraftSaved, useWorkflowDraft } from "../../../components/workflowDraftContext";
import { useEntityCommands } from "../../../components/useEntityCommands";
import { CommandFeedback } from "../../../components/CommandFeedback";
export function TrainingTab(props: {
  pipelineContext?: PipelineContext;
  onPipelineChange?: (value: Partial<PipelineContext>) => void;
  accountId: string;
  datasets: MlDatasetSummary[];
  featureSnapshots: MlFeatureSnapshotResponse[];
  runs: MlTrainingRun[];
  runsAvailable?: boolean;
  statusFilter: MlLifecycleStatusFilter;
  onChanged: () => Promise<void>;
  onConfirmed?: () => void;
  onFilterChange: (value: MlLifecycleStatusFilter) => Promise<void>;
  isLifecyclePending?: (id: string, action: string) => boolean;
  onLifecycle: (run: MlTrainingRun, action: ArchiveDeleteAction) => Promise<void>;
  setError: (value: string | null) => void;
}) {
  const submitted = useRef<{ dataset: string; snapshot: string; family: string; algorithm: string; ids: Set<string> } | null>(null);
  const pending = useRef(false);
  const commands = useEntityCommands();
  const [savedRuns, setSavedRuns] = useState<Record<string, MlTrainingRun>>({});
  const [queueing, setQueueing] = useState(false);
  const readyDatasets = props.datasets.filter((dataset) => dataset.status === "READY");
  const [draft, setDraft, clearDraft, recovered] = useTrainingDraft(props.accountId, props.pipelineContext?.datasetVersionId || "new", props.pipelineContext);
  useWorkflowDraft(recovered, clearDraft);
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
    if (!props.pipelineContext && !datasetVersionId && readyDatasets[0]) {
      setDraft((current) => ({ ...current, datasetVersionId: readyDatasets[0].datasetVersionId }));
    }
  }, [datasetVersionId, readyDatasets, setDraft]);
  useEffect(() => {
    if (props.pipelineContext) setDraft((previous) => ({ ...previous, datasetVersionId: props.pipelineContext!.datasetVersionId, featureSnapshotId: props.pipelineContext!.snapshotId, modelFamily: props.pipelineContext!.family, failureHorizonDays: props.pipelineContext!.horizonDays ?? previous.failureHorizonDays }));
  }, [props.pipelineContext?.datasetVersionId, props.pipelineContext?.snapshotId, props.pipelineContext?.family, props.pipelineContext?.horizonDays]);

  useEffect(() => {
    if (datasetVersionId && featureSnapshotId && !availableSnapshots.some((snapshot) => snapshot.id === featureSnapshotId)) {
      setDraft((current) => ({ ...current, featureSnapshotId: "" }));
    }
  }, [availableSnapshots, datasetVersionId, featureSnapshotId, setDraft]);

  useEffect(() => {
    if (!props.pipelineContext && datasetVersionId && !featureSnapshotId && compatibleSnapshots[0]) {
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
    props.onPipelineChange?.({ family: nextModelFamily, snapshotId: nextSnapshot?.id ?? "", horizonDays: nextModelFamily === "FAILURE_PREDICTION" ? nextSnapshot?.horizonDays ?? failureHorizonDays : null, runId: "", modelId: "", policyId: "" });
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
    if (pending.current || commands.isBlocked("training:create")) return;
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
    submitted.current = { dataset: datasetVersionId, snapshot: featureSnapshotId, family: modelFamily, algorithm, ids: new Set(props.runs.map((run) => run.id)) };
    pending.current = true;
    setQueueing(true);
    try {
      await commands.run("training:create", { label: "Queue training", nonIdempotent: true, mutate: () => createTrainingRun({
        datasetVersionId,
        featureSnapshotId: featureSnapshotId || null,
        modelFamily,
        algorithm,
        failureHorizonDays: modelFamily === "FAILURE_PREDICTION" ? failureHorizonDays : null,
        minimumPositiveLabels: modelFamily === "FAILURE_PREDICTION" ? minimumPositiveLabels : null,
        minimumLabelCoverage: modelFamily === "FAILURE_PREDICTION" ? minimumLabelCoverage : null,
        hyperparametersJson: "{}",
      }), onCommitted: (createdRun) => {
      props.onConfirmed?.();
      setSavedRuns((current) => ({ ...current, [createdRun.id]: createdRun }));
      props.onPipelineChange?.({ runId: createdRun.id, modelId: "", policyId: "" });
      clearDraft();
      markWorkflowDraftSaved("ml-training");
      }, refresh: props.onChanged });
    } catch (error) {
      props.setError(safeMlError(error));
    } finally {
      pending.current = false;
      setQueueing(false);
    }
  }

  return (
    <div className="ml-workflow-grid">
      <CommandFeedback outcomes={commands.outcomes} onRetryRead={commands.retryRead} />
      {commands.outcomes["training:create"]?.phase === "unknown-result" ? <div><p>Review existing training runs before queueing another. Matching lineage does not prove which request created a run.</p>{props.runs.filter((run) => submitted.current && !submitted.current.ids.has(run.id) && run.datasetVersionId === submitted.current.dataset && (run.featureSnapshotId ?? "") === submitted.current.snapshot && run.modelFamily === submitted.current.family && run.algorithm === submitted.current.algorithm).map((run) => <button type="button" key={run.id} onClick={() => { commands.reconcile("training:create", run); props.onPipelineChange?.({ runId: run.id }); clearDraft(); }}>Use matching training run {run.id} · {run.status}</button>)}</div> : null}
      <form className="panel ml-workflow-form" data-draft-id="ml-training" onSubmit={submit}>
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
          <label>Dataset version<select value={datasetVersionId} onChange={(e) => { setDraft((current) => ({ ...current, datasetVersionId: e.target.value, featureSnapshotId: "" })); props.onPipelineChange?.({ datasetVersionId: e.target.value, snapshotId: "", runId: "", modelId: "", policyId: "" }); }}><option value="">Select dataset version</option>{readyDatasets.map((dataset) => <option key={dataset.datasetVersionId} value={dataset.datasetVersionId}>{dataset.name} v{dataset.version}</option>)}</select></label>
          <label>
            Feature snapshot
            <select value={featureSnapshotId} onChange={(e) => { setDraft((current) => ({ ...current, featureSnapshotId: e.target.value })); props.onPipelineChange?.({ snapshotId: e.target.value, runId: "", modelId: "", policyId: "" }); }}>
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
              <label>Prediction horizon<input min="1" max="365" type="number" value={failureHorizonDays || ""} onChange={(e) => setDraft((current) => ({ ...current, failureHorizonDays: Number(e.target.value) }))} onBlur={() => { if (failureHorizonDays > 0 && failureHorizonDays <= 365) props.onPipelineChange?.({ horizonDays: failureHorizonDays }); }} /></label>
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
        <button className="primary-button" disabled={queueing || commands.isBlocked("training:create") || Boolean(trainingDisabledReason)} title={trainingDisabledReason ?? undefined}>{queueing ? "Queueing..." : "Queue training"}</button>
      </form>
      <section className="panel">
        <div className="section-header">
          <div>
            <h2>Training runs</h2>
          </div>
          <StatusFilterSelect label="Training status filter" value={props.statusFilter} onChange={props.onFilterChange} />
        </div>
        {props.runsAvailable === false ? <p>Training records unavailable or loading.</p> : props.runs.length === 0 ? <div className="empty-state">No training runs yet.</div> : props.runs.map((record) => { const run = savedRuns[record.id] ?? record; return (
          <div className="ml-readout" key={run.id}>
            <strong>{run.modelFamily} / {run.algorithm}</strong>
            <span>{run.status} / {run.logSummary ?? run.failureReason ?? "Waiting for worker"}</span>
            <div className="action-row action-row-start">
              {["QUEUED", "RUNNING"].includes(run.status) && <button aria-label={`Cancel training run ${run.id}`} className="ghost-button" disabled={commands.isPending(`run:${run.id}:cancel`)} onClick={() => void commands.run(`run:${run.id}:cancel`, { label: "Cancel training", mutate: () => cancelTrainingRun(run.id), onCommitted: (saved) => { props.onConfirmed?.(); setSavedRuns((current) => ({ ...current, [saved.id]: saved })); }, refresh: props.onChanged })} type="button">Cancel</button>}
              {["FAILED", "CANCELED"].includes(run.status) && <button aria-label={`Retry training run ${run.id}`} className="ghost-button" disabled={commands.isPending(`run:${run.id}:retry`)} onClick={() => void commands.run(`run:${run.id}:retry`, { label: "Retry training", mutate: () => retryTrainingRun(run.id), onCommitted: (saved) => { props.onConfirmed?.(); setSavedRuns((current) => ({ ...current, [saved.id]: saved })); }, refresh: props.onChanged })} type="button">Retry</button>}
              {["SUCCEEDED", "FAILED", "CANCELED"].includes(run.status) && <button aria-label={`Archive training run ${run.id}`} className="ghost-button" disabled={props.isLifecyclePending?.(run.id, "archive")} onClick={() => void props.onLifecycle(run, "archive")} type="button">Archive</button>}
              {["FAILED", "CANCELED"].includes(run.status) && <button aria-label={`Delete training run ${run.id}`} className="ghost-button" disabled={props.isLifecyclePending?.(run.id, "delete")} onClick={() => void props.onLifecycle(run, "delete")} type="button">Delete</button>}
            </div>
          </div>
        ); })}
      </section>
    </div>
  );
}

