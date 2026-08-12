import { type FormEvent, useEffect, useMemo, useRef, useState } from "react";
import {
  createInternalTelemetryDataset,
  previewInternalTelemetryDataset,
} from "../../../analytics/mlModuleApi";
import type { MlInternalDatasetResponse } from "../../../analytics/mlModuleApi";
import type { Device } from "../../../devices/devicesApi";
import { CandidateWindowList } from "./CandidateWindowList";
import { DatasetReadinessPanel } from "./DatasetReadinessPanel";
import { DeviceScopeSelector } from "./DeviceScopeSelector";
import { MetricCoverageMatrix } from "./MetricCoverageMatrix";
import { TelemetryWindowChart } from "./TelemetryWindowChart";
import type {
  DatasetWorkbenchDefaults,
  DatasetWorkbenchDraft,
  DatasetWorkbenchPreviewState,
  DatasetWorkbenchQuickRange,
} from "../model/datasetWorkbench.types";
import {
  applyQuickRange,
  buildPreviewRequest,
  datasetWorkbenchDraftKey,
  dateTimeLocalToIso,
  defaultDatasetWorkbenchDraft,
  humanizeModelFamily,
  isValidRange,
  reconcileDraftWithPreview,
  validateWorkbench,
} from "../model/datasetWorkbench.utils";

export function DatasetWorkbench({
  accountId,
  devices,
  workflowMode,
  onCreate,
  onCreatedFromWorkbench,
  onWorkflowModeChange,
}: {
  accountId: string;
  devices: Device[];
  workflowMode: DatasetWorkbenchDraft["modelFamily"];
  onCreate: (body: Parameters<typeof createInternalTelemetryDataset>[0]) => Promise<MlInternalDatasetResponse>;
  onCreatedFromWorkbench: (defaults: DatasetWorkbenchDefaults) => void;
  onWorkflowModeChange: (mode: DatasetWorkbenchDraft["modelFamily"]) => void;
}) {
  const draftKey = datasetWorkbenchDraftKey(accountId);
  const initialDraft = readDraft<DatasetWorkbenchDraft>(draftKey);
  const [draft, setDraft] = useState<DatasetWorkbenchDraft>(() => mergeDraft(initialDraft));
  const [restoredDraft, setRestoredDraft] = useState(Boolean(initialDraft));
  const [previewState, setPreviewState] = useState<DatasetWorkbenchPreviewState>({ preview: null, status: "idle", error: null });
  const [localError, setLocalError] = useState<string | null>(null);
  const [createSummary, setCreateSummary] = useState<string | null>(null);
  const previewVersion = useRef(0);

  useEffect(() => {
    const stored = readDraft<DatasetWorkbenchDraft>(draftKey);
    setDraft(mergeDraft(stored));
    setRestoredDraft(Boolean(stored));
    setPreviewState({ preview: null, status: "idle", error: null });
    setCreateSummary(null);
  }, [draftKey]);

  useEffect(() => {
    setDraft((current) => current.modelFamily === workflowMode
      ? current
      : { ...current, modelFamily: workflowMode, horizonDays: workflowMode === "FAILURE_PREDICTION" ? current.horizonDays : 30 });
    setPreviewState((current) => current.status === "ready" || current.status === "loading"
      ? { ...current, status: "stale", error: null }
      : current);
  }, [workflowMode]);

  useEffect(() => {
    try {
      if (isDefaultDraft(draft)) {
        window.localStorage.removeItem(draftKey);
        return;
      }
      window.localStorage.setItem(draftKey, JSON.stringify(draft));
    } catch {
      // Draft persistence is best effort; server-side preview/create remains authoritative.
    }
  }, [draftKey, draft]);

  useEffect(() => {
    if (!canAutoPreview(draft)) {
      return;
    }
    const handle = window.setTimeout(() => {
      void runPreview();
    }, 450);
    return () => window.clearTimeout(handle);
  }, [
    draft.deviceIds.join("|"),
    draft.from,
    draft.to,
    draft.modelFamily,
    draft.horizonDays,
    draft.metricSelectionMode,
    draft.selectedMetrics.join("|"),
    draft.splitStrategy,
    draft.featureConfigVersion,
    draft.minSamplesPerMetric,
    draft.maxSamplingGapSeconds,
  ]);

  const validation = useMemo(
    () => validateWorkbench(draft, devices, previewState.preview, previewState.status),
    [devices, draft, previewState.preview, previewState.status],
  );

  function patchDraft(patch: Partial<DatasetWorkbenchDraft>, affectsPreview = true) {
    setDraft((current) => ({ ...current, ...patch }));
    setCreateSummary(null);
    setLocalError(null);
    if (!affectsPreview) {
      return;
    }
    setPreviewState((current) => {
      if (current.status === "ready" || current.status === "loading") {
        previewVersion.current += 1;
        return { ...current, status: "stale", error: null };
      }
      return current;
    });
  }

  function selectQuickRange(quickRange: DatasetWorkbenchQuickRange) {
    if (quickRange === "custom") {
      patchDraft({ quickRange });
      return;
    }
    patchDraft(applyQuickRange(quickRange));
  }

  async function runPreview() {
    if (draft.deviceIds.length === 0) {
      setLocalError("Select at least one device before preview.");
      return;
    }
    if (!isValidRange(draft.from, draft.to)) {
      setLocalError("From must be before To.");
      return;
    }
    if (draft.modelFamily === "FAILURE_PREDICTION" && (!draft.horizonDays || draft.horizonDays < 1)) {
      setLocalError("Failure prediction requires a positive horizon.");
      return;
    }
    const version = previewVersion.current + 1;
    previewVersion.current = version;
    setLocalError(null);
    setCreateSummary(null);
    setPreviewState((current) => ({ preview: current.preview, status: "loading", error: null }));
    try {
      const response = await previewInternalTelemetryDataset(buildPreviewRequest(draft));
      if (previewVersion.current !== version) {
        return;
      }
      setPreviewState({ preview: response, status: "ready", error: null });
      setDraft((current) => reconcileDraftWithPreview(current, response));
    } catch (error) {
      if (previewVersion.current !== version) {
        return;
      }
      setPreviewState({ preview: null, status: "error", error: readableError(error) });
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const currentValidation = validateWorkbench(draft, devices, previewState.preview, previewState.status);
    if (currentValidation.createDisabled) {
      setLocalError(currentValidation.createDisabledReason);
      return;
    }
    if (!previewState.preview) {
      setLocalError("Run preview before creating a dataset.");
      return;
    }
    try {
      const response = await onCreate({
        name: draft.name.trim(),
        description: draft.description.trim() || null,
        deviceIds: draft.deviceIds,
        deviceType: draft.deviceTypeFilter === "all" ? null : draft.deviceTypeFilter,
        deviceMetadata: {},
        metrics: draft.selectedMetrics,
        from: dateTimeLocalToIso(draft.from),
        to: dateTimeLocalToIso(draft.to),
        splitStrategy: draft.splitStrategy,
        splitConfigJson: "{}",
        featureConfigVersion: draft.featureConfigVersion.trim() || "feature-config-v1",
        minSamplesPerMetric: draft.minSamplesPerMetric,
        maxSamplingGapSeconds: draft.maxSamplingGapSeconds,
        previewId: previewState.preview.previewId,
        previewHash: previewState.preview.previewHash,
        modelFamily: draft.modelFamily,
        horizonDays: draft.modelFamily === "FAILURE_PREDICTION" ? draft.horizonDays : null,
        metricSelectionMode: draft.metricSelectionMode,
      });
      onCreatedFromWorkbench({
        modelFamily: draft.modelFamily,
        horizonDays: draft.modelFamily === "FAILURE_PREDICTION" ? draft.horizonDays : null,
        datasetName: draft.name.trim(),
      });
      clearDraft();
      setCreateSummary(`Created dataset ${response.status}: ${response.acceptedRows} accepted / ${response.rejectedRows} rejected / ${response.warningCount} warnings.`);
    } catch (error) {
      setLocalError(readableError(error));
    }
  }

  function clearDraft() {
    try {
      window.localStorage.removeItem(draftKey);
    } catch {
      // Ignore storage failures.
    }
    setDraft(defaultDatasetWorkbenchDraft);
    setRestoredDraft(false);
    setPreviewState({ preview: null, status: "idle", error: null });
  }

  return (
    <form className="panel dataset-workbench" onSubmit={submit}>
      <div className="section-header">
        <div>
          <h2>Dataset Workbench</h2>
          <p>Preview telemetry coverage, candidate windows, and readiness before committing immutable rows.</p>
        </div>
        <span className={`workbench-preview-state workbench-preview-${previewState.status}`} aria-live="polite">
          {previewState.status === "ready" && previewState.preview ? previewState.preview.status : previewState.status}
        </span>
      </div>
      {localError && <div className="error-message">{localError}</div>}
      {previewState.error && <div className="error-message">{previewState.error}</div>}
      {restoredDraft && <div className="warning-message">Restored unsaved dataset draft.</div>}
      {createSummary && <div className="success-message">{createSummary}</div>}

      <section className="workbench-section workbench-header-controls" aria-label="Workbench controls">
        <div className="workbench-control-grid workbench-control-grid-wide">
          <label>Dataset name<input value={draft.name} onChange={(event) => patchDraft({ name: event.target.value }, false)} required /></label>
          <label>Description<input value={draft.description} onChange={(event) => patchDraft({ description: event.target.value }, false)} /></label>
          <label>
            Model family
            <select
              value={draft.modelFamily}
              onChange={(event) => {
                const modelFamily = event.target.value as DatasetWorkbenchDraft["modelFamily"];
                patchDraft({ modelFamily, horizonDays: modelFamily === "FAILURE_PREDICTION" ? draft.horizonDays : 30 });
                onWorkflowModeChange(modelFamily);
              }}
            >
              <option value="ANOMALY_DETECTION">Anomaly detection</option>
              <option value="FAILURE_PREDICTION">Failure prediction</option>
            </select>
          </label>
          {draft.modelFamily === "FAILURE_PREDICTION" && (
            <label>Failure horizon<input min="1" max="365" type="number" value={draft.horizonDays} onChange={(event) => patchDraft({ horizonDays: Number(event.target.value) })} /></label>
          )}
          <label>
            Quick range
            <select value={draft.quickRange} onChange={(event) => selectQuickRange(event.target.value as DatasetWorkbenchQuickRange)}>
              <option value="24h">Last 24 hours</option>
              <option value="7d">Last 7 days</option>
              <option value="30d">Last 30 days</option>
              <option value="custom">Custom</option>
            </select>
          </label>
        </div>
        <div className="workbench-current-intent">
          <strong>{humanizeModelFamily(draft.modelFamily)}</strong>
          <span>{draft.deviceIds.length} devices / {draft.selectedMetrics.length} metrics / {draft.splitStrategy}</span>
        </div>
      </section>

      <div className="workbench-layout">
        <div className="workbench-left">
          <DeviceScopeSelector devices={devices} draft={draft} preview={previewState.preview} onPatch={patchDraft} />
        </div>
        <div className="workbench-center">
          <TelemetryWindowChart draft={draft} preview={previewState.preview} previewStatus={previewState.status} onPatch={patchDraft} />
          <CandidateWindowList draft={draft} preview={previewState.preview} onPatch={patchDraft} />
        </div>
        <div className="workbench-right">
          <DatasetReadinessPanel draft={draft} preview={previewState.preview} previewStatus={previewState.status} validation={validation} />
        </div>
      </div>

      <MetricCoverageMatrix
        draft={draft}
        preview={previewState.preview}
        previewStatus={previewState.status}
        staleMetrics={validation.staleMetrics}
        onPatch={patchDraft}
      />

      <section className="workbench-section" aria-label="Dataset commit controls">
        <div className="workbench-control-grid">
          <label>Split<select value={draft.splitStrategy} onChange={(event) => patchDraft({ splitStrategy: event.target.value as DatasetWorkbenchDraft["splitStrategy"] })}><option value="TIME_BASED">Time based</option><option value="DEVICE_BASED">Device based</option><option value="RANDOM">Random</option></select></label>
          <label>Feature config<input value={draft.featureConfigVersion} onChange={(event) => patchDraft({ featureConfigVersion: event.target.value })} /></label>
          <label>Min samples<input min="1" type="number" value={draft.minSamplesPerMetric} onChange={(event) => patchDraft({ minSamplesPerMetric: Number(event.target.value) })} /></label>
          <label>Max gap seconds<input min="1" type="number" value={draft.maxSamplingGapSeconds} onChange={(event) => patchDraft({ maxSamplingGapSeconds: Number(event.target.value) })} /></label>
        </div>
        {validation.createDisabledReason && <div className="warning-message">{validation.createDisabledReason}</div>}
        <div className="action-row action-row-start">
          <button className="ghost-button" disabled={previewState.status === "loading"} onClick={() => void runPreview()} type="button">
            {previewState.status === "loading" ? "Previewing..." : previewState.status === "stale" ? "Refresh preview" : "Preview dataset"}
          </button>
          <button className="primary-button" disabled={validation.createDisabled} title={validation.createDisabledReason ?? undefined}>
            Create dataset version
          </button>
          <button className="ghost-button" onClick={clearDraft} type="button">Reset draft</button>
        </div>
      </section>
    </form>
  );
}

function mergeDraft(stored: Partial<DatasetWorkbenchDraft> | null): DatasetWorkbenchDraft {
  return {
    ...defaultDatasetWorkbenchDraft,
    ...(stored ?? {}),
    modelFamily: stored?.modelFamily === "FAILURE_PREDICTION" ? "FAILURE_PREDICTION" : "ANOMALY_DETECTION",
    metricSelectionMode: stored?.metricSelectionMode === "ALL_AVAILABLE" ? "ALL_AVAILABLE" : "COMMON",
    selectedCandidateId: stored?.selectedCandidateId ?? null,
  };
}

function readDraft<T>(key: string): Partial<T> | null {
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as Partial<T>) : null;
  } catch {
    return null;
  }
}

function readableError(error: unknown) {
  if (error instanceof Error && error.message.trim()) {
    if (/service unavailable|503|temporarily unavailable/i.test(error.message)) {
      return "Dataset preview is temporarily unavailable while services warm up. Refresh preview in a few seconds.";
    }
    return error.message;
  }
  return "Dataset preview is unavailable. Try again after dependencies recover.";
}

function canAutoPreview(draft: DatasetWorkbenchDraft) {
  if (draft.deviceIds.length === 0 || !isValidRange(draft.from, draft.to)) {
    return false;
  }
  return draft.modelFamily !== "FAILURE_PREDICTION" || Boolean(draft.horizonDays && draft.horizonDays > 0);
}

function isDefaultDraft(draft: DatasetWorkbenchDraft) {
  return JSON.stringify(draft) === JSON.stringify(defaultDatasetWorkbenchDraft);
}
