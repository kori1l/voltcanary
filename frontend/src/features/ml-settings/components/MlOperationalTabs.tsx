import { FormEvent, useEffect, useMemo, useState } from "react";
import { PredictionResponse } from "../../../analytics/analyticsApi";
import { approveMlModel, archiveMlModel, MlDatasetSummary, MlDeploymentMode, MlDeploymentPolicy, MlDeploymentScope, MlFeatureSnapshotResponse, MlLabelType, MlLifecycleStatusFilter, MlModelVersion, MlTrainingRun, rejectMlModel, resolveDeploymentPolicy, saveDeploymentPolicy } from "../../../analytics/mlModuleApi";
import { safeMlError } from "../../../analytics/mlSettingsApi";
import { Device, DeviceType } from "../../../devices/devicesApi";
import { useActionConfirmation } from "../../../components/useActionConfirmation";

const lifecycleStatusFilters: MlLifecycleStatusFilter[] = ["default", "active", "failed", "archived", "deleted", "all"];

function StatusFilterSelect(props: {
  label: string;
  value: MlLifecycleStatusFilter;
  onChange: (value: MlLifecycleStatusFilter) => Promise<void>;
}) {
  return (
    <label className="compact-select">
      {props.label}
      <select value={props.value} onChange={(event) => void props.onChange(event.target.value as MlLifecycleStatusFilter)}>
        {lifecycleStatusFilters.map((value) => <option key={value} value={value}>{humanizeKey(value)}</option>)}
      </select>
    </label>
  );
}
function ModelsTab(props: { models: MlModelVersion[]; modelsAvailable?: boolean; statusFilter: MlLifecycleStatusFilter; onChanged: () => Promise<void>; onDelete: (model: MlModelVersion) => Promise<void>; onFilterChange: (value: MlLifecycleStatusFilter) => Promise<void>; setError: (value: string | null) => void }) {
  const confirmation = useActionConfirmation();
  async function archive(model: MlModelVersion) {
    try { const reason = await confirmation.confirm({ title: "Archive model", object: `${model.modelKey} v${model.version}`, impact: "Archives this model version while retaining its artifact and lineage history. The server validates model state and references; archive has no impact-preview endpoint.", reasonRequired: true }); if (!reason) return; await archiveMlModel(model.id,reason); await props.onChanged(); } catch (error) { props.setError(safeMlError(error)); }
  }
  async function reviewModel(model: MlModelVersion, action: "approve" | "reject") {
    try {
      const reason = await confirmation.confirm({ title: `${action === "approve" ? "Approve" : "Reject"} model`, object: `${model.modelKey} v${model.version}`, impact: action === "approve" ? "Make this version eligible for deployment selection. Approval does not activate a policy; server promotion gates remain required." : "Reject this model version for deployment. Existing policy and lifecycle restrictions are enforced by the server.", reasonRequired: true });
      if (!reason) return;
      await (action === "approve" ? approveMlModel(model.id, reason) : rejectMlModel(model.id, reason));
      await props.onChanged();
    } catch (error) { props.setError(safeMlError(error)); }
  }
  return (
    <section className="panel">
      {confirmation.dialog}
      <div className="section-header">
        <div>
          <h2>Model registry</h2>
        </div>
        <StatusFilterSelect label="Model status filter" value={props.statusFilter} onChange={props.onFilterChange} />
      </div>
      {props.modelsAvailable === false ? <p>Model records unavailable or loading.</p> : props.models.length === 0 ? <div className="empty-state">No registered model versions yet.</div> : props.models.map((model) => {
        const gates = model.family === "FAILURE_PREDICTION" ? approvalGateSummary(model) : null;
        const approveBlocked = Boolean(gates && !gates.promotionEligible);
        const modelLabel = `${model.modelKey} v${model.version}`;
        return (
          <div className="ml-model-row" key={model.id}>
            <div className="ml-model-body">
              <strong>{modelLabel}</strong>
              <span>{model.family} / {humanizeAlgorithm(model.algorithm)} / {model.status} / {modelOriginLabel(model)}</span>
              {model.family === "FAILURE_PREDICTION" ? <FailureModelCard gates={gates} model={model} /> : <GenericModelMetrics model={model} />}
            </div>
            <div className="action-row">
              {model.status !== "APPROVED" && model.status !== "ACTIVE" && (
                <button
                  aria-label={`Approve ${modelLabel}`}
                  className="ghost-button"
                  disabled={approveBlocked}
                  onClick={() => void reviewModel(model, "approve")}
                  title={approveBlocked ? "Promotion gates must pass before approval." : undefined}
                  type="button"
                >
                  Approve
                </button>
              )}
              {model.status !== "REJECTED" && model.status !== "ACTIVE" && <button aria-label={`Reject ${modelLabel}`} className="ghost-button" onClick={() => void reviewModel(model, "reject")} type="button">Reject</button>}
              {model.status !== "ACTIVE" && <button aria-label={`Archive ${modelLabel}`} className="ghost-button" onClick={() => void archive(model)} type="button">Archive</button>}
              {["CANDIDATE", "REJECTED"].includes(model.status) && <button aria-label={`Delete ${modelLabel}`} className="ghost-button" onClick={() => void props.onDelete(model)} type="button">Delete</button>}
            </div>
          </div>
        );
      })}
    </section>
  );
}

function FailureModelCard({ gates, model }: { gates: ApprovalGateSummary | null; model: MlModelVersion }) {
  const metrics = model.evaluationMetrics ?? {};
  const horizonDays = metricNumber(metrics, "failureHorizonDays");
  const precision = metricNumber(metrics, "failurePrecision");
  const recall = metricNumber(metrics, "failureRecall");
  const prAuc = metricNumber(metrics, "failurePrAuc");
  const prAucLift = metricNumber(metrics, "failurePrAucLift");
  const falseAlerts = metricNumber(metrics, "failureFalseAlertsPerDeviceMonth");
  const leadTime = metricNumber(metrics, "failureAverageLeadTimeDays");
  const labelCoverage = metricNumber(metrics, "labelCoverage") ?? metricNumber(metrics, "failureLabelCoverage");
  const positives = metricNumber(metrics, "failurePositiveLabels");
  const negatives = metricNumber(metrics, "failureNegativeLabels");

  return (
    <div className="ml-model-card-summary">
      <div>
        <h3>{horizonDays ? `${horizonDays}-day failure horizon` : "Failure model card"}</h3>
        <p>Predicts confirmed failure or corrective-maintenance risk for operator review. Use alongside rule alerts and maintenance context.</p>
      </div>
      <dl className="ml-model-metric-grid">
        <div><dt>Precision</dt><dd>{formatPercentMetric(precision)}</dd></div>
        <div><dt>Recall</dt><dd>{formatPercentMetric(recall)}</dd></div>
        <div><dt>PR-AUC</dt><dd>{formatDecimalMetric(prAuc)}</dd></div>
        <div><dt>PR-AUC lift</dt><dd>{formatSignedMetric(prAucLift)}</dd></div>
        <div><dt>False alerts</dt><dd>{falseAlerts === null ? "N/A" : `${formatDecimalMetric(falseAlerts)} / device-month`}</dd></div>
        <div><dt>Lead time</dt><dd>{leadTime === null ? "N/A" : `${formatDecimalMetric(leadTime)} days`}</dd></div>
      </dl>
      <dl className="ml-model-lineage">
        <div><dt>Labels</dt><dd>{positives === null ? "N/A" : `${positives} positive${negatives === null ? "" : ` / ${negatives} negative`}`}</dd></div>
        <div><dt>Coverage</dt><dd>{formatPercentMetric(labelCoverage)}</dd></div>
        <div><dt>Dataset</dt><dd className="mono-value">{model.datasetVersionId ?? "Unavailable"}</dd></div>
        <div><dt>Feature hash</dt><dd className="mono-value">{model.featureSchemaHash ?? "Unavailable"}</dd></div>
        <div><dt>Artifact</dt><dd className="mono-value">{model.artifactHash ?? model.artifactManifestUri ?? model.artifactUri ?? "Unavailable"}</dd></div>
      </dl>
      {gates && <ApprovalGatePanel gates={gates} />}
    </div>
  );
}

function ApprovalGatePanel({ gates }: { gates: ApprovalGateSummary }) {
  return (
    <section className={gates.promotionEligible ? "ml-gate-panel ml-gate-panel-pass" : "ml-gate-panel ml-gate-panel-blocked"} aria-label="Promotion gates">
      <div>
        <strong>{gates.promotionEligible ? "Promotion gates passed" : "Promotion blocked"}</strong>
        <span>
          {gates.profileId ? `${gates.profileId}${gates.profileVersion ? ` / ${gates.profileVersion}` : ""}` : gates.promotionEligible ? "This candidate is eligible for approval." : "Resolve failed gates before approval."}
          {gates.thresholdSource ? ` / ${gates.thresholdSource}` : ""}
        </span>
      </div>
      {gates.criteria.length > 0 && (
        <dl className="ml-gate-list">
          {gates.criteria.map((gate) => (
            <div key={gate.name}>
              <dt>{humanizeFeatureName(gate.name)} / {gate.passed ? "Pass" : "Fail"}</dt>
              <dd>{gate.reason} Observed {formatGateValue(gate)} / required {formatGateThreshold(gate)}.</dd>
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
        <div key={key}><dt>{humanizeKey(key)}</dt><dd>{formatQualityValue(value)}</dd></div>
      ))}
    </dl>
  );
}

function PoliciesTab(props: {
  devices: Device[];
  deviceTypes: DeviceType[];
  models: MlModelVersion[];
  policies: MlDeploymentPolicy[];
  policiesAvailable?: boolean;
  statusFilter: MlLifecycleStatusFilter;
  onChanged: () => Promise<void>;
  onFilterChange: (value: MlLifecycleStatusFilter) => Promise<void>;
  onLifecycle: (policy: MlDeploymentPolicy, action: "disable" | "delete") => Promise<void>;
  setError: (value: string | null) => void;
}) {
  const deployableModels = props.models.filter((model) => (model.status === "APPROVED" || model.status === "ACTIVE") && !model.demoModel);
  const [scope, setScope] = useState<MlDeploymentScope>("ACCOUNT");
  const [mode, setMode] = useState<MlDeploymentMode>("SHADOW");
  const [modelVersionId, setModelVersionId] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [deviceType, setDeviceType] = useState("");
  const [canaryPercent, setCanaryPercent] = useState(10);
  const [fallbackEnabled, setFallbackEnabled] = useState(true);
  const [resolved, setResolved] = useState<string | null>(null);
  const confirmation = useActionConfirmation();
  const [saving, setSaving] = useState(false);
  const validPolicy = (mode === "DISABLED" || deployableModels.some((model) => model.id === modelVersionId)) && (scope !== "DEVICE" || Boolean(deviceId)) && (scope !== "DEVICE_TYPE" || Boolean(deviceType)) && (mode !== "CANARY" || (Number.isFinite(canaryPercent) && canaryPercent >= 0 && canaryPercent <= 100));

  useEffect(() => {
    if (!modelVersionId && deployableModels[0]) {
      setModelVersionId(deployableModels[0].id);
    }
  }, [deployableModels, modelVersionId]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!validPolicy || saving) return;
    const reason = await confirmation.confirm({ title: "Apply deployment policy", object: `${scope === "DEVICE" ? props.devices.find((device) => device.id === deviceId)?.name ?? deviceId : scope === "DEVICE_TYPE" ? deviceType : "Account defaults"} · ${mode}`, impact: `${mode === "SHADOW" ? "Evaluates the model separately from the primary serving path." : mode === "CANARY" ? `Uses the model for ${canaryPercent}% of the configured canary cohort.` : mode === "ACTIVE" ? "Uses this approved model for eligible predictions." : "Disables the selected deployment scope."} Model: ${mode === "DISABLED" ? "none" : deployableModels.find((model) => model.id === modelVersionId)?.modelKey ?? modelVersionId}. Baseline fallback ${fallbackEnabled ? "enabled" : "disabled"}. More specific device policies take precedence over type and account settings.`, reasonRequired: true });
    if (reason === null) return;
    setSaving(true);
    try {
      await saveDeploymentPolicy({
        scope,
        deviceId: scope === "DEVICE" ? deviceId || null : null,
        deviceType: scope === "DEVICE_TYPE" ? deviceType || null : null,
        modelVersionId: mode === "DISABLED" ? null : modelVersionId || null,
        mode,
        canaryPercent: mode === "CANARY" ? canaryPercent : null,
        fallbackEnabled,
        reason,
      });
      await props.onChanged();
    } catch (error) {
      props.setError(safeMlError(error));
    } finally { setSaving(false); }
  }

  async function resolve() {
    try {
      const policy = await resolveDeploymentPolicy(deviceId, deviceType);
      setResolved(`${policy.policySource ?? "BASELINE"} / ${policy.mode} / ${policy.fallbackReason ?? "model selected"}`);
    } catch (error) {
      props.setError(safeMlError(error));
    }
  }

  return (
    <div className="ml-workflow-grid">
      {confirmation.dialog}
      <form className="panel ml-workflow-form" onSubmit={submit}>
        <h2>Deployment policy editor</h2>
        <div className="ml-control-grid">
          <label>Scope<select value={scope} onChange={(e) => setScope(e.target.value as MlDeploymentScope)}><option value="ACCOUNT">Account</option><option value="DEVICE_TYPE">Device type</option><option value="DEVICE">Device</option></select></label>
          <label>Mode<select value={mode} onChange={(e) => setMode(e.target.value as MlDeploymentMode)}><option value="SHADOW">Shadow</option><option value="CANARY">Canary</option><option value="ACTIVE">Active</option><option value="DISABLED">Disabled</option></select></label>
          <label>Model<select value={modelVersionId} onChange={(e) => setModelVersionId(e.target.value)}><option value="">No model</option>{deployableModels.map((model) => <option key={model.id} value={model.id}>{model.modelKey} v{model.version} / {modelOriginLabel(model)}</option>)}</select></label>
          {scope === "DEVICE" && (
            <label>
              Device
              <select value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
                <option value="">Select device</option>
                {props.devices.map((device) => <option key={device.id} value={device.id}>{deviceLabel(device)}</option>)}
              </select>
            </label>
          )}
          {scope === "DEVICE_TYPE" && (
            <label>
              Device type
              <select value={deviceType} onChange={(e) => setDeviceType(e.target.value)}>
                <option value="">Select device type</option>
                {props.deviceTypes.map((type) => <option key={type.code} value={type.code}>{type.displayName} / {type.code}</option>)}
              </select>
            </label>
          )}
          {mode === "CANARY" && <label>Canary percent<input max="100" min="0" onChange={(e) => setCanaryPercent(Number(e.target.value))} type="number" value={canaryPercent} /></label>}
        </div>
        <label className="checkbox-label"><input checked={fallbackEnabled} onChange={(e) => setFallbackEnabled(e.target.checked)} type="checkbox" /> Baseline fallback enabled</label>
        <p className="status-note">Shadow records comparison evidence; Canary serves a percentage; Active serves the approved model. Fallback is a configured alternative, not a measured success rate. Select a device and resolve its effective policy to inspect inheritance before applying changes.</p>
        <div className="action-row action-row-start"><button className="primary-button" disabled={!validPolicy || saving}>Save policy</button><button className="ghost-button" onClick={() => void resolve()} type="button">Resolve policy</button></div>
        {resolved && <div className="success-message">{resolved}</div>}
      </form>
      <section className="panel">
        <div className="section-header">
          <div>
            <h2>Current policies</h2>
          </div>
          <StatusFilterSelect label="Policy status filter" value={props.statusFilter} onChange={props.onFilterChange} />
        </div>
        {props.policiesAvailable === false ? <p>Policy records unavailable or loading.</p> : props.policies.length === 0 ? <div className="empty-state">No deployment policies yet.</div> : props.policies.map((policy) => (
          <div className="ml-readout" key={policy.id}>
            <strong>{policy.scope} / {policy.mode}</strong>
            <span>{policy.modelVersionId ?? "baseline fallback"} / fallback {policy.fallbackEnabled ? "on" : "off"}</span>
            <div className="action-row action-row-start">
              {policy.mode !== "DISABLED" && <button className="ghost-button" onClick={() => void props.onLifecycle(policy, "disable")} type="button">Disable</button>}
              {policy.mode === "DISABLED" && <button className="ghost-button" onClick={() => void props.onLifecycle(policy, "delete")} type="button">Delete</button>}
            </div>
          </div>
        ))}
      </section>
    </div>
  );
}

function MonitoringTab(props: {
  dataAvailable?: { datasets: boolean; models: boolean; policies: boolean; trainingRuns: boolean };
  datasets: MlDatasetSummary[];
  deviceId: string;
  models: MlModelVersion[];
  policies: MlDeploymentPolicy[];
  prediction: PredictionResponse | null;
  statusError: string | null;
  trainingRuns: MlTrainingRun[];
}) {
  const stats = useMemo(() => {
    const failedRuns = props.trainingRuns.filter((run) => run.status === "FAILED").length;
    const fallbackPolicies = props.policies.filter((policy) => policy.fallbackEnabled).length;
    const readyDatasets = props.datasets.filter((dataset) => dataset.status === "READY").length;
    const activeModels = props.models.filter((model) => model.status === "ACTIVE").length;
    const fallbackRate = props.policies.length === 0 ? null : Math.round((fallbackPolicies / props.policies.length) * 100);
    const errorRate = props.trainingRuns.length === 0 ? null : Math.round((failedRuns / props.trainingRuns.length) * 100);
    const predictionQuality = props.prediction && Number.isFinite(props.prediction.confidence) && !/DISABLED|INSUFFICIENT|ERROR|UNAVAILABLE|NO_DATA/i.test(props.prediction.predictionStatus) ? Math.round(props.prediction.confidence * 100) : null;
    const latencyStatus = "No latency measurement is exposed by this API.";
    const driftStatus = activeModels > 0 && readyDatasets > 0 ? "Watch feature hash changes" : "Insufficient model baseline";
    const deploymentPath = props.prediction?.predictionPath ?? props.prediction?.deploymentMode ?? "N/A";
    const canaryState = props.prediction?.canaryIncluded === true
      ? "Included"
      : props.prediction?.canaryIncluded === false
        ? "Excluded"
        : "N/A";
    return { activeModels, canaryState, deploymentPath, driftStatus, errorRate, failedRuns, fallbackPolicies, fallbackRate, latencyStatus, predictionQuality, readyDatasets };
  }, [props.datasets, props.models, props.policies, props.prediction, props.trainingRuns]);

  return (
    <div className="ml-workflow-grid">
      <section className="panel">
        <h2>ML operations snapshot</h2>
        <div className="ml-stat-grid">
          <article><span>Ready datasets (loaded)</span><strong>{props.dataAvailable?.datasets === false ? "Unavailable" : stats.readyDatasets}</strong></article>
          <article><span>Active models (loaded)</span><strong>{props.dataAvailable?.models === false ? "Unavailable" : stats.activeModels}</strong></article>
          <article><span>Fallback-enabled policies</span><strong>{props.dataAvailable?.policies === false || stats.fallbackRate === null ? "Unavailable" : `${stats.fallbackPolicies} / ${props.policies.length} (${stats.fallbackRate}%)`}</strong><small>Configuration share in loaded scopes; runtime fallback frequency unavailable.</small></article>
          <article><span>Failed training runs</span><strong>{props.dataAvailable?.trainingRuns === false || stats.errorRate === null ? "Unavailable" : `${stats.failedRuns} / ${props.trainingRuns.length} (${stats.errorRate}%)`}</strong><small>Loaded lifecycle records, all returned dates.</small></article>
          <article><span>Prediction confidence</span><strong>{stats.predictionQuality === null ? "Unavailable" : `${stats.predictionQuality}%`}</strong><small>{props.prediction?.createdAt ?? "No prediction observation"}</small></article>
          <article><span>Inference latency</span><strong>Unavailable</strong></article>
          <article><span>Deployment path</span><strong>{stats.deploymentPath}</strong></article>
          <article><span>Canary</span><strong>{stats.canaryState}</strong></article>
        </div>
        <div className="ml-readout"><strong>Drift</strong><span>{stats.driftStatus}</span></div>
        <div className="ml-readout"><strong>Latency</strong><span>{stats.latencyStatus}</span></div>
        <div className="ml-readout"><strong>Fallback policies</strong><span>{props.dataAvailable?.policies === false ? "Unavailable policy evidence" : `${stats.fallbackPolicies} policy scopes have baseline fallback enabled`}</span></div>
        <div className="ml-readout"><strong>Training errors</strong><span>{props.dataAvailable?.trainingRuns === false ? "Unavailable training evidence" : `${stats.failedRuns} failed runs in the current list`}</span></div>
        <h3>Deployment comparison</h3>
        <dl className="ml-model-metric-grid">
          <div><dt>Active</dt><dd>{props.prediction?.predictionPath === "ACTIVE" ? statusText(props.prediction.predictionStatus) : "No active path in latest trace"}</dd></div>
          <div><dt>Shadow</dt><dd>{props.prediction?.shadowPrediction ? statusText(props.prediction.predictionStatus) : "Stored separately from latest active"}</dd></div>
          <div><dt>Canary</dt><dd>{props.prediction?.deploymentMode === "CANARY" ? formatCanaryState(props.prediction) : "No canary policy in latest trace"}</dd></div>
          <div><dt>Fallback</dt><dd>{props.prediction ? props.prediction.predictionPath === "FALLBACK" ? props.prediction.fallbackReason || "Fallback used" : "Not used in this trace" : "No prediction trace"}</dd></div>
        </dl>
      </section>
      <MlStatus deviceMode={Boolean(props.deviceId)} error={props.statusError} prediction={props.prediction} />
    </div>
  );
}

function MlStatus({ prediction, error, deviceMode }: { prediction: PredictionResponse | null; error: string | null; deviceMode: boolean }) {
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

function statusText(v: string) {
  return ({ UNAVAILABLE: "ML unavailable; rule analytics continue", INSUFFICIENT_DATA: "More telemetry required", SUPPRESSED_MAINTENANCE: "Suppressed during maintenance" } as Record<string, string>)[v] ?? v;
}

function formatCanaryState(prediction: PredictionResponse) {
  const percent = prediction.canaryPercent === null || prediction.canaryPercent === undefined
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

function modelOriginLabel(model: MlModelVersion) {
  if (model.demoModel || model.modelOrigin === "DEMO") {
    return "Demo";
  }
  if (model.systemHeuristic || model.modelOrigin === "SYSTEM_HEURISTIC") {
    return "System heuristic";
  }
  return "Trained";
}

type TrainingTemplate = {
  modelFamily: "ANOMALY_DETECTION" | "FAILURE_PREDICTION";
  title: string;
  badge: string;
  description: string;
  defaultAlgorithm: string;
  algorithms: string[];
  checklist: string[];
};

type RiskDriverView = {
  feature: string;
  value: number | null;
  weight: number | null;
  contribution: number;
  direction: string;
};

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

const trainingTemplates: Record<TrainingTemplate["modelFamily"], TrainingTemplate> = {
  ANOMALY_DETECTION: {
    modelFamily: "ANOMALY_DETECTION",
    title: "Anomaly detection",
    badge: "Operational baseline",
    description: "Find unusual telemetry patterns and keep baseline fallback available.",
    defaultAlgorithm: "baseline-statistical-anomaly-v1",
    algorithms: ["baseline-statistical-anomaly-v1"],
    checklist: [
      "Use a READY dataset with normalized telemetry.",
      "Generate an anomaly feature snapshot for the selected dataset.",
      "Approve only after artifact load and model-only smoke inference pass.",
    ],
  },
  FAILURE_PREDICTION: {
    modelFamily: "FAILURE_PREDICTION",
    title: "Failure prediction",
    badge: "Outcome supervised",
    description: "Estimate failure or corrective-maintenance risk for a fixed future horizon.",
    defaultAlgorithm: "sklearn-logistic-regression-failure-v1",
    algorithms: ["sklearn-logistic-regression-failure-v1", "sklearn-random-forest-failure-v1"],
    checklist: [
      "Generate horizon labels for the same dataset and horizon.",
      "Confirm enough positive outcomes and label coverage before training.",
      "Review the model card and approval gates before promotion.",
    ],
  },
};

function humanizeAlgorithm(value: string) {
  return value
    .replace(/-v\d+$/i, "")
    .split("-")
    .map((part) => part === "sklearn" ? "sklearn" : part.replace(/^./, (match) => match.toUpperCase()))
    .join(" ");
}

const defaultMapping = JSON.stringify({
  timestampColumn: "ts",
  deviceKeyColumn: "device",
  metricNameColumn: "metric",
  metricValueColumn: "value",
  unitColumn: "unit",
  labelColumn: "label",
  existingDeviceIdColumn: "existingDeviceId",
  deviceTypeColumn: "deviceType",
  metadataColumns: ["firmware", "batch"],
  mappingVersion: "ui-mapping-v1",
  allowPartialAcceptance: true,
}, null, 2);

const labelOptions: MlLabelType[] = [
  "NORMAL",
  "ANOMALY",
  "DEGRADATION",
  "MAINTENANCE",
  "CONFIRMED_FAILURE",
  "CORRECTIVE_MAINTENANCE",
  "PREVENTIVE_MAINTENANCE",
  "INSPECTION_NORMAL",
  "FALSE_POSITIVE",
  "SENSOR_FAULT",
  "UNRESOLVED_ANOMALY",
  "UNKNOWN",
];

function deviceLabel(device: Device) {
  return `${device.name} / ${device.type} / ${device.status}`;
}

function snapshotLabel(snapshot: MlFeatureSnapshotResponse) {
  const family = snapshot.modelFamily ?? "ANOMALY_DETECTION";
  const horizon = snapshot.horizonDays ? ` / h${snapshot.horizonDays}` : "";
  return `${family}${horizon} / ${snapshot.rowCount} rows / ${snapshot.featureSchemaHash} / ${snapshot.id}`;
}

function defaultFeatureFamilies(modelFamily: "ANOMALY_DETECTION" | "FAILURE_PREDICTION") {
  if (modelFamily === "FAILURE_PREDICTION") {
    return ["rolling_stats", "baseline_deviation", "missing_data", "time", "device_context", "label"];
  }
  return ["rolling_stats", "baseline_deviation", "missing_data", "time", "label"];
}

function toInstant(value: string) {
  return new Date(value).toISOString();
}

function splitList(value: string) {
  return value
    .split(/[\n,]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function humanizeKey(value: string) {
  const text = value.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  return text.replace(/^./, (match) => match.toUpperCase());
}

function formatQualityValue(value: unknown) {
  if (value === null || value === undefined) {
    return "N/A";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

function metricNumber(metrics: Record<string, unknown>, key: string) {
  const value = metrics[key];
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function formatPercentMetric(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  return `${Math.round(value * 100)}%`;
}

function formatDecimalMetric(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  return value.toLocaleString(undefined, { maximumFractionDigits: 2, minimumFractionDigits: value < 10 ? 1 : 0 });
}

function formatSignedMetric(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  const sign = value > 0 ? "+" : "";
  return `${sign}${formatDecimalMetric(value)}`;
}

function approvalGateSummary(model: MlModelVersion): ApprovalGateSummary {
  const explicit = explicitApprovalGateSummary(model.evaluationMetrics ?? {});
  if (explicit) {
    return explicit;
  }

  const metrics = model.evaluationMetrics ?? {};
  const positiveLabels = metricNumber(metrics, "failurePositiveLabels");
  const minimumPositiveLabels = metricNumber(metrics, "minimumPositiveLabels") ?? metricNumber(metrics, "failureMinimumPositiveLabels") ?? 1;
  const labelCoverage = metricNumber(metrics, "labelCoverage") ?? metricNumber(metrics, "failureLabelCoverage");
  const minimumLabelCoverage = metricNumber(metrics, "minimumLabelCoverage") ?? metricNumber(metrics, "failureMinimumLabelCoverage") ?? 0.8;
  const validationSamples = metricNumber(metrics, "failureValidationSamples");
  const hasEvaluation = validationSamples !== null ? validationSamples > 0 : metricNumber(metrics, "failurePrAuc") !== null;
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
      observed: model.artifactUri && model.artifactManifestUri && model.artifactHash && model.artifactSizeBytes ? 1 : 0,
      threshold: 1,
      passed: Boolean(model.artifactUri && model.artifactManifestUri && model.artifactHash && model.artifactSizeBytes),
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
    .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === "object" && !Array.isArray(item))
    .map((item) => ({
      name: stringMetric(item, "name") ?? "approval_gate",
      observed: metricNumber(item, "observed"),
      threshold: metricNumber(item, "threshold"),
      passed: Boolean(item.passed),
      reason: stringMetric(item, "reason") ?? "Approval gate failed.",
    }));
  const failedCriteria = normalizedCriteria.filter((item) => !item.passed);
  const promotionEligible = typeof gateObject.promotionEligible === "boolean"
    ? gateObject.promotionEligible
    : failedCriteria.length === 0;
  return {
    status: stringMetric(gateObject, "status") ?? (promotionEligible ? "PASSED" : "NEEDS_REVIEW"),
    profileId: stringMetric(gateObject, "profileId") ?? stringMetric(metrics, "approvalGateProfileId"),
    profileVersion: stringMetric(gateObject, "profileVersion") ?? stringMetric(metrics, "approvalGateProfileVersion"),
    thresholdSource: stringMetric(gateObject, "thresholdSource") ?? stringMetric(metrics, "approvalGateThresholdSource"),
    promotionEligible,
    criteria: normalizedCriteria,
    failedCriteria,
  };
}

function formatGateValue(gate: ApprovalGateCriterion) {
  if (gate.observed === null) {
    return "N/A";
  }
  if (gate.name.includes("coverage") || gate.name.includes("precision") || gate.name.includes("recall")) {
    return formatPercentMetric(gate.observed);
  }
  return formatGateNumber(gate.observed);
}

function formatGateThreshold(gate: ApprovalGateCriterion) {
  if (gate.threshold === null) {
    return "N/A";
  }
  if (gate.name.includes("coverage") || gate.name.includes("precision") || gate.name.includes("recall")) {
    return formatPercentMetric(gate.threshold);
  }
  return formatGateNumber(gate.threshold);
}

function formatGateNumber(value: number) {
  return Number.isInteger(value) ? String(value) : formatDecimalMetric(value);
}

function normalizeRiskDrivers(drivers: Array<Record<string, unknown>>) {
  return drivers
    .map((driver) => {
      const feature = stringMetric(driver, "feature") ?? stringMetric(driver, "metric") ?? "unknown_feature";
      const contribution = metricNumber(driver, "contribution") ?? metricNumber(driver, "score") ?? 0;
      return {
        feature,
        value: metricNumber(driver, "value"),
        weight: metricNumber(driver, "weight"),
        contribution,
        direction: stringMetric(driver, "direction") ?? (contribution >= 0 ? "increases_risk" : "decreases_risk"),
      };
    })
    .sort((left, right) => Math.abs(right.contribution) - Math.abs(left.contribution));
}

function stringMetric(metrics: Record<string, unknown>, key: string) {
  const value = metrics[key];
  return typeof value === "string" && value.trim() ? value : null;
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

function humanizeFeatureName(value: string) {
  return value
    .replace(/[_-]+/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part.length <= 2 ? part.toUpperCase() : part.replace(/^./, (match) => match.toUpperCase()))
    .join(" ");
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
  const start = stringMetric(value, "start") ?? stringMetric(value, "from") ?? stringMetric(value, "windowStart");
  const end = stringMetric(value, "end") ?? stringMetric(value, "to") ?? stringMetric(value, "windowEnd");
  if (start || end) {
    return `${start ?? "unknown start"} - ${end ?? "unknown end"}`;
  }
  return Object.entries(value).map(([key, item]) => `${humanizeKey(key)}: ${formatQualityValue(item)}`).join(" / ");
}

export { ModelsTab as MlModelsTab, PoliciesTab as MlPoliciesTab, MonitoringTab as MlMonitoringTab };
