import { PredictionResponse } from "../../../analytics/analyticsApi";
import { Device } from "../../../devices/devicesApi";

import { statusText, formatCanaryState, RiskDriverView, formatPercentMetric, formatSignedMetric, normalizeRiskDrivers, readableDriverDirection, humanizeFeatureName, formatDriverNumber, formatTrainingWindow } from "../model/mlConsolePresentation";
export function MlStatus({ prediction, error, deviceMode }: { prediction: PredictionResponse | null; error: string | null; deviceMode: boolean }) {
  if (!deviceMode) return <section className="panel"><h2>Latest prediction</h2><div className="empty-state">Select a device to inspect read-only ML metadata.</div></section>;
  if (!prediction) return <section className="panel"><h2>Latest prediction</h2><div className="empty-state">{error ?? "Prediction metadata is unavailable. Settings remain editable."}</div></section>;
  const explanations = prediction.explanations ?? [];
  const riskDrivers = normalizeRiskDrivers(prediction.riskDrivers ?? []);
  const hasFailureRisk = prediction.failureRiskScore !== null && prediction.failureRiskScore !== undefined;
  return (
    <section className="panel">
      <div className="section-header">
        <div><h2>Latest prediction</h2><p>Read-only traceability</p></div>
        <span className="status-badge">{statusText(prediction.predictionStatus)}</span>
      </div>
      {hasFailureRisk && <FailureRiskDetail prediction={prediction} riskDrivers={riskDrivers} />}
      <dl className="detail-list">
        <div><dt>Model</dt><dd>{prediction.modelName} / {prediction.modelVersion}</dd></div>
        <div><dt>Family / type</dt><dd>{prediction.modelFamily ?? "Unknown"} / {prediction.modelType ?? "Unknown"}</dd></div>
        <div><dt>Model status</dt><dd>{prediction.modelStatus ?? "Unknown"}</dd></div>
        <div><dt>Policy</dt><dd>{prediction.policySource ?? "BASELINE"} / {prediction.deploymentMode ?? "DISABLED"}</dd></div>
        <div><dt>Prediction path</dt><dd>{prediction.predictionPath ?? "Unknown"}</dd></div>
        <div><dt>Device type</dt><dd>{prediction.resolvedDeviceType || "Unknown"}</dd></div>
        <div><dt>Canary</dt><dd>{formatCanaryState(prediction)}</dd></div>
        <div><dt>Fallback</dt><dd>{prediction.fallbackReason || "None"}</dd></div>
        <div><dt>Confidence</dt><dd>{Math.round(prediction.confidence * 100)}%</dd></div>
        <div><dt>Model version id</dt><dd className="mono-value">{prediction.modelVersionId ?? "Unavailable"}</dd></div>
        <div><dt>Input hash</dt><dd className="mono-value">{prediction.inputHash || "Unavailable"}</dd></div>
      </dl>
      <dl className="ml-model-metric-grid">
        <div><dt>Failure risk</dt><dd>{formatPercentMetric(prediction.failureRiskScore ?? null)}</dd></div>
        <div><dt>Anomaly score</dt><dd>{formatPercentMetric(prediction.anomalyScore)}</dd></div>
        <div><dt>Degradation score</dt><dd>{formatPercentMetric(prediction.degradationScore)}</dd></div>
      </dl>
      <details className="ml-raw-details">
        <summary>Raw signals</summary>
        <pre>{JSON.stringify(prediction.signals ?? [], null, 2)}</pre>
      </details>
      <h3>Explanations</h3><ul>{explanations.map((x, i) => <li key={i}>{x}</li>)}</ul>
      <h3>Recommended actions</h3><ul>{(prediction.recommendedActions ?? []).map((x, i) => <li key={i}>{x}</li>)}</ul>
    </section>
  );
}

function FailureRiskDetail({ prediction, riskDrivers }: { prediction: PredictionResponse; riskDrivers: RiskDriverView[] }) {
  return (
    <section className="ml-failure-risk-panel" aria-label="Failure risk detail">
      <div className="ml-risk-score">
        <span>{prediction.failureHorizonDays ? `${prediction.failureHorizonDays}-day failure risk` : "Failure risk"}</span>
        <strong>{formatPercentMetric(prediction.failureRiskScore ?? null)}</strong>
        <small>{prediction.failureRiskBand ?? "Unbanded"} / confidence {formatPercentMetric(prediction.confidence)}</small>
      </div>
      <dl className="ml-risk-context-grid">
        <div><dt>Label coverage</dt><dd>{formatPercentMetric(prediction.labelCoverage ?? null)}</dd></div>
        <div><dt>Training window</dt><dd>{formatTrainingWindow(prediction.trainingDataWindow)}</dd></div>
        <div><dt>Model output</dt><dd>Separate from anomaly and degradation scores</dd></div>
      </dl>
      <RiskDriversList drivers={riskDrivers} />
    </section>
  );
}

function RiskDriversList({ drivers }: { drivers: RiskDriverView[] }) {
  if (drivers.length === 0) {
    return <div className="empty-state">No risk drivers returned for this prediction.</div>;
  }
  const maxContribution = Math.max(...drivers.map((driver) => Math.abs(driver.contribution)), 0.000001);
  return (
    <div className="ml-risk-driver-list">
      <h3>Risk drivers</h3>
      {drivers.map((driver) => {
        const width = Math.max(6, Math.round((Math.abs(driver.contribution) / maxContribution) * 100));
        return (
          <article className="ml-risk-driver-row" key={`${driver.feature}-${driver.contribution}`}>
            <div>
              <strong>{humanizeFeatureName(driver.feature)}</strong>
              <span>{readableDriverDirection(driver.direction)} / value {formatDriverNumber(driver.value)} / weight {formatDriverNumber(driver.weight)}</span>
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

