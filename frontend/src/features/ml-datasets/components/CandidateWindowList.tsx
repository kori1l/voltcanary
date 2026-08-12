import type { MlDatasetWorkbenchPreviewResponse } from "../../../analytics/mlModuleApi";
import type { DatasetWorkbenchDraft } from "../model/datasetWorkbench.types";
import {
  formatDateTime,
  formatNumber,
  formatPercent,
  formatSeconds,
  isoToDateTimeLocal,
  statusLabel,
  workbenchStatusClass,
} from "../model/datasetWorkbench.utils";

export function CandidateWindowList({
  draft,
  preview,
  onPatch,
}: {
  draft: DatasetWorkbenchDraft;
  preview: MlDatasetWorkbenchPreviewResponse | null;
  onPatch: (patch: Partial<DatasetWorkbenchDraft>, affectsPreview?: boolean) => void;
}) {
  const candidates = preview?.candidateWindows ?? [];

  return (
    <section className="workbench-section" aria-labelledby="workbench-candidates-title">
      <div className="workbench-section-header">
        <div>
          <h3 id="workbench-candidates-title">Candidate windows</h3>
          <span>{candidates.length} preview recommendations</span>
        </div>
      </div>
      {candidates.length === 0 ? (
        <div className="empty-state">No candidate windows returned for the current scope.</div>
      ) : (
        <div className="workbench-candidate-list">
          {candidates.map((candidate) => {
            const selected = candidate.id === draft.selectedCandidateId;
            return (
              <article className={`workbench-candidate-card ${selected ? "workbench-candidate-card-selected" : ""}`} key={candidate.id}>
                <div className="workbench-candidate-title">
                  <div>
                    <strong>{statusLabel(candidate.kind)}</strong>
                    <span>{formatDateTime(candidate.from)} - {formatDateTime(candidate.to)}</span>
                  </div>
                  <span className={`workbench-status-pill ${workbenchStatusClass(candidate.status)}`}>{statusLabel(candidate.status)}</span>
                </div>
                <dl className="workbench-candidate-grid">
                  <div><dt>Score</dt><dd>{formatPercent(candidate.score)}</dd></div>
                  <div><dt>Samples</dt><dd>{formatNumber(candidate.sampleCount)}</dd></div>
                  <div><dt>Coverage</dt><dd>{formatPercent(candidate.coverageRatio)}</dd></div>
                  <div><dt>Max gap</dt><dd>{formatSeconds(candidate.maxSamplingGapSeconds)}</dd></div>
                  <div><dt>Labels</dt><dd>{candidate.positiveLabels} positive / {candidate.normalLabels} normal</dd></div>
                  <div><dt>Excluded</dt><dd>{candidate.maintenanceExcludedLabels} maintenance / {candidate.unobservableLabels} unobservable</dd></div>
                </dl>
                <p>{candidate.explanation}</p>
                {candidate.blockingReasons.length > 0 && <div className="error-message">{candidate.blockingReasons.join(" ")}</div>}
                {candidate.warningReasons.length > 0 && <div className="warning-message">{candidate.warningReasons.join(" ")}</div>}
                <button
                  className="ghost-button"
                  onClick={() => onPatch({
                    from: isoToDateTimeLocal(candidate.from),
                    to: isoToDateTimeLocal(candidate.to),
                    quickRange: "custom",
                    selectedCandidateId: candidate.id,
                  })}
                  type="button"
                >
                  Use window
                </button>
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}
