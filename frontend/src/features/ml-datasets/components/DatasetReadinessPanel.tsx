import type { MlDatasetWorkbenchPreviewResponse } from "../../../analytics/mlModuleApi";
import type { DatasetWorkbenchDraft, DatasetWorkbenchPreviewStatus, DatasetWorkbenchValidation } from "../model/datasetWorkbench.types";
import {
  formatNumber,
  formatPercent,
  humanizeModelFamily,
  statusLabel,
  workbenchStatusClass,
} from "../model/datasetWorkbench.utils";

export function DatasetReadinessPanel({
  draft,
  preview,
  previewStatus,
  validation,
}: {
  draft: DatasetWorkbenchDraft;
  preview: MlDatasetWorkbenchPreviewResponse | null;
  previewStatus: DatasetWorkbenchPreviewStatus;
  validation: DatasetWorkbenchValidation;
}) {
  const selectedMetrics = draft.selectedMetrics.length;
  const finalStatus = previewStatus === "ready" && preview ? preview.status : previewStatus.toUpperCase();

  return (
    <section className="workbench-section workbench-readiness" aria-labelledby="workbench-readiness-title">
      <div className="workbench-section-header">
        <div>
          <h3 id="workbench-readiness-title">Readiness</h3>
          <span>{validation.createDisabledReason ?? "Fresh preview can be committed"}</span>
        </div>
        <span className={`workbench-status-pill ${workbenchStatusClass(finalStatus)}`}>{statusLabel(finalStatus)}</span>
      </div>
      <dl className="workbench-summary-grid">
        <div><dt>Scope</dt><dd>{draft.deviceIds.length} devices</dd></div>
        <div><dt>Metrics</dt><dd>{selectedMetrics} selected</dd></div>
        <div><dt>Family</dt><dd>{humanizeModelFamily(draft.modelFamily)}</dd></div>
        <div><dt>Horizon</dt><dd>{draft.modelFamily === "FAILURE_PREDICTION" ? `${draft.horizonDays} days` : "Not required"}</dd></div>
      </dl>
      {preview?.qualityGates.length ? (
        <div className="workbench-gate-list">
          <h4>Data quality gates</h4>
          {preview.qualityGates.map((gate) => (
            <article key={gate.key}>
              <span className={`workbench-status-pill ${workbenchStatusClass(gate.status)}`}>{statusLabel(gate.status)}</span>
              <div>
                <strong>{gate.label}</strong>
                <span>{gate.message}</span>
                <small>Observed {formatGateValue(gate.actual)} / threshold {formatGateValue(gate.threshold)}</small>
              </div>
            </article>
          ))}
        </div>
      ) : null}
      {preview ? (
        <div className="workbench-gate-list">
          <h4>Metric coverage</h4>
          <article>
            <span className={`workbench-status-pill ${workbenchStatusClass(preview.status)}`}>{statusLabel(preview.status)}</span>
            <div>
              <strong>{preview.availableMetrics.length} available metrics</strong>
              <span>{preview.availableMetrics.filter((metric) => metric.status !== "BLOCKED").length} are usable with current gates.</span>
            </div>
          </article>
        </div>
      ) : null}
      {draft.modelFamily === "FAILURE_PREDICTION" ? (
        <div className="workbench-gate-list">
          <h4>Failure labels</h4>
          {preview?.labelReadiness ? (
            <article>
              <span className={`workbench-status-pill ${workbenchStatusClass(preview.labelReadiness.status)}`}>{statusLabel(preview.labelReadiness.status)}</span>
              <div>
                <strong>{preview.labelReadiness.horizonDays ?? draft.horizonDays}-day horizon readiness</strong>
                <span>{preview.labelReadiness.positiveLabels} positive / {preview.labelReadiness.normalLabels} normal / {preview.labelReadiness.unresolvedLabels} unresolved</span>
                <small>{formatPercent(preview.labelReadiness.coverageRatio)} coverage / {formatNumber(preview.labelReadiness.unobservableRows)} unobservable rows</small>
              </div>
            </article>
          ) : (
            <div className="warning-message">Failure-prediction preview has not returned horizon label readiness.</div>
          )}
        </div>
      ) : null}
      <div className="workbench-gate-list">
        <h4>Downstream compatibility</h4>
        <article>
          <span className={`workbench-status-pill ${validation.createDisabled ? "workbench-status-warning" : "workbench-status-ready"}`}>
            {validation.createDisabled ? "Review" : "Ready"}
          </span>
          <div>
            <strong>Feature snapshot defaults</strong>
            <span>{humanizeModelFamily(draft.modelFamily)}{draft.modelFamily === "FAILURE_PREDICTION" ? ` / ${draft.horizonDays} days` : ""}</span>
          </div>
        </article>
      </div>
      {validation.blockingReasons.length > 0 && (
        <div className="error-message">
          <strong>Blocking reasons</strong>
          <ul>{validation.blockingReasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
        </div>
      )}
      {validation.warningReasons.length > 0 && (
        <div className="warning-message">
          <strong>Warnings</strong>
          <ul>{validation.warningReasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
        </div>
      )}
    </section>
  );
}

function formatGateValue(value: unknown) {
  if (typeof value === "number") {
    return Number.isInteger(value) ? value.toLocaleString() : value.toLocaleString(undefined, { maximumFractionDigits: 2 });
  }
  if (value === null || value === undefined) {
    return "N/A";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}
