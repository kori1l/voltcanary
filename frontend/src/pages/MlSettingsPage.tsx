import { FormEvent, useEffect, useMemo, useState } from "react";
import { getLatestPrediction, PredictionResponse } from "../analytics/analyticsApi";
import {
  approveMlModel,
  archiveMlModel,
  cancelTrainingRun,
  createInternalTelemetryDataset,
  correctMlLabel,
  createTrainingRun,
  createMlLabel,
  generateFeatureSnapshot,
  generateHorizonLabels,
  getMlLabelAudit,
  getMlDatasetVersion,
  importMlDataset,
  listMlLabels,
  listDeploymentPolicies,
  listMlDatasets,
  listMlModels,
  listTrainingRuns,
  MlDatasetDetail,
  MlDatasetSummary,
  MlDeploymentMode,
  MlDeploymentPolicy,
  MlDeploymentScope,
  MlFeatureSnapshotResponse,
  MlHorizonLabelGenerationResponse,
  MlLabel,
  MlLabelAudit,
  MlLabelType,
  MlModelVersion,
  MlTrainingRun,
  rejectMlModel,
  resolveDeploymentPolicy,
  retryTrainingRun,
  saveDeploymentPolicy,
} from "../analytics/mlModuleApi";
import {
  clearDeviceMlSettings,
  getAccountMlSettings,
  getDeviceMlSettings,
  MlSettingsResponse,
  MlSettingsValues,
  safeMlError,
  saveAccountMlSettings,
  saveDeviceMlSettings,
} from "../analytics/mlSettingsApi";

const initial: MlSettingsValues = {
  mlEnabled: true,
  predictionMode: "MODEL_OR_BASELINE_HEURISTIC",
  confidenceWarningThreshold: 0.6,
  maintenanceBehavior: "REDUCE_CONFIDENCE",
  explanationsVisible: true,
};

type MlTab = "settings" | "datasets" | "import" | "labels" | "training" | "models" | "policies" | "monitoring";
type FeatureSnapshotConfig = Parameters<typeof generateFeatureSnapshot>[0];

export function MlSettingsPage() {
  const [activeTab, setActiveTab] = useState<MlTab>("settings");
  const [values, setValues] = useState(initial);
  const [effective, setEffective] = useState<MlSettingsResponse | null>(null);
  const [deviceId, setDeviceId] = useState("");
  const [deviceMode, setDeviceMode] = useState(false);
  const [prediction, setPrediction] = useState<PredictionResponse | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [datasets, setDatasets] = useState<MlDatasetSummary[]>([]);
  const [datasetDetail, setDatasetDetail] = useState<MlDatasetDetail | null>(null);
  const [trainingRuns, setTrainingRuns] = useState<MlTrainingRun[]>([]);
  const [models, setModels] = useState<MlModelVersion[]>([]);
  const [policies, setPolicies] = useState<MlDeploymentPolicy[]>([]);
  const [featureSnapshot, setFeatureSnapshot] = useState<MlFeatureSnapshotResponse | null>(null);
  const [labels, setLabels] = useState<MlLabel[]>([]);
  const [labelAudit, setLabelAudit] = useState<MlLabelAudit[]>([]);

  useEffect(() => {
    void loadAccount();
    void refreshMlWorkspace();
  }, []);

  function adopt(v: MlSettingsResponse) {
    setEffective(v);
    setValues({
      mlEnabled: v.mlEnabled,
      predictionMode: v.predictionMode,
      confidenceWarningThreshold: v.confidenceWarningThreshold,
      maintenanceBehavior: v.maintenanceBehavior,
      explanationsVisible: v.explanationsVisible,
    });
  }

  async function loadAccount() {
    setBusy(true);
    setError(null);
    try {
      adopt(await getAccountMlSettings());
      setDeviceMode(false);
    } catch (e) {
      setError(safeMlError(e));
    } finally {
      setBusy(false);
    }
  }

  async function loadDevice() {
    if (!deviceId.trim()) {
      setError("Device ID is required");
      return;
    }
    setBusy(true);
    setError(null);
    setStatusError(null);
    try {
      adopt(await getDeviceMlSettings(deviceId.trim()));
      setDeviceMode(true);
      getLatestPrediction(deviceId.trim())
        .then(setPrediction)
        .catch((e) => {
          setPrediction(null);
          setStatusError(safeMlError(e));
        });
    } catch (e) {
      setError(safeMlError(e));
    } finally {
      setBusy(false);
    }
  }

  async function refreshMlWorkspace() {
    const [datasetResult, trainingResult, modelResult, policyResult] = await Promise.allSettled([
      listMlDatasets(),
      listTrainingRuns(),
      listMlModels(),
      listDeploymentPolicies(),
    ]);
    if (datasetResult.status === "fulfilled") {
      setDatasets(datasetResult.value);
    }
    if (trainingResult.status === "fulfilled") {
      setTrainingRuns(trainingResult.value);
    }
    if (modelResult.status === "fulfilled") {
      setModels(modelResult.value);
    }
    if (policyResult.status === "fulfilled") {
      setPolicies(policyResult.value);
    }
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    if (values.confidenceWarningThreshold < 0 || values.confidenceWarningThreshold > 1) {
      setError("Confidence threshold must be between 0 and 1");
      return;
    }
    if (!values.mlEnabled && values.predictionMode !== "RULE_BASED_ONLY") {
      setError("Disabled ML requires Rule based only mode");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      adopt(deviceMode ? await saveDeviceMlSettings(deviceId, values) : await saveAccountMlSettings(values));
      setMessage("ML settings saved");
    } catch (x) {
      setError(safeMlError(x));
    } finally {
      setBusy(false);
    }
  }

  async function clearOverride() {
    setBusy(true);
    try {
      await clearDeviceMlSettings(deviceId);
      adopt(await getDeviceMlSettings(deviceId));
      setMessage("Device now inherits account defaults");
    } catch (e) {
      setError(safeMlError(e));
    } finally {
      setBusy(false);
    }
  }

  async function loadDatasetDetail(datasetVersionId: string) {
    setBusy(true);
    setError(null);
    try {
      setDatasetDetail(await getMlDatasetVersion(datasetVersionId));
    } catch (e) {
      setError(safeMlError(e));
    } finally {
      setBusy(false);
    }
  }

  const set = <K extends keyof MlSettingsValues>(key: K, value: MlSettingsValues[K]) =>
    setValues((v) => ({ ...v, [key]: value }));

  return (
    <>
      <header className="page-header">
        <h1>ML Settings</h1>
        <p>Prediction policy, training assets, deployment, and read-only model readiness.</p>
      </header>
      {message && <div className="success-message">{message}</div>}
      {error && <div className="error-message">{error}</div>}
      <nav className="tab-row" aria-label="ML workflow tabs">
        {[
          ["settings", "Settings"],
          ["datasets", "Datasets"],
          ["import", "Import"],
          ["labels", "Labels"],
          ["training", "Training"],
          ["models", "Models"],
          ["policies", "Policies"],
          ["monitoring", "Monitoring"],
        ].map(([key, label]) => (
          <button
            className={`tab-button ${activeTab === key ? "tab-button-active" : ""}`}
            key={key}
            onClick={() => setActiveTab(key as MlTab)}
            type="button"
          >
            {label}
          </button>
        ))}
      </nav>

      {activeTab === "settings" && (
        <SettingsTab
          busy={busy}
          clearOverride={clearOverride}
          deviceId={deviceId}
          deviceMode={deviceMode}
          effective={effective}
          loadAccount={loadAccount}
          loadDevice={loadDevice}
          prediction={prediction}
          save={save}
          setDeviceId={setDeviceId}
          setValue={set}
          statusError={statusError}
          values={values}
        />
      )}
      {activeTab === "datasets" && (
        <DatasetsTab
          datasets={datasets}
          detail={datasetDetail}
          featureSnapshot={featureSnapshot}
          onCreateInternalDataset={async (body) => {
            setBusy(true);
            setError(null);
            try {
              const response = await createInternalTelemetryDataset(body);
              await refreshMlWorkspace();
              await loadDatasetDetail(response.datasetVersionId);
              setMessage(`Internal telemetry dataset ${response.status}: ${response.acceptedRows} accepted / ${response.rejectedRows} rejected`);
            } catch (e) {
              setError(safeMlError(e));
            } finally {
              setBusy(false);
            }
          }}
          onGenerateFeatureSnapshot={async (body) => {
            setBusy(true);
            setError(null);
            try {
              setFeatureSnapshot(await generateFeatureSnapshot(body));
              setMessage("Feature snapshot generated");
              await refreshMlWorkspace();
            } catch (e) {
              setError(safeMlError(e));
            } finally {
              setBusy(false);
            }
          }}
          onSelect={loadDatasetDetail}
        />
      )}
      {activeTab === "import" && (
        <ImportTab
          onImported={async (summary) => {
            setMessage(summary);
            await refreshMlWorkspace();
          }}
          setError={setError}
        />
      )}
      {activeTab === "labels" && (
        <LabelsTab
          audit={labelAudit}
          datasets={datasets}
          labels={labels}
          onAudit={async (labelId) => {
            setError(null);
            try {
              setLabelAudit(await getMlLabelAudit(labelId));
            } catch (e) {
              setError(safeMlError(e));
            }
          }}
          onChanged={async (datasetVersionId) => {
            setLabels(await listMlLabels(datasetVersionId));
            setMessage("Label review updated");
          }}
          setError={setError}
        />
      )}
      {activeTab === "training" && (
        <TrainingTab
          datasets={datasets}
          featureSnapshot={featureSnapshot}
          runs={trainingRuns}
          onChanged={async () => {
            await refreshMlWorkspace();
            setMessage("Training workflow updated");
          }}
          setError={setError}
        />
      )}
      {activeTab === "models" && (
        <ModelsTab
          models={models}
          onChanged={async () => {
            await refreshMlWorkspace();
            setMessage("Model registry updated");
          }}
          setError={setError}
        />
      )}
      {activeTab === "policies" && (
        <PoliciesTab
          models={models}
          policies={policies}
          onChanged={async () => {
            await refreshMlWorkspace();
            setMessage("Deployment policy saved");
          }}
          setError={setError}
        />
      )}
      {activeTab === "monitoring" && (
        <MonitoringTab
          datasets={datasets}
          deviceId={deviceId}
          models={models}
          policies={policies}
          prediction={prediction}
          statusError={statusError}
          trainingRuns={trainingRuns}
        />
      )}
    </>
  );
}

function SettingsTab(props: {
  busy: boolean;
  clearOverride: () => Promise<void>;
  deviceId: string;
  deviceMode: boolean;
  effective: MlSettingsResponse | null;
  loadAccount: () => Promise<void>;
  loadDevice: () => Promise<void>;
  prediction: PredictionResponse | null;
  save: (e: FormEvent) => Promise<void>;
  setDeviceId: (value: string) => void;
  setValue: <K extends keyof MlSettingsValues>(key: K, value: MlSettingsValues[K]) => void;
  statusError: string | null;
  values: MlSettingsValues;
}) {
  return (
    <>
      <section className="panel ml-scope">
        <div className="section-header">
          <div>
            <h2>Scope</h2>
            <p>Account defaults or a per-device override.</p>
          </div>
          <span className="status-badge">{props.effective?.source ?? "LOADING"}</span>
        </div>
        <div className="action-row action-row-start">
          <button className="ghost-button" onClick={() => void props.loadAccount()} type="button">Account defaults</button>
          <input aria-label="Device ID" placeholder="Device UUID" value={props.deviceId} onChange={(e) => props.setDeviceId(e.target.value)} />
          <button className="ghost-button" onClick={() => void props.loadDevice()} type="button">Load device</button>
        </div>
      </section>
      <div className="settings-grid">
        <form className="panel ml-settings-form" onSubmit={props.save}>
          <div className="section-header">
            <div>
              <h2>Prediction preferences</h2>
              <p>Saved server-side and applied to analytics recalculation.</p>
            </div>
          </div>
          <label>
            <input
              checked={props.values.mlEnabled}
              onChange={(e) => {
                props.setValue("mlEnabled", e.target.checked);
                if (!e.target.checked) props.setValue("predictionMode", "RULE_BASED_ONLY");
              }}
              type="checkbox"
            />{" "}
            Enable ML-assisted predictions
          </label>
          <label>
            Allowed prediction mode
            <select value={props.values.predictionMode} onChange={(e) => props.setValue("predictionMode", e.target.value as MlSettingsValues["predictionMode"])}>
              <option value="MODEL_ONLY">Trained model only</option>
              <option value="MODEL_OR_BASELINE_HEURISTIC">Model or baseline heuristic</option>
              <option value="RULE_BASED_ONLY">Rule-based fallback only</option>
            </select>
            <small>Controls whether trained model, baseline heuristic, or only deterministic rules may be used.</small>
          </label>
          <label>
            Confidence warning threshold
            <input
              max="1"
              min="0"
              onChange={(e) => props.setValue("confidenceWarningThreshold", Number(e.target.value))}
              step="0.01"
              type="number"
              value={props.values.confidenceWarningThreshold}
            />
          </label>
          <label>
            Maintenance exclusions
            <select value={props.values.maintenanceBehavior} onChange={(e) => props.setValue("maintenanceBehavior", e.target.value as MlSettingsValues["maintenanceBehavior"])}>
              <option value="SUPPRESS_ML">Suppress ML calls</option>
              <option value="REDUCE_CONFIDENCE">Reduce confidence</option>
            </select>
            <small>Controls whether excluded maintenance data prevents inference or lowers confidence.</small>
          </label>
          <label>
            <input checked={props.values.explanationsVisible} onChange={(e) => props.setValue("explanationsVisible", e.target.checked)} type="checkbox" /> Show prediction explanations
          </label>
          <div className="action-row action-row-start">
            <button className="primary-button" disabled={props.busy}>Save settings</button>
            {props.deviceMode && props.effective?.source === "DEVICE_OVERRIDE" && (
              <button className="ghost-button" onClick={() => void props.clearOverride()} type="button">Clear override</button>
            )}
          </div>
        </form>
        <MlStatus deviceMode={props.deviceMode} error={props.statusError} prediction={props.prediction} />
      </div>
    </>
  );
}

function DatasetsTab(props: {
  datasets: MlDatasetSummary[];
  detail: MlDatasetDetail | null;
  featureSnapshot: MlFeatureSnapshotResponse | null;
  onCreateInternalDataset: (body: Parameters<typeof createInternalTelemetryDataset>[0]) => Promise<void>;
  onGenerateFeatureSnapshot: (body: FeatureSnapshotConfig) => Promise<void>;
  onSelect: (datasetVersionId: string) => Promise<void>;
}) {
  const [featureTemplateId, setFeatureTemplateId] = useState("generic-appliance");
  const [featureTemplateVersion, setFeatureTemplateVersion] = useState("template-v1");
  const [rollingWindowSize, setRollingWindowSize] = useState(6);
  const [missingGapSeconds, setMissingGapSeconds] = useState(21600);
  const [featureFamiliesText, setFeatureFamiliesText] = useState("rolling_stats\nbaseline_deviation\nmissing_data\ntime\nlabel");
  const selectedDatasetVersionId = props.detail?.dataset.datasetVersionId ?? "";

  function generateSnapshot() {
    if (!selectedDatasetVersionId) return;
    void props.onGenerateFeatureSnapshot({
      datasetVersionId: selectedDatasetVersionId,
      featureSetVersion: "feature-set-v1",
      codeConfigVersion: "analytics-feature-pipeline-v1",
      rollingWindowSize,
      missingGapSeconds,
      enabledFeatureFamilies: splitList(featureFamiliesText),
      modelFamily: "ANOMALY_DETECTION",
      horizonDays: null,
      featureTemplateId,
      featureTemplateVersion,
    });
  }

  return (
    <div className="ml-workflow-grid">
      <div className="ml-panel-stack">
        <InternalTelemetryDatasetForm onCreate={props.onCreateInternalDataset} />
        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Dataset versions</h2>
              <p>Validation status, lineage, and feature preparation.</p>
            </div>
          </div>
          {props.datasets.length === 0 ? <div className="empty-state">No datasets have been created or imported yet.</div> : (
            <div className="ml-table">
              {props.datasets.map((dataset) => (
                <button className="ml-table-row" key={dataset.datasetVersionId} onClick={() => void props.onSelect(dataset.datasetVersionId)} type="button">
                  <strong>{dataset.name}</strong>
                  <span>{dataset.sourceType}</span>
                  <span>{dataset.status}</span>
                  <span>v{dataset.version}</span>
                </button>
              ))}
            </div>
          )}
        </section>
      </div>
      <section className="panel">
        <div className="section-header">
          <div>
            <h2>Dataset detail</h2>
            <p>Validation reports and deterministic lineage.</p>
          </div>
        </div>
        {!props.detail ? <div className="empty-state">Select a dataset version to inspect validation and lineage.</div> : (
          <>
            <dl className="detail-list">
              <div><dt>Status</dt><dd>{props.detail.dataset.status}</dd></div>
              <div><dt>Version id</dt><dd className="mono-value">{props.detail.dataset.datasetVersionId}</dd></div>
              <div><dt>Split</dt><dd>{props.detail.dataset.splitStrategy}</dd></div>
              <div><dt>Feature config</dt><dd>{props.detail.dataset.featureConfigVersion}</dd></div>
              <div><dt>Artifact URI</dt><dd className="mono-value">{props.detail.dataset.artifactUri ?? "No external artifact"}</dd></div>
              <div><dt>Artifact hash</dt><dd className="mono-value">{props.detail.dataset.artifactHash ?? props.detail.dataset.dataHash ?? "Unavailable"}</dd></div>
              <div><dt>Immutable rows</dt><dd className="mono-value">{props.detail.dataset.immutableRowReference ?? "Unavailable"}</dd></div>
              <div><dt>Failure reason</dt><dd>{props.detail.dataset.failureReason ?? "None"}</dd></div>
            </dl>
            <h3>Validation</h3>
            {props.detail.validationReports.map((report) => (
              <div className="ml-readout" key={report.id}>
                <strong>{report.summary}</strong>
                <span>{report.acceptedRows} accepted / {report.rejectedRows} rejected / {report.warningCount} warnings</span>
                {Object.keys(report.rejectedReasons ?? {}).length > 0 && (
                  <dl className="ml-quality-grid">
                    {Object.entries(report.rejectedReasons).map(([reason, count]) => (
                      <div key={reason}>
                        <dt>{humanizeKey(reason)}</dt>
                        <dd>{count} rejected rows</dd>
                      </div>
                    ))}
                  </dl>
                )}
                {Object.keys(report.qualityProfile ?? {}).length > 0 && (
                  <dl className="ml-quality-grid">
                    {Object.entries(report.qualityProfile).map(([key, value]) => (
                      <div key={key}>
                        <dt>{humanizeKey(key)}</dt>
                        <dd>{formatQualityValue(value)}</dd>
                      </div>
                    ))}
                  </dl>
                )}
              </div>
            ))}
            <h3>Lineage</h3>
            {props.detail.lineage.map((lineage) => (
              <div className="ml-readout" key={lineage.id}>
                <strong>{lineage.sourceReference}</strong>
                <span className="mono-value">{lineage.contentHash}</span>
              </div>
            ))}
            <section className="ml-inline-panel">
              <h3>Feature snapshot</h3>
              <div className="ml-form-grid">
                <label>Feature template<select value={featureTemplateId} onChange={(event) => setFeatureTemplateId(event.target.value)}><option value="generic-appliance">Generic appliance</option><option value="fridge-freezer">Fridge/freezer</option></select></label>
                <label>Template version<input value={featureTemplateVersion} onChange={(event) => setFeatureTemplateVersion(event.target.value)} /></label>
                <label>Rolling window<input min="1" type="number" value={rollingWindowSize} onChange={(event) => setRollingWindowSize(Number(event.target.value))} /></label>
                <label>Max gap seconds<input min="1" type="number" value={missingGapSeconds} onChange={(event) => setMissingGapSeconds(Number(event.target.value))} /></label>
              </div>
              <label>Feature families<textarea className="ml-compact-editor" value={featureFamiliesText} onChange={(event) => setFeatureFamiliesText(event.target.value)} /></label>
              <div className="action-row action-row-start">
                <button className="primary-button" onClick={generateSnapshot} type="button">Generate feature snapshot</button>
              </div>
              {props.featureSnapshot && <FeatureSnapshotSummary snapshot={props.featureSnapshot} />}
            </section>
          </>
        )}
      </section>
    </div>
  );
}

function FeatureSnapshotSummary({ snapshot }: { snapshot: MlFeatureSnapshotResponse }) {
  return (
    <div className={snapshot.rejectedRowCount > 0 ? "warning-message" : "success-message"}>
      <dl className="detail-list">
        <div><dt>Snapshot id</dt><dd className="mono-value">{snapshot.id}</dd></div>
        <div><dt>Rows</dt><dd>{snapshot.rowCount} ready / {snapshot.rejectedRowCount} rejected</dd></div>
        <div><dt>Feature schema hash</dt><dd className="mono-value">{snapshot.featureSchemaHash}</dd></div>
        <div><dt>Feature data hash</dt><dd className="mono-value">{snapshot.featureDataHash ?? "Pending"}</dd></div>
        <div><dt>Feature config hash</dt><dd className="mono-value">{snapshot.featureConfigHash ?? "Pending"}</dd></div>
        <div><dt>Artifact URI</dt><dd className="mono-value">{snapshot.artifactUri}</dd></div>
        <div><dt>Immutable rows</dt><dd className="mono-value">{snapshot.immutableRowReference ?? "Unavailable"}</dd></div>
        <div><dt>Template</dt><dd>{snapshot.featureTemplateId ?? "generic-appliance"} / {snapshot.featureTemplateVersion ?? "template-v1"}</dd></div>
      </dl>
    </div>
  );
}

function summarizeLabels(labels: MlLabel[]) {
  return labels.reduce(
    (summary, item) => {
      if (item.label === "CONFIRMED_FAILURE" || item.label === "CORRECTIVE_MAINTENANCE") {
        summary.positive += 1;
      }
      if (item.source === "MAINTENANCE_OUTCOME") {
        summary.outcome += 1;
      }
      if (item.label === "UNKNOWN" || item.label === "UNRESOLVED_ANOMALY") {
        summary.unresolved += 1;
      }
      if (item.label === "FALSE_POSITIVE") {
        summary.falsePositive += 1;
      }
      return summary;
    },
    { positive: 0, outcome: 0, unresolved: 0, falsePositive: 0 },
  );
}

function InternalTelemetryDatasetForm(props: { onCreate: (body: Parameters<typeof createInternalTelemetryDataset>[0]) => Promise<void> }) {
  const [name, setName] = useState("Internal telemetry training set");
  const [description, setDescription] = useState("");
  const [deviceIdsText, setDeviceIdsText] = useState("");
  const [metricsText, setMetricsText] = useState("activePowerW\nvoltageV\ncurrentA");
  const [from, setFrom] = useState("2026-07-01T00:00");
  const [to, setTo] = useState("2026-07-02T00:00");
  const [splitStrategy, setSplitStrategy] = useState<"TIME_BASED" | "DEVICE_BASED" | "RANDOM">("TIME_BASED");
  const [featureConfigVersion, setFeatureConfigVersion] = useState("feature-config-v1");
  const [minSamplesPerMetric, setMinSamplesPerMetric] = useState(24);
  const [maxSamplingGapSeconds, setMaxSamplingGapSeconds] = useState(21600);
  const [localError, setLocalError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const deviceIds = splitList(deviceIdsText);
    const metrics = splitList(metricsText);
    if (!name.trim()) {
      setLocalError("Dataset name is required");
      return;
    }
    if (deviceIds.length === 0) {
      setLocalError("At least one device ID is required");
      return;
    }
    if (metrics.length === 0) {
      setLocalError("At least one metric is required");
      return;
    }
    setLocalError(null);
    await props.onCreate({
      name: name.trim(),
      description: description.trim() || null,
      deviceIds,
      deviceMetadata: {},
      metrics,
      from: toInstant(from),
      to: toInstant(to),
      splitStrategy,
      splitConfigJson: "{}",
      featureConfigVersion: featureConfigVersion.trim() || "feature-config-v1",
      minSamplesPerMetric,
      maxSamplingGapSeconds,
    });
  }

  return (
    <form className="panel ml-settings-form" onSubmit={submit}>
      <div className="section-header">
        <div>
          <h2>Create internal dataset</h2>
          <p>Freeze selected telemetry rows into a dataset version.</p>
        </div>
      </div>
      {localError && <div className="error-message">{localError}</div>}
      <label>Dataset name<input value={name} onChange={(event) => setName(event.target.value)} required /></label>
      <label>Description<input value={description} onChange={(event) => setDescription(event.target.value)} /></label>
      <label>Device IDs<textarea className="ml-compact-editor" value={deviceIdsText} onChange={(event) => setDeviceIdsText(event.target.value)} placeholder="One UUID per line or comma separated" required /></label>
      <label>Metrics<textarea className="ml-compact-editor" value={metricsText} onChange={(event) => setMetricsText(event.target.value)} required /></label>
      <div className="ml-form-grid">
        <label>From<input type="datetime-local" value={from} onChange={(event) => setFrom(event.target.value)} required /></label>
        <label>To<input type="datetime-local" value={to} onChange={(event) => setTo(event.target.value)} required /></label>
        <label>Split<select value={splitStrategy} onChange={(event) => setSplitStrategy(event.target.value as typeof splitStrategy)}><option value="TIME_BASED">Time based</option><option value="DEVICE_BASED">Device based</option><option value="RANDOM">Random</option></select></label>
        <label>Feature config<input value={featureConfigVersion} onChange={(event) => setFeatureConfigVersion(event.target.value)} /></label>
        <label>Min samples<input min="1" type="number" value={minSamplesPerMetric} onChange={(event) => setMinSamplesPerMetric(Number(event.target.value))} /></label>
        <label>Max gap seconds<input min="1" type="number" value={maxSamplingGapSeconds} onChange={(event) => setMaxSamplingGapSeconds(Number(event.target.value))} /></label>
      </div>
      <div className="action-row action-row-start">
        <button className="primary-button">Create dataset version</button>
      </div>
    </form>
  );
}

function ImportTab({ onImported, setError }: { onImported: (summary: string) => Promise<void>; setError: (value: string | null) => void }) {
  const [kind, setKind] = useState<"csv" | "jsonl">("csv");
  const [datasetName, setDatasetName] = useState("");
  const [description, setDescription] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [mappingText, setMappingText] = useState(defaultMapping);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!file) {
      setError("Select a CSV or JSONL file");
      return;
    }
    try {
      const mapping = JSON.parse(mappingText) as Record<string, unknown>;
      const response = await importMlDataset(kind, file, mapping, datasetName, description);
      await onImported(`Imported ${response.acceptedRows} rows; ${response.rejectedRows} rejected`);
    } catch (error) {
      setError(safeMlError(error));
    }
  }

  return (
    <form className="panel ml-import-form" onSubmit={submit}>
      <div className="section-header">
        <div>
          <h2>External dataset import</h2>
          <p>Map historical files into training rows without writing telemetry.</p>
        </div>
      </div>
      <div className="ml-form-grid">
        <label>File type<select value={kind} onChange={(e) => setKind(e.target.value as "csv" | "jsonl")}><option value="csv">CSV</option><option value="jsonl">JSONL</option></select></label>
        <label>Dataset name<input value={datasetName} onChange={(e) => setDatasetName(e.target.value)} placeholder="Washer historical import" /></label>
        <label>Description<input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Optional notes" /></label>
        <label>File<input aria-label="Dataset file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} type="file" /></label>
      </div>
      <label>
        Schema mapping JSON
        <textarea className="ml-json-editor" value={mappingText} onChange={(e) => setMappingText(e.target.value)} />
      </label>
      <button className="primary-button">Import dataset</button>
    </form>
  );
}

function LabelsTab(props: {
  audit: MlLabelAudit[];
  datasets: MlDatasetSummary[];
  labels: MlLabel[];
  onAudit: (labelId: string) => Promise<void>;
  onChanged: (datasetVersionId: string) => Promise<void>;
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
  const [horizonDays, setHorizonDays] = useState(30);
  const [includePreventiveMaintenance, setIncludePreventiveMaintenance] = useState(false);
  const [generationResult, setGenerationResult] = useState<MlHorizonLabelGenerationResponse | null>(null);

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
    try {
      await correctMlLabel(item.id, { label: nextLabel, reason: "Corrected during label review" });
      await props.onChanged(item.datasetVersionId);
    } catch (error) {
      props.setError(safeMlError(error));
    }
  }

  async function generateLabels() {
    if (!datasetVersionId) {
      props.setError("Select a dataset version before generating labels");
      return;
    }
    try {
      const result = await generateHorizonLabels({
        datasetVersionId,
        horizonDays,
        generationVersion: "failure-outcome-v1",
        includePreventiveMaintenance,
      });
      setGenerationResult(result);
      await props.onChanged(datasetVersionId);
    } catch (error) {
      props.setError(safeMlError(error));
    }
  }

  const coverage = summarizeLabels(props.labels);

  return (
    <div className="ml-workflow-grid">
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
          <label>Device id<input value={deviceId} onChange={(event) => setDeviceId(event.target.value)} placeholder="Optional device UUID" /></label>
          <label>External device key<input value={externalDeviceKey} onChange={(event) => setExternalDeviceKey(event.target.value)} /></label>
          <label>Metric<input value={metricName} onChange={(event) => setMetricName(event.target.value)} /></label>
          <label>Label<select value={label} onChange={(event) => setLabel(event.target.value as MlLabelType)}>{labelOptions.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
          <label>Start<input type="datetime-local" value={labelStartAt} onChange={(event) => setLabelStartAt(event.target.value)} /></label>
          <label>End<input type="datetime-local" value={labelEndAt} onChange={(event) => setLabelEndAt(event.target.value)} /></label>
        </div>
        <label>Reason<input value={reason} onChange={(event) => setReason(event.target.value)} /></label>
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
            <button className="ghost-button" onClick={() => void generateLabels()} type="button">Generate horizon labels</button>
          </div>
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
        {props.labels.length > 0 && (
          <dl className="ml-quality-grid">
            <div><dt>Label coverage</dt><dd>{props.labels.length} labels</dd></div>
            <div><dt>Positive labels</dt><dd>{coverage.positive}</dd></div>
            <div><dt>Outcome labels</dt><dd>{coverage.outcome}</dd></div>
            <div><dt>Unknown/unresolved</dt><dd>{coverage.unresolved}</dd></div>
            <div><dt>False positives</dt><dd>{coverage.falsePositive}</dd></div>
          </dl>
        )}
        {props.labels.length === 0 ? <div className="empty-state">No labels loaded for the selected dataset.</div> : props.labels.map((item) => (
          <div className="ml-model-row" key={item.id}>
            <div>
              <strong>{item.label} / {item.metricName ?? "metric"}</strong>
              <span>{item.deviceId ?? item.externalDeviceKey} / {item.source} / {item.reason ?? "No reason"}</span>
              <small>{new Date(item.labelStartAt).toLocaleString()} - {new Date(item.labelEndAt).toLocaleString()}</small>
              {item.lineageHash && <small className="mono-value">h{item.horizonDays} {item.generationVersion} {item.lineageHash}</small>}
            </div>
            <div className="action-row">
              <select aria-label={`Correct label ${item.id}`} value={item.label} onChange={(event) => void correct(item, event.target.value as MlLabelType)}>
                {labelOptions.map((value) => <option key={value} value={value}>{value}</option>)}
              </select>
              <button className="ghost-button" onClick={() => void props.onAudit(item.id)} type="button">Audit</button>
            </div>
          </div>
        ))}
        {props.audit.length > 0 && (
          <>
            <h3>Audit trail</h3>
            {props.audit.map((item) => (
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

function TrainingTab(props: {
  datasets: MlDatasetSummary[];
  featureSnapshot: MlFeatureSnapshotResponse | null;
  runs: MlTrainingRun[];
  onChanged: () => Promise<void>;
  setError: (value: string | null) => void;
}) {
  const readyDatasets = props.datasets.filter((dataset) => dataset.status === "READY");
  const [datasetVersionId, setDatasetVersionId] = useState("");
  const [featureSnapshotId, setFeatureSnapshotId] = useState("");
  const [modelFamily, setModelFamily] = useState<"ANOMALY_DETECTION" | "FAILURE_PREDICTION">("ANOMALY_DETECTION");
  const [algorithm, setAlgorithm] = useState("baseline-statistical-anomaly-v1");
  const [failureHorizonDays, setFailureHorizonDays] = useState(14);
  const [minimumPositiveLabels, setMinimumPositiveLabels] = useState(20);
  const [minimumLabelCoverage, setMinimumLabelCoverage] = useState(0.8);
  const selectedTemplate = trainingTemplates[modelFamily];
  const selectedDataset = readyDatasets.find((dataset) => dataset.datasetVersionId === datasetVersionId);

  useEffect(() => {
    if (!datasetVersionId && readyDatasets[0]) {
      setDatasetVersionId(readyDatasets[0].datasetVersionId);
    }
  }, [datasetVersionId, readyDatasets]);

  useEffect(() => {
    if (props.featureSnapshot?.id) {
      setFeatureSnapshotId(props.featureSnapshot.id);
    }
  }, [props.featureSnapshot]);

  function chooseTemplate(nextModelFamily: "ANOMALY_DETECTION" | "FAILURE_PREDICTION") {
    setModelFamily(nextModelFamily);
    setAlgorithm(trainingTemplates[nextModelFamily].defaultAlgorithm);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (modelFamily === "FAILURE_PREDICTION" && (!failureHorizonDays || failureHorizonDays < 1)) {
      props.setError("Failure prediction requires a positive horizon.");
      return;
    }
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
    } catch (error) {
      props.setError(safeMlError(error));
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
          <label>Dataset version<select value={datasetVersionId} onChange={(e) => setDatasetVersionId(e.target.value)}>{readyDatasets.map((dataset) => <option key={dataset.datasetVersionId} value={dataset.datasetVersionId}>{dataset.name} v{dataset.version}</option>)}</select></label>
          <label>Feature snapshot id<input value={featureSnapshotId} onChange={(e) => setFeatureSnapshotId(e.target.value)} placeholder="Use the latest generated snapshot UUID" /></label>
          <label>Algorithm<select value={algorithm} onChange={(e) => setAlgorithm(e.target.value)}>
            {selectedTemplate.algorithms.map((value) => <option key={value} value={value}>{humanizeAlgorithm(value)}</option>)}
          </select></label>
        </div>
        {selectedDataset && (
          <div className="ml-readout">
            <strong>{selectedDataset.name} v{selectedDataset.version}</strong>
            <span>{selectedDataset.sourceType} / {selectedDataset.splitStrategy} / {selectedDataset.immutableRowReference ? "immutable rows ready" : "artifact-backed"}</span>
          </div>
        )}
        {modelFamily === "FAILURE_PREDICTION" && (
          <section className="ml-inline-panel">
            <h3>Failure prediction gates</h3>
            <div className="ml-form-grid">
              <label>Prediction horizon<input min="1" max="365" type="number" value={failureHorizonDays} onChange={(e) => setFailureHorizonDays(Number(e.target.value))} /></label>
              <label>Minimum positive labels<input min="1" type="number" value={minimumPositiveLabels} onChange={(e) => setMinimumPositiveLabels(Number(e.target.value))} /></label>
              <label>Minimum label coverage<input max="1" min="0" step="0.01" type="number" value={minimumLabelCoverage} onChange={(e) => setMinimumLabelCoverage(Number(e.target.value))} /></label>
            </div>
            <div className="ml-readout">
              <strong>Before queueing</strong>
              <span>Generate horizon labels for {failureHorizonDays} days, then build a failure-prediction feature snapshot from the same dataset version.</span>
            </div>
          </section>
        )}
        <button className="primary-button" disabled={!datasetVersionId}>Queue training</button>
      </form>
      <section className="panel">
        <h2>Training runs</h2>
        {props.runs.length === 0 ? <div className="empty-state">No training runs yet.</div> : props.runs.map((run) => (
          <div className="ml-readout" key={run.id}>
            <strong>{run.modelFamily} / {run.algorithm}</strong>
            <span>{run.status} / {run.logSummary ?? run.failureReason ?? "Waiting for worker"}</span>
            <div className="action-row action-row-start">
              {["QUEUED", "RUNNING"].includes(run.status) && <button className="ghost-button" onClick={() => void cancelTrainingRun(run.id).then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Cancel</button>}
              {["FAILED", "CANCELED"].includes(run.status) && <button className="ghost-button" onClick={() => void retryTrainingRun(run.id).then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Retry</button>}
            </div>
          </div>
        ))}
      </section>
    </div>
  );
}

function ModelsTab(props: { models: MlModelVersion[]; onChanged: () => Promise<void>; setError: (value: string | null) => void }) {
  return (
    <section className="panel">
      <h2>Model registry</h2>
      {props.models.length === 0 ? <div className="empty-state">No registered model versions yet.</div> : props.models.map((model) => {
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
                  onClick={() => void approveMlModel(model.id, "UI approval").then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))}
                  title={approveBlocked ? "Promotion gates must pass before approval." : undefined}
                  type="button"
                >
                  Approve
                </button>
              )}
              {model.status !== "REJECTED" && model.status !== "ACTIVE" && <button aria-label={`Reject ${modelLabel}`} className="ghost-button" onClick={() => void rejectMlModel(model.id, "UI rejection").then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Reject</button>}
              {model.status !== "ACTIVE" && <button aria-label={`Archive ${modelLabel}`} className="ghost-button" onClick={() => void archiveMlModel(model.id, "UI archive").then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Archive</button>}
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
        <span>{gates.promotionEligible ? "This candidate is eligible for approval." : "Resolve failed gates before approval."}</span>
      </div>
      {gates.failedCriteria.length > 0 && (
        <dl className="ml-gate-list">
          {gates.failedCriteria.map((gate) => (
            <div key={gate.name}>
              <dt>{humanizeFeatureName(gate.name)}</dt>
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

function PoliciesTab(props: { models: MlModelVersion[]; policies: MlDeploymentPolicy[]; onChanged: () => Promise<void>; setError: (value: string | null) => void }) {
  const deployableModels = props.models.filter((model) => (model.status === "APPROVED" || model.status === "ACTIVE") && !model.demoModel);
  const [scope, setScope] = useState<MlDeploymentScope>("ACCOUNT");
  const [mode, setMode] = useState<MlDeploymentMode>("SHADOW");
  const [modelVersionId, setModelVersionId] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [deviceType, setDeviceType] = useState("");
  const [canaryPercent, setCanaryPercent] = useState(10);
  const [fallbackEnabled, setFallbackEnabled] = useState(true);
  const [resolved, setResolved] = useState<string | null>(null);

  useEffect(() => {
    if (!modelVersionId && deployableModels[0]) {
      setModelVersionId(deployableModels[0].id);
    }
  }, [deployableModels, modelVersionId]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      await saveDeploymentPolicy({
        scope,
        deviceId: scope === "DEVICE" ? deviceId || null : null,
        deviceType: scope === "DEVICE_TYPE" ? deviceType || null : null,
        modelVersionId: mode === "DISABLED" ? null : modelVersionId || null,
        mode,
        canaryPercent: mode === "CANARY" ? canaryPercent : null,
        fallbackEnabled,
        reason: "Updated from ML console",
      });
      await props.onChanged();
    } catch (error) {
      props.setError(safeMlError(error));
    }
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
      <form className="panel ml-workflow-form" onSubmit={submit}>
        <h2>Deployment policy editor</h2>
        <div className="ml-control-grid">
          <label>Scope<select value={scope} onChange={(e) => setScope(e.target.value as MlDeploymentScope)}><option value="ACCOUNT">Account</option><option value="DEVICE_TYPE">Device type</option><option value="DEVICE">Device</option></select></label>
          <label>Mode<select value={mode} onChange={(e) => setMode(e.target.value as MlDeploymentMode)}><option value="SHADOW">Shadow</option><option value="CANARY">Canary</option><option value="ACTIVE">Active</option><option value="DISABLED">Disabled</option></select></label>
          <label>Model<select value={modelVersionId} onChange={(e) => setModelVersionId(e.target.value)}><option value="">No model</option>{deployableModels.map((model) => <option key={model.id} value={model.id}>{model.modelKey} v{model.version} / {modelOriginLabel(model)}</option>)}</select></label>
          {scope === "DEVICE" && <label>Device id<input value={deviceId} onChange={(e) => setDeviceId(e.target.value)} /></label>}
          {scope === "DEVICE_TYPE" && <label>Device type<input value={deviceType} onChange={(e) => setDeviceType(e.target.value)} /></label>}
          {mode === "CANARY" && <label>Canary percent<input max="100" min="0" onChange={(e) => setCanaryPercent(Number(e.target.value))} type="number" value={canaryPercent} /></label>}
        </div>
        <label className="checkbox-label"><input checked={fallbackEnabled} onChange={(e) => setFallbackEnabled(e.target.checked)} type="checkbox" /> Baseline fallback enabled</label>
        <div className="action-row action-row-start"><button className="primary-button">Save policy</button><button className="ghost-button" onClick={() => void resolve()} type="button">Resolve policy</button></div>
        {resolved && <div className="success-message">{resolved}</div>}
      </form>
      <section className="panel">
        <h2>Current policies</h2>
        {props.policies.length === 0 ? <div className="empty-state">No deployment policies yet.</div> : props.policies.map((policy) => (
          <div className="ml-readout" key={policy.id}>
            <strong>{policy.scope} / {policy.mode}</strong>
            <span>{policy.modelVersionId ?? "baseline fallback"} / fallback {policy.fallbackEnabled ? "on" : "off"}</span>
          </div>
        ))}
      </section>
    </div>
  );
}

function MonitoringTab(props: {
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
    const fallbackRate = props.policies.length === 0 ? 0 : Math.round((fallbackPolicies / props.policies.length) * 100);
    const errorRate = props.trainingRuns.length === 0 ? 0 : Math.round((failedRuns / props.trainingRuns.length) * 100);
    const predictionQuality = props.prediction ? Math.round(props.prediction.confidence * 100) : 0;
    const latencyStatus = props.prediction ? "Captured at prediction boundary" : "No recent prediction";
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
          <article><span>Ready datasets</span><strong>{stats.readyDatasets}</strong></article>
          <article><span>Active models</span><strong>{stats.activeModels}</strong></article>
          <article><span>Fallback rate</span><strong>{stats.fallbackRate}%</strong></article>
          <article><span>Error rate</span><strong>{stats.errorRate}%</strong></article>
          <article><span>Prediction quality</span><strong>{stats.predictionQuality}%</strong></article>
          <article><span>Inference latency</span><strong>{props.prediction ? "OK" : "N/A"}</strong></article>
          <article><span>Deployment path</span><strong>{stats.deploymentPath}</strong></article>
          <article><span>Canary</span><strong>{stats.canaryState}</strong></article>
        </div>
        <div className="ml-readout"><strong>Drift</strong><span>{stats.driftStatus}</span></div>
        <div className="ml-readout"><strong>Latency</strong><span>{stats.latencyStatus}</span></div>
        <div className="ml-readout"><strong>Fallback policies</strong><span>{stats.fallbackPolicies} policy scopes have baseline fallback enabled</span></div>
        <div className="ml-readout"><strong>Training errors</strong><span>{stats.failedRuns} failed runs in the current list</span></div>
        <h3>Deployment comparison</h3>
        <dl className="ml-model-metric-grid">
          <div><dt>Active</dt><dd>{props.prediction?.predictionPath === "ACTIVE" ? statusText(props.prediction.predictionStatus) : "No active path in latest trace"}</dd></div>
          <div><dt>Shadow</dt><dd>{props.prediction?.shadowPrediction ? statusText(props.prediction.predictionStatus) : "Stored separately from latest active"}</dd></div>
          <div><dt>Canary</dt><dd>{props.prediction?.deploymentMode === "CANARY" ? formatCanaryState(props.prediction) : "No canary policy in latest trace"}</dd></div>
          <div><dt>Fallback</dt><dd>{props.prediction?.predictionPath === "FALLBACK" ? props.prediction.fallbackReason || "Fallback used" : "Not used"}</dd></div>
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
  promotionEligible: boolean;
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
    promotionEligible: failedCriteria.length === 0,
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
  const failedCriteria = criteria
    .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === "object" && !Array.isArray(item))
    .map((item) => ({
      name: stringMetric(item, "name") ?? "approval_gate",
      observed: metricNumber(item, "observed"),
      threshold: metricNumber(item, "threshold"),
      passed: Boolean(item.passed),
      reason: stringMetric(item, "reason") ?? "Approval gate failed.",
    }))
    .filter((item) => !item.passed);
  const promotionEligible = typeof gateObject.promotionEligible === "boolean"
    ? gateObject.promotionEligible
    : failedCriteria.length === 0;
  return {
    status: stringMetric(gateObject, "status") ?? (promotionEligible ? "PASSED" : "NEEDS_REVIEW"),
    promotionEligible,
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
