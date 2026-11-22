import { useMemo } from "react";
import { PredictionResponse } from "../../../analytics/analyticsApi";
import {
  MlDatasetSummary,
  MlDeploymentPolicy,
  MlModelVersion,
  MlTrainingRun,
} from "../../../analytics/mlModuleApi";
import { Device } from "../../../devices/devicesApi";
import {
  formatPercentMetric,
  formatQualityValue,
  formatSignedMetric,
  humanizeFeatureName,
  humanizeKey,
  metricNumber,
  stringMetric,
} from "./mlOperationalPresentation";

export function MlMonitoringTab(props: {
  dataAvailable?: {
    datasets: boolean;
    models: boolean;
    policies: boolean;
    trainingRuns: boolean;
  };
  datasets: MlDatasetSummary[];
  deviceId: string;
  models: MlModelVersion[];
  policies: MlDeploymentPolicy[];
  prediction: PredictionResponse | null;
  statusError: string | null;
  trainingRuns: MlTrainingRun[];
}) {
  const stats = useMemo(() => {
    const failedRuns = props.trainingRuns.filter(
      (run) => run.status === "FAILED",
    ).length;
    const fallbackPolicies = props.policies.filter(
      (policy) => policy.fallbackEnabled,
    ).length;
    const readyDatasets = props.datasets.filter(
      (dataset) => dataset.status === "READY",
    ).length;
    const activeModels = props.models.filter(
      (model) => model.status === "ACTIVE",
    ).length;
    const fallbackRate =
      props.policies.length === 0
        ? null
        : Math.round((fallbackPolicies / props.policies.length) * 100);
    const errorRate =
      props.trainingRuns.length === 0
        ? null
        : Math.round((failedRuns / props.trainingRuns.length) * 100);
    const predictionQuality =
      props.prediction &&
      Number.isFinite(props.prediction.confidence) &&
      !/DISABLED|INSUFFICIENT|ERROR|UNAVAILABLE|NO_DATA/i.test(
        props.prediction.predictionStatus,
      )
        ? Math.round(props.prediction.confidence * 100)
        : null;
    const latencyStatus = "No latency measurement is exposed by this API.";
    const driftStatus =
      activeModels > 0 && readyDatasets > 0
        ? "Watch feature hash changes"
        : "Insufficient model baseline";
    const deploymentPath =
      props.prediction?.predictionPath ??
      props.prediction?.deploymentMode ??
      "N/A";
    const canaryState =
      props.prediction?.canaryIncluded === true
        ? "Included"
        : props.prediction?.canaryIncluded === false
          ? "Excluded"
          : "N/A";
    return {
      activeModels,
      canaryState,
      deploymentPath,
      driftStatus,
      errorRate,
      failedRuns,
      fallbackPolicies,
      fallbackRate,
      latencyStatus,
      predictionQuality,
      readyDatasets,
    };
  }, [
    props.datasets,
    props.models,
    props.policies,
    props.prediction,
    props.trainingRuns,
  ]);

  return (
    <div className="ml-workflow-grid">
      <section className="panel">
        <h2>ML operations snapshot</h2>
        <div className="ml-stat-grid">
          <article>
            <span>Ready datasets (loaded)</span>
            <strong>
              {props.dataAvailable?.datasets === false
                ? "Unavailable"
                : stats.readyDatasets}
            </strong>
          </article>
          <article>
            <span>Active models (loaded)</span>
            <strong>
              {props.dataAvailable?.models === false
                ? "Unavailable"
                : stats.activeModels}
            </strong>
          </article>
          <article>
            <span>Fallback-enabled policies</span>
            <strong>
              {props.dataAvailable?.policies === false ||
              stats.fallbackRate === null
                ? "Unavailable"
                : `${stats.fallbackPolicies} / ${props.policies.length} (${stats.fallbackRate}%)`}
            </strong>
            <small>
              Configuration share in loaded scopes; runtime fallback frequency
              unavailable.
            </small>
          </article>
          <article>
            <span>Failed training runs</span>
            <strong>
              {props.dataAvailable?.trainingRuns === false ||
              stats.errorRate === null
                ? "Unavailable"
                : `${stats.failedRuns} / ${props.trainingRuns.length} (${stats.errorRate}%)`}
            </strong>
            <small>Loaded lifecycle records, all returned dates.</small>
          </article>
          <article>
            <span>Prediction confidence</span>
            <strong>
              {stats.predictionQuality === null
                ? "Unavailable"
                : `${stats.predictionQuality}%`}
            </strong>
            <small>
              {props.prediction?.createdAt ?? "No prediction observation"}
            </small>
          </article>
          <article>
            <span>Inference latency</span>
            <strong>Unavailable</strong>
          </article>
          <article>
            <span>Deployment path</span>
            <strong>{stats.deploymentPath}</strong>
          </article>
          <article>
            <span>Canary</span>
            <strong>{stats.canaryState}</strong>
          </article>
        </div>
        <div className="ml-readout">
          <strong>Drift</strong>
          <span>{stats.driftStatus}</span>
        </div>
        <div className="ml-readout">
          <strong>Latency</strong>
          <span>{stats.latencyStatus}</span>
        </div>
        <div className="ml-readout">
          <strong>Fallback policies</strong>
          <span>
            {props.dataAvailable?.policies === false
              ? "Unavailable policy evidence"
              : `${stats.fallbackPolicies} policy scopes have baseline fallback enabled`}
          </span>
        </div>
        <div className="ml-readout">
          <strong>Training errors</strong>
          <span>
            {props.dataAvailable?.trainingRuns === false
              ? "Unavailable training evidence"
              : `${stats.failedRuns} failed runs in the current list`}
          </span>
        </div>
        <h3>Deployment comparison</h3>
        <dl className="ml-model-metric-grid">
          <div>
            <dt>Active</dt>
            <dd>
              {props.prediction?.predictionPath === "ACTIVE"
                ? statusText(props.prediction.predictionStatus)
                : "No active path in latest trace"}
            </dd>
          </div>
          <div>
            <dt>Shadow</dt>
            <dd>
              {props.prediction?.shadowPrediction
                ? statusText(props.prediction.predictionStatus)
                : "Stored separately from latest active"}
            </dd>
          </div>
          <div>
            <dt>Canary</dt>
            <dd>
              {props.prediction?.deploymentMode === "CANARY"
                ? formatCanaryState(props.prediction)
                : "No canary policy in latest trace"}
            </dd>
          </div>
          <div>
            <dt>Fallback</dt>
            <dd>
              {props.prediction
                ? props.prediction.predictionPath === "FALLBACK"
                  ? props.prediction.fallbackReason || "Fallback used"
                  : "Not used in this trace"
                : "No prediction trace"}
            </dd>
          </div>
        </dl>
      </section>
      <MlStatus
        deviceMode={Boolean(props.deviceId)}
        error={props.statusError}
        prediction={props.prediction}
      />
    </div>
  );
}

function MlStatus({
  prediction,
  error,
  deviceMode,
}: {
  prediction: PredictionResponse | null;
  error: string | null;
  deviceMode: boolean;
}) {
  if (!deviceMode)
    return (
      <section className="panel">
        <h2>Latest prediction</h2>
        <div className="empty-state">
          Select a device to inspect read-only ML metadata.
        </div>
      </section>
    );
  if (!prediction)
    return (
      <section className="panel">
        <h2>Latest prediction</h2>
        <div className="empty-state">
          {error ??
            "Prediction metadata is unavailable. Settings remain editable."}
        </div>
      </section>
    );
  const explanations = prediction.explanations ?? [];
  const riskDrivers = normalizeRiskDrivers(prediction.riskDrivers ?? []);
  const hasFailureRisk =
    prediction.failureRiskScore !== null &&
    prediction.failureRiskScore !== undefined;
  return (
    <section className="panel">
      <div className="section-header">
        <div>
          <h2>Latest prediction</h2>
          <p>Read-only traceability</p>
        </div>
        <span className="status-badge">
          {statusText(prediction.predictionStatus)}
        </span>
      </div>
      {hasFailureRisk && (
        <FailureRiskDetail prediction={prediction} riskDrivers={riskDrivers} />
      )}
      <dl className="detail-list">
        <div>
          <dt>Model</dt>
          <dd>
            {prediction.modelName} / {prediction.modelVersion}
          </dd>
        </div>
        <div>
          <dt>Family / type</dt>
          <dd>
            {prediction.modelFamily ?? "Unknown"} /{" "}
            {prediction.modelType ?? "Unknown"}
          </dd>
        </div>
        <div>
          <dt>Model status</dt>
          <dd>{prediction.modelStatus ?? "Unknown"}</dd>
        </div>
        <div>
          <dt>Policy</dt>
          <dd>
            {prediction.policySource ?? "BASELINE"} /{" "}
            {prediction.deploymentMode ?? "DISABLED"}
          </dd>
        </div>
        <div>
          <dt>Prediction path</dt>
          <dd>{prediction.predictionPath ?? "Unknown"}</dd>
        </div>
        <div>
          <dt>Device type</dt>
          <dd>{prediction.resolvedDeviceType || "Unknown"}</dd>
        </div>
        <div>
          <dt>Canary</dt>
          <dd>{formatCanaryState(prediction)}</dd>
        </div>
        <div>
          <dt>Fallback</dt>
          <dd>{prediction.fallbackReason || "None"}</dd>
        </div>
        <div>
          <dt>Confidence</dt>
          <dd>{Math.round(prediction.confidence * 100)}%</dd>
        </div>
        <div>
          <dt>Model version id</dt>
          <dd className="mono-value">
            {prediction.modelVersionId ?? "Unavailable"}
          </dd>
        </div>
        <div>
          <dt>Input hash</dt>
          <dd className="mono-value">
            {prediction.inputHash || "Unavailable"}
          </dd>
        </div>
      </dl>
      <dl className="ml-model-metric-grid">
        <div>
          <dt>Failure risk</dt>
          <dd>{formatPercentMetric(prediction.failureRiskScore ?? null)}</dd>
        </div>
        <div>
          <dt>Anomaly score</dt>
          <dd>{formatPercentMetric(prediction.anomalyScore)}</dd>
        </div>
        <div>
          <dt>Degradation score</dt>
          <dd>{formatPercentMetric(prediction.degradationScore)}</dd>
        </div>
      </dl>
      <details className="ml-raw-details">
        <summary>Raw signals</summary>
        <pre>{JSON.stringify(prediction.signals ?? [], null, 2)}</pre>
      </details>
      <h3>Explanations</h3>
      <ul>
        {explanations.map((x, i) => (
          <li key={i}>{x}</li>
        ))}
      </ul>
      <h3>Recommended actions</h3>
      <ul>
        {(prediction.recommendedActions ?? []).map((x, i) => (
          <li key={i}>{x}</li>
        ))}
      </ul>
    </section>
  );
}

function FailureRiskDetail({
  prediction,
  riskDrivers,
}: {
  prediction: PredictionResponse;
  riskDrivers: RiskDriverView[];
}) {
  return (
    <section className="ml-failure-risk-panel" aria-label="Failure risk detail">
      <div className="ml-risk-score">
        <span>
          {prediction.failureHorizonDays
            ? `${prediction.failureHorizonDays}-day failure risk`
            : "Failure risk"}
        </span>
        <strong>
          {formatPercentMetric(prediction.failureRiskScore ?? null)}
        </strong>
        <small>
          {prediction.failureRiskBand ?? "Unbanded"} / confidence{" "}
          {formatPercentMetric(prediction.confidence)}
        </small>
      </div>
      <dl className="ml-risk-context-grid">
        <div>
          <dt>Label coverage</dt>
          <dd>{formatPercentMetric(prediction.labelCoverage ?? null)}</dd>
        </div>
        <div>
          <dt>Training window</dt>
          <dd>{formatTrainingWindow(prediction.trainingDataWindow)}</dd>
        </div>
        <div>
          <dt>Model output</dt>
          <dd>Separate from anomaly and degradation scores</dd>
        </div>
      </dl>
      <RiskDriversList drivers={riskDrivers} />
    </section>
  );
}

function RiskDriversList({ drivers }: { drivers: RiskDriverView[] }) {
  if (drivers.length === 0) {
    return (
      <div className="empty-state">
        No risk drivers returned for this prediction.
      </div>
    );
  }
  const maxContribution = Math.max(
    ...drivers.map((driver) => Math.abs(driver.contribution)),
    0.000001,
  );
  return (
    <div className="ml-risk-driver-list">
      <h3>Risk drivers</h3>
      {drivers.map((driver) => {
        const width = Math.max(
          6,
          Math.round((Math.abs(driver.contribution) / maxContribution) * 100),
        );
        return (
          <article
            className="ml-risk-driver-row"
            key={`${driver.feature}-${driver.contribution}`}
          >
            <div>
              <strong>{humanizeFeatureName(driver.feature)}</strong>
              <span>
                {readableDriverDirection(driver.direction)} / value{" "}
                {formatDriverNumber(driver.value)} / weight{" "}
                {formatDriverNumber(driver.weight)}
              </span>
            </div>
            <div className="ml-risk-driver-impact">
              <span>{formatSignedMetric(driver.contribution)}</span>
              <i style={{ width: `${width}%` }} />
            </div>
          </article>
        );
      })}
    </div>
  );
}

function statusText(v: string) {
  return (
    (
      {
        UNAVAILABLE: "ML unavailable; rule analytics continue",
        INSUFFICIENT_DATA: "More telemetry required",
        SUPPRESSED_MAINTENANCE: "Suppressed during maintenance",
      } as Record<string, string>
    )[v] ?? v
  );
}

function formatCanaryState(prediction: PredictionResponse) {
  const percent =
    prediction.canaryPercent === null || prediction.canaryPercent === undefined
      ? ""
      : ` / ${prediction.canaryPercent}%`;
  if (prediction.canaryIncluded === true) {
    return `Included${percent}`;
  }
  if (prediction.canaryIncluded === false) {
    return `Excluded${percent}`;
  }
  return percent ? percent.slice(3) : "N/A";
}

type RiskDriverView = {
  feature: string;
  value: number | null;
  weight: number | null;
  contribution: number;
  direction: string;
};

function normalizeRiskDrivers(drivers: Array<Record<string, unknown>>) {
  return drivers
    .map((driver) => {
      const feature =
        stringMetric(driver, "feature") ??
        stringMetric(driver, "metric") ??
        "unknown_feature";
      const contribution =
        metricNumber(driver, "contribution") ??
        metricNumber(driver, "score") ??
        0;
      return {
        feature,
        value: metricNumber(driver, "value"),
        weight: metricNumber(driver, "weight"),
        contribution,
        direction:
          stringMetric(driver, "direction") ??
          (contribution >= 0 ? "increases_risk" : "decreases_risk"),
      };
    })
    .sort(
      (left, right) =>
        Math.abs(right.contribution) - Math.abs(left.contribution),
    );
}

function readableDriverDirection(value: string) {
  if (value.toLowerCase().includes("decrease")) {
    return "Lowers risk";
  }
  if (value.toLowerCase().includes("increase")) {
    return "Raises risk";
  }
  return humanizeKey(value);
}

function formatDriverNumber(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  return value.toLocaleString(undefined, { maximumFractionDigits: 4 });
}

function formatTrainingWindow(value: Record<string, unknown> | undefined) {
  if (!value || Object.keys(value).length === 0) {
    return "Unavailable";
  }
  const start =
    stringMetric(value, "start") ??
    stringMetric(value, "from") ??
    stringMetric(value, "windowStart");
  const end =
    stringMetric(value, "end") ??
    stringMetric(value, "to") ??
    stringMetric(value, "windowEnd");
  if (start || end) {
    return `${start ?? "unknown start"} - ${end ?? "unknown end"}`;
  }
  return Object.entries(value)
    .map(([key, item]) => `${humanizeKey(key)}: ${formatQualityValue(item)}`)
    .join(" / ");
}
