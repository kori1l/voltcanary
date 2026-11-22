import { useState } from "react";
import {
  approveMlModel,
  archiveMlModel,
  MlLifecycleStatusFilter,
  MlModelVersion,
  rejectMlModel,
} from "../../../analytics/mlModuleApi";
import { useActionConfirmation } from "../../../components/useActionConfirmation";
import { useEntityCommands } from "../../../components/useEntityCommands";
import { CommandFeedback } from "../../../components/CommandFeedback";
import {
  StatusFilterSelect,
  formatDecimalMetric,
  formatPercentMetric,
  formatQualityValue,
  formatSignedMetric,
  humanizeFeatureName,
  humanizeKey,
  metricNumber,
  modelOriginLabel,
  stringMetric,
} from "./mlOperationalPresentation";

export function MlModelsTab(props: {
  selectedModelId?: string;
  onClearSelection?: () => void;
  models: MlModelVersion[];
  modelsAvailable?: boolean;
  statusFilter: MlLifecycleStatusFilter;
  onChanged: () => Promise<void>;
  onConfirmed?: () => void;
  isDeletePending?: (id: string) => boolean;
  onDelete: (model: MlModelVersion) => Promise<void>;
  onFilterChange: (value: MlLifecycleStatusFilter) => Promise<void>;
  setError: (value: string | null) => void;
}) {
  const confirmation = useActionConfirmation();
  const commands = useEntityCommands();
  const visibleModels = props.selectedModelId
    ? props.models.filter((model) => model.id === props.selectedModelId)
    : props.models;
  const [savedModels, setSavedModels] = useState<Record<string, MlModelVersion>>({});
  async function modelCommand(model: MlModelVersion, action: "approve" | "reject" | "archive") {
    let reason = "";
    const label = `${action[0].toUpperCase()}${action.slice(1)} ${model.modelKey} v${model.version}`;
    await commands.run(`model:${model.id}:${action}`, {
      label,
      review: async () => {
        const answer = await confirmation.confirm({
          title: `${action[0].toUpperCase()}${action.slice(1)} model`,
          object: `${model.modelKey} v${model.version}`,
          impact:
            action === "approve"
              ? "Make this version eligible for deployment selection. Approval does not activate a policy; server promotion gates remain required."
              : action === "reject"
                ? "Reject this model version for deployment. Existing policy and lifecycle restrictions are enforced by the server."
                : "Archives this model version while retaining its artifact and lineage history. The server validates model state and references; archive has no impact-preview endpoint.",
          reasonRequired: true,
        });
        reason = answer ?? "";
        return Boolean(answer);
      },
      mutate: () =>
        action === "approve"
          ? approveMlModel(model.id, reason)
          : action === "reject"
            ? rejectMlModel(model.id, reason)
            : archiveMlModel(model.id, reason),
      onCommitted: (saved) => {
        props.onConfirmed?.();
        setSavedModels((current) => ({ ...current, [model.id]: saved }));
      },
      refresh: props.onChanged,
    });
  }
  return (
    <section className="panel">
      {confirmation.dialog}
      <CommandFeedback outcomes={commands.outcomes} onRetryRead={commands.retryRead} />
      <div className="section-header">
        <div>
          <h2>Model registry</h2>
        </div>
        <StatusFilterSelect
          label="Model status filter"
          value={props.statusFilter}
          onChange={props.onFilterChange}
        />
      </div>
      {props.selectedModelId ? (
        <div className="action-row">
          <span>Selected prediction model</span>
          <button type="button" className="ghost-button" onClick={props.onClearSelection}>
            Show all models
          </button>
        </div>
      ) : null}
      {props.modelsAvailable === false ? (
        <p>Model records unavailable or loading.</p>
      ) : visibleModels.length === 0 ? (
        <div className="empty-state">
          {props.selectedModelId
            ? "Selected model unavailable or outside the current status filter."
            : "No registered model versions yet."}
        </div>
      ) : (
        visibleModels.map((record) => {
          const model = savedModels[record.id] ?? record;
          const gates = model.family === "FAILURE_PREDICTION" ? approvalGateSummary(model) : null;
          const approveBlocked = Boolean(gates && !gates.promotionEligible);
          const modelLabel = `${model.modelKey} v${model.version}`;
          return (
            <div className="ml-model-row" key={model.id}>
              <div className="ml-model-body">
                <strong>{modelLabel}</strong>
                <span>
                  {model.family} / {humanizeAlgorithm(model.algorithm)} / {model.status} /{" "}
                  {modelOriginLabel(model)}
                </span>
                {model.family === "FAILURE_PREDICTION" ? (
                  <FailureModelCard gates={gates} model={model} />
                ) : (
                  <GenericModelMetrics model={model} />
                )}
              </div>
              <div className="action-row">
                {model.status !== "APPROVED" && model.status !== "ACTIVE" && (
                  <button
                    aria-label={`Approve ${modelLabel}`}
                    className="ghost-button"
                    disabled={approveBlocked || commands.isPending(`model:${model.id}:approve`)}
                    onClick={() => void modelCommand(model, "approve")}
                    title={
                      approveBlocked ? "Promotion gates must pass before approval." : undefined
                    }
                    type="button"
                  >
                    Approve
                  </button>
                )}
                {model.status !== "REJECTED" && model.status !== "ACTIVE" && (
                  <button
                    aria-label={`Reject ${modelLabel}`}
                    className="ghost-button"
                    disabled={commands.isPending(`model:${model.id}:reject`)}
                    onClick={() => void modelCommand(model, "reject")}
                    type="button"
                  >
                    Reject
                  </button>
                )}
                {model.status !== "ACTIVE" && (
                  <button
                    aria-label={`Archive ${modelLabel}`}
                    className="ghost-button"
                    disabled={commands.isPending(`model:${model.id}:archive`)}
                    onClick={() => void modelCommand(model, "archive")}
                    type="button"
                  >
                    Archive
                  </button>
                )}
                {["CANDIDATE", "REJECTED"].includes(model.status) && (
                  <button
                    aria-label={`Delete ${modelLabel}`}
                    className="ghost-button"
                    disabled={props.isDeletePending?.(model.id)}
                    onClick={() => void props.onDelete(model)}
                    type="button"
                  >
                    Delete
                  </button>
                )}
              </div>
            </div>
          );
        })
      )}
    </section>
  );
}

function FailureModelCard({
  gates,
  model,
}: {
  gates: ApprovalGateSummary | null;
  model: MlModelVersion;
}) {
  const metrics = model.evaluationMetrics ?? {};
  const horizonDays = metricNumber(metrics, "failureHorizonDays");
  const precision = metricNumber(metrics, "failurePrecision");
  const recall = metricNumber(metrics, "failureRecall");
  const prAuc = metricNumber(metrics, "failurePrAuc");
  const prAucLift = metricNumber(metrics, "failurePrAucLift");
  const falseAlerts = metricNumber(metrics, "failureFalseAlertsPerDeviceMonth");
  const leadTime = metricNumber(metrics, "failureAverageLeadTimeDays");
  const labelCoverage =
    metricNumber(metrics, "labelCoverage") ?? metricNumber(metrics, "failureLabelCoverage");
  const positives = metricNumber(metrics, "failurePositiveLabels");
  const negatives = metricNumber(metrics, "failureNegativeLabels");

  return (
    <div className="ml-model-card-summary">
      <div>
        <h3>{horizonDays ? `${horizonDays}-day failure horizon` : "Failure model card"}</h3>
        <p>
          Predicts confirmed failure or corrective-maintenance risk for operator review. Use
          alongside rule alerts and maintenance context.
        </p>
      </div>
      <dl className="ml-model-metric-grid">
        <div>
          <dt>Precision</dt>
          <dd>{formatPercentMetric(precision)}</dd>
        </div>
        <div>
          <dt>Recall</dt>
          <dd>{formatPercentMetric(recall)}</dd>
        </div>
        <div>
          <dt>PR-AUC</dt>
          <dd>{formatDecimalMetric(prAuc)}</dd>
        </div>
        <div>
          <dt>PR-AUC lift</dt>
          <dd>{formatSignedMetric(prAucLift)}</dd>
        </div>
        <div>
          <dt>False alerts</dt>
          <dd>
            {falseAlerts === null ? "N/A" : `${formatDecimalMetric(falseAlerts)} / device-month`}
          </dd>
        </div>
        <div>
          <dt>Lead time</dt>
          <dd>{leadTime === null ? "N/A" : `${formatDecimalMetric(leadTime)} days`}</dd>
        </div>
      </dl>
      <dl className="ml-model-lineage">
        <div>
          <dt>Labels</dt>
          <dd>
            {positives === null
              ? "N/A"
              : `${positives} positive${negatives === null ? "" : ` / ${negatives} negative`}`}
          </dd>
        </div>
        <div>
          <dt>Coverage</dt>
          <dd>{formatPercentMetric(labelCoverage)}</dd>
        </div>
        <div>
          <dt>Dataset</dt>
          <dd className="mono-value">{model.datasetVersionId ?? "Unavailable"}</dd>
        </div>
        <div>
          <dt>Feature hash</dt>
          <dd className="mono-value">{model.featureSchemaHash ?? "Unavailable"}</dd>
        </div>
        <div>
          <dt>Artifact</dt>
          <dd className="mono-value">
            {model.artifactHash ?? model.artifactManifestUri ?? model.artifactUri ?? "Unavailable"}
          </dd>
        </div>
      </dl>
      {gates && <ApprovalGatePanel gates={gates} />}
    </div>
  );
}

function ApprovalGatePanel({ gates }: { gates: ApprovalGateSummary }) {
  return (
    <section
      className={
        gates.promotionEligible
          ? "ml-gate-panel ml-gate-panel-pass"
          : "ml-gate-panel ml-gate-panel-blocked"
      }
      aria-label="Promotion gates"
    >
      <div>
        <strong>{gates.promotionEligible ? "Promotion gates passed" : "Promotion blocked"}</strong>
        <span>
          {gates.profileId
            ? `${gates.profileId}${gates.profileVersion ? ` / ${gates.profileVersion}` : ""}`
            : gates.promotionEligible
              ? "This candidate is eligible for approval."
              : "Resolve failed gates before approval."}
          {gates.thresholdSource ? ` / ${gates.thresholdSource}` : ""}
        </span>
      </div>
      {gates.criteria.length > 0 && (
        <dl className="ml-gate-list">
          {gates.criteria.map((gate) => (
            <div key={gate.name}>
              <dt>
                {humanizeFeatureName(gate.name)} / {gate.passed ? "Pass" : "Fail"}
              </dt>
              <dd>
                {gate.reason} Observed {formatGateValue(gate)} / required{" "}
                {formatGateThreshold(gate)}.
              </dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

function GenericModelMetrics({ model }: { model: MlModelVersion }) {
  const entries = Object.entries(model.evaluationMetrics ?? {});
  if (entries.length === 0) {
    return <small>No evaluation metrics captured yet.</small>;
  }
  return (
    <dl className="ml-model-lineage">
      {entries.slice(0, 6).map(([key, value]) => (
        <div key={key}>
          <dt>{humanizeKey(key)}</dt>
          <dd>{formatQualityValue(value)}</dd>
        </div>
      ))}
    </dl>
  );
}

type ApprovalGateCriterion = {
  name: string;
  observed: number | null;
  threshold: number | null;
  passed: boolean;
  reason: string;
};

type ApprovalGateSummary = {
  status: string;
  profileId: string | null;
  profileVersion: string | null;
  thresholdSource: string | null;
  promotionEligible: boolean;
  criteria: ApprovalGateCriterion[];
  failedCriteria: ApprovalGateCriterion[];
};

function humanizeAlgorithm(value: string) {
  return value
    .replace(/-v\d+$/i, "")
    .split("-")
    .map((part) =>
      part === "sklearn" ? "sklearn" : part.replace(/^./, (match) => match.toUpperCase()),
    )
    .join(" ");
}

function approvalGateSummary(model: MlModelVersion): ApprovalGateSummary {
  const explicit = explicitApprovalGateSummary(model.evaluationMetrics ?? {});
  if (explicit) {
    return explicit;
  }

  const metrics = model.evaluationMetrics ?? {};
  const positiveLabels = metricNumber(metrics, "failurePositiveLabels");
  const minimumPositiveLabels =
    metricNumber(metrics, "minimumPositiveLabels") ??
    metricNumber(metrics, "failureMinimumPositiveLabels") ??
    1;
  const labelCoverage =
    metricNumber(metrics, "labelCoverage") ?? metricNumber(metrics, "failureLabelCoverage");
  const minimumLabelCoverage =
    metricNumber(metrics, "minimumLabelCoverage") ??
    metricNumber(metrics, "failureMinimumLabelCoverage") ??
    0.8;
  const validationSamples = metricNumber(metrics, "failureValidationSamples");
  const hasEvaluation =
    validationSamples !== null
      ? validationSamples > 0
      : metricNumber(metrics, "failurePrAuc") !== null;
  const falseAlerts = metricNumber(metrics, "failureFalseAlertsPerDeviceMonth");
  const maxFalseAlerts = metricNumber(metrics, "maximumFalseAlertsPerDeviceMonth") ?? 30;
  const criteria: ApprovalGateCriterion[] = [
    {
      name: "minimum_positive_labels",
      observed: positiveLabels,
      threshold: minimumPositiveLabels,
      passed: positiveLabels !== null && positiveLabels >= minimumPositiveLabels,
      reason: "Requires enough confirmed failure or corrective maintenance labels.",
    },
    {
      name: "minimum_label_coverage",
      observed: labelCoverage,
      threshold: minimumLabelCoverage,
      passed: labelCoverage !== null && labelCoverage >= minimumLabelCoverage,
      reason: "Requires enough usable horizon labels for the dataset.",
    },
    {
      name: "time_based_evaluation_available",
      observed: hasEvaluation ? 1 : 0,
      threshold: 1,
      passed: hasEvaluation,
      reason: "Requires time-based holdout evaluation before promotion.",
    },
    {
      name: "maximum_false_alert_rate",
      observed: falseAlerts,
      threshold: maxFalseAlerts,
      passed: falseAlerts !== null && falseAlerts <= maxFalseAlerts,
      reason: "Limits false alerts per device-month before promotion.",
    },
    {
      name: "artifact_load_test",
      observed:
        model.artifactUri &&
        model.artifactManifestUri &&
        model.artifactHash &&
        model.artifactSizeBytes
          ? 1
          : 0,
      threshold: 1,
      passed: Boolean(
        model.artifactUri &&
        model.artifactManifestUri &&
        model.artifactHash &&
        model.artifactSizeBytes,
      ),
      reason: "Requires a persisted, hashed, load-tested model bundle.",
    },
    {
      name: "feature_schema_compatibility",
      observed: model.featureSchemaHash ? 1 : 0,
      threshold: 1,
      passed: Boolean(model.featureSchemaHash),
      reason: "Requires a feature schema hash for runtime compatibility checks.",
    },
  ];
  const failedCriteria = criteria.filter((gate) => !gate.passed);
  return {
    status: failedCriteria.length === 0 ? "PASSED" : "NEEDS_REVIEW",
    profileId: "fallback-ui-derived",
    profileVersion: null,
    thresholdSource: "ui-derived-legacy-metrics",
    promotionEligible: failedCriteria.length === 0,
    criteria,
    failedCriteria,
  };
}

function explicitApprovalGateSummary(metrics: Record<string, unknown>): ApprovalGateSummary | null {
  const raw = metrics.approvalGates;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return null;
  }
  const gateObject = raw as Record<string, unknown>;
  const criteria = Array.isArray(gateObject.criteria) ? gateObject.criteria : [];
  const normalizedCriteria = criteria
    .filter(
      (item): item is Record<string, unknown> =>
        Boolean(item) && typeof item === "object" && !Array.isArray(item),
    )
    .map((item) => ({
      name: stringMetric(item, "name") ?? "approval_gate",
      observed: metricNumber(item, "observed"),
      threshold: metricNumber(item, "threshold"),
      passed: Boolean(item.passed),
      reason: stringMetric(item, "reason") ?? "Approval gate failed.",
    }));
  const failedCriteria = normalizedCriteria.filter((item) => !item.passed);
  const promotionEligible =
    typeof gateObject.promotionEligible === "boolean"
      ? gateObject.promotionEligible
      : failedCriteria.length === 0;
  return {
    status: stringMetric(gateObject, "status") ?? (promotionEligible ? "PASSED" : "NEEDS_REVIEW"),
    profileId:
      stringMetric(gateObject, "profileId") ?? stringMetric(metrics, "approvalGateProfileId"),
    profileVersion:
      stringMetric(gateObject, "profileVersion") ??
      stringMetric(metrics, "approvalGateProfileVersion"),
    thresholdSource:
      stringMetric(gateObject, "thresholdSource") ??
      stringMetric(metrics, "approvalGateThresholdSource"),
    promotionEligible,
    criteria: normalizedCriteria,
    failedCriteria,
  };
}

function formatGateValue(gate: ApprovalGateCriterion) {
  if (gate.observed === null) {
    return "N/A";
  }
  if (
    gate.name.includes("coverage") ||
    gate.name.includes("precision") ||
    gate.name.includes("recall")
  ) {
    return formatPercentMetric(gate.observed);
  }
  return formatGateNumber(gate.observed);
}

function formatGateThreshold(gate: ApprovalGateCriterion) {
  if (gate.threshold === null) {
    return "N/A";
  }
  if (
    gate.name.includes("coverage") ||
    gate.name.includes("precision") ||
    gate.name.includes("recall")
  ) {
    return formatPercentMetric(gate.threshold);
  }
  return formatGateNumber(gate.threshold);
}

function formatGateNumber(value: number) {
  return Number.isInteger(value) ? String(value) : formatDecimalMetric(value);
}
