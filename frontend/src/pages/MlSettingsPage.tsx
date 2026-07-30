import { FormEvent, useEffect, useMemo, useState } from "react";
import { getLatestPrediction, PredictionResponse } from "../analytics/analyticsApi";
import {
  approveMlModel,
  archiveMlModel,
  cancelTrainingRun,
  correctMlLabel,
  createTrainingRun,
  createMlLabel,
  generateFeatureSnapshot,
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
          onGenerateFeatureSnapshot={async (datasetVersionId) => {
            setBusy(true);
            setError(null);
            try {
              setFeatureSnapshot(await generateFeatureSnapshot({
                datasetVersionId,
                featureSetVersion: "feature-set-v1",
                codeConfigVersion: "analytics-feature-pipeline-v1",
                rollingWindowSize: 6,
                missingGapSeconds: 21600,
                enabledFeatureFamilies: ["rolling_stats", "baseline_deviation", "missing_data", "time", "label"],
              }));
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
  onGenerateFeatureSnapshot: (datasetVersionId: string) => Promise<void>;
  onSelect: (datasetVersionId: string) => Promise<void>;
}) {
  return (
    <div className="ml-workflow-grid">
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
            </dl>
            <h3>Validation</h3>
            {props.detail.validationReports.map((report) => (
              <div className="ml-readout" key={report.id}>
                <strong>{report.summary}</strong>
                <span>{report.acceptedRows} accepted / {report.rejectedRows} rejected / {report.warningCount} warnings</span>
              </div>
            ))}
            <h3>Lineage</h3>
            {props.detail.lineage.map((lineage) => (
              <div className="ml-readout" key={lineage.id}>
                <strong>{lineage.sourceReference}</strong>
                <span className="mono-value">{lineage.contentHash}</span>
              </div>
            ))}
            <div className="action-row action-row-start">
              <button className="primary-button" onClick={() => void props.onGenerateFeatureSnapshot(props.detail!.dataset.datasetVersionId)} type="button">Generate feature snapshot</button>
            </div>
            {props.featureSnapshot && <div className="success-message">Feature hash {props.featureSnapshot.featureSchemaHash}</div>}
          </>
        )}
      </section>
    </div>
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

  return (
    <div className="ml-workflow-grid">
      <form className="panel" onSubmit={create}>
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
      </form>
      <section className="panel">
        <h2>Dataset labels</h2>
        {props.labels.length === 0 ? <div className="empty-state">No labels loaded for the selected dataset.</div> : props.labels.map((item) => (
          <div className="ml-model-row" key={item.id}>
            <div>
              <strong>{item.label} · {item.metricName ?? "metric"}</strong>
              <span>{item.deviceId ?? item.externalDeviceKey} · {item.source} · {item.reason ?? "No reason"}</span>
              <small>{new Date(item.labelStartAt).toLocaleString()} - {new Date(item.labelEndAt).toLocaleString()}</small>
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
                <span>{item.reason ?? "No reason"} · {new Date(item.editedAt).toLocaleString()}</span>
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
  const [algorithm, setAlgorithm] = useState("baseline-statistical-anomaly-v1");

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

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      await createTrainingRun({
        datasetVersionId,
        featureSnapshotId: featureSnapshotId || null,
        modelFamily: "ANOMALY_DETECTION",
        algorithm,
        hyperparametersJson: "{}",
      });
      await props.onChanged();
    } catch (error) {
      props.setError(safeMlError(error));
    }
  }

  return (
    <div className="ml-workflow-grid">
      <form className="panel" onSubmit={submit}>
        <h2>Create training run</h2>
        <label>Dataset version<select value={datasetVersionId} onChange={(e) => setDatasetVersionId(e.target.value)}>{readyDatasets.map((dataset) => <option key={dataset.datasetVersionId} value={dataset.datasetVersionId}>{dataset.name} v{dataset.version}</option>)}</select></label>
        <label>Feature snapshot id<input value={featureSnapshotId} onChange={(e) => setFeatureSnapshotId(e.target.value)} placeholder="Optional snapshot UUID" /></label>
        <label>Algorithm<input value={algorithm} onChange={(e) => setAlgorithm(e.target.value)} /></label>
        <button className="primary-button" disabled={!datasetVersionId}>Queue training</button>
      </form>
      <section className="panel">
        <h2>Training runs</h2>
        {props.runs.length === 0 ? <div className="empty-state">No training runs yet.</div> : props.runs.map((run) => (
          <div className="ml-readout" key={run.id}>
            <strong>{run.modelFamily} / {run.algorithm}</strong>
            <span>{run.status} · {run.logSummary ?? run.failureReason ?? "Waiting for worker"}</span>
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
      {props.models.length === 0 ? <div className="empty-state">No registered model versions yet.</div> : props.models.map((model) => (
        <div className="ml-model-row" key={model.id}>
          <div>
            <strong>{model.modelKey} v{model.version}</strong>
            <span>{model.family} · {model.algorithm} · {model.status}</span>
            <small>{Object.entries(model.evaluationMetrics ?? {}).map(([key, value]) => `${key}: ${String(value)}`).join(" · ") || "No metrics"}</small>
          </div>
          <div className="action-row">
            {model.status !== "APPROVED" && model.status !== "ACTIVE" && <button className="ghost-button" onClick={() => void approveMlModel(model.id, "UI approval").then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Approve</button>}
            {model.status !== "REJECTED" && model.status !== "ACTIVE" && <button className="ghost-button" onClick={() => void rejectMlModel(model.id, "UI rejection").then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Reject</button>}
            {model.status !== "ACTIVE" && <button className="ghost-button" onClick={() => void archiveMlModel(model.id, "UI archive").then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Archive</button>}
          </div>
        </div>
      ))}
    </section>
  );
}

function PoliciesTab(props: { models: MlModelVersion[]; policies: MlDeploymentPolicy[]; onChanged: () => Promise<void>; setError: (value: string | null) => void }) {
  const deployableModels = props.models.filter((model) => model.status === "APPROVED" || model.status === "ACTIVE");
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
      setResolved(`${policy.policySource ?? "BASELINE"} · ${policy.mode} · ${policy.fallbackReason ?? "model selected"}`);
    } catch (error) {
      props.setError(safeMlError(error));
    }
  }

  return (
    <div className="ml-workflow-grid">
      <form className="panel" onSubmit={submit}>
        <h2>Deployment policy editor</h2>
        <label>Scope<select value={scope} onChange={(e) => setScope(e.target.value as MlDeploymentScope)}><option value="ACCOUNT">Account</option><option value="DEVICE_TYPE">Device type</option><option value="DEVICE">Device</option></select></label>
        {scope === "DEVICE" && <label>Device id<input value={deviceId} onChange={(e) => setDeviceId(e.target.value)} /></label>}
        {scope === "DEVICE_TYPE" && <label>Device type<input value={deviceType} onChange={(e) => setDeviceType(e.target.value)} /></label>}
        <label>Mode<select value={mode} onChange={(e) => setMode(e.target.value as MlDeploymentMode)}><option value="SHADOW">Shadow</option><option value="CANARY">Canary</option><option value="ACTIVE">Active</option><option value="DISABLED">Disabled</option></select></label>
        <label>Model<select value={modelVersionId} onChange={(e) => setModelVersionId(e.target.value)}><option value="">No model</option>{deployableModels.map((model) => <option key={model.id} value={model.id}>{model.modelKey} v{model.version}</option>)}</select></label>
        {mode === "CANARY" && <label>Canary percent<input max="100" min="0" onChange={(e) => setCanaryPercent(Number(e.target.value))} type="number" value={canaryPercent} /></label>}
        <label><input checked={fallbackEnabled} onChange={(e) => setFallbackEnabled(e.target.checked)} type="checkbox" /> Baseline fallback enabled</label>
        <div className="action-row action-row-start"><button className="primary-button">Save policy</button><button className="ghost-button" onClick={() => void resolve()} type="button">Resolve policy</button></div>
        {resolved && <div className="success-message">{resolved}</div>}
      </form>
      <section className="panel">
        <h2>Current policies</h2>
        {props.policies.length === 0 ? <div className="empty-state">No deployment policies yet.</div> : props.policies.map((policy) => (
          <div className="ml-readout" key={policy.id}>
            <strong>{policy.scope} · {policy.mode}</strong>
            <span>{policy.modelVersionId ?? "baseline fallback"} · fallback {policy.fallbackEnabled ? "on" : "off"}</span>
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
    return { activeModels, driftStatus, errorRate, failedRuns, fallbackPolicies, fallbackRate, latencyStatus, predictionQuality, readyDatasets };
  }, [props.datasets, props.models, props.policies, props.trainingRuns]);

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
        </div>
        <div className="ml-readout"><strong>Drift</strong><span>{stats.driftStatus}</span></div>
        <div className="ml-readout"><strong>Latency</strong><span>{stats.latencyStatus}</span></div>
        <div className="ml-readout"><strong>Fallback policies</strong><span>{stats.fallbackPolicies} policy scopes have baseline fallback enabled</span></div>
        <div className="ml-readout"><strong>Training errors</strong><span>{stats.failedRuns} failed runs in the current list</span></div>
      </section>
      <MlStatus deviceMode={Boolean(props.deviceId)} error={props.statusError} prediction={props.prediction} />
    </div>
  );
}

function MlStatus({ prediction, error, deviceMode }: { prediction: PredictionResponse | null; error: string | null; deviceMode: boolean }) {
  if (!deviceMode) return <section className="panel"><h2>Latest prediction</h2><div className="empty-state">Select a device to inspect read-only ML metadata.</div></section>;
  if (!prediction) return <section className="panel"><h2>Latest prediction</h2><div className="empty-state">{error ?? "Prediction metadata is unavailable. Settings remain editable."}</div></section>;
  const explanations = prediction.explanations ?? [];
  return (
    <section className="panel">
      <div className="section-header">
        <div><h2>Latest prediction</h2><p>Read-only traceability</p></div>
        <span className="status-badge">{statusText(prediction.predictionStatus)}</span>
      </div>
      <dl className="detail-list">
        <div><dt>Model</dt><dd>{prediction.modelName} / {prediction.modelVersion}</dd></div>
        <div><dt>Type / status</dt><dd>{prediction.modelType ?? "Unknown"} / {prediction.modelStatus ?? "Unknown"}</dd></div>
        <div><dt>Confidence</dt><dd>{Math.round(prediction.confidence * 100)}%</dd></div>
        <div><dt>Input hash</dt><dd className="mono-value">{prediction.inputHash || "Unavailable"}</dd></div>
      </dl>
      <h3>Signals</h3><pre>{JSON.stringify(prediction.signals ?? [], null, 2)}</pre>
      <h3>Explanations</h3><ul>{explanations.map((x, i) => <li key={i}>{x}</li>)}</ul>
      <h3>Recommended actions</h3><ul>{(prediction.recommendedActions ?? []).map((x, i) => <li key={i}>{x}</li>)}</ul>
    </section>
  );
}

function statusText(v: string) {
  return ({ UNAVAILABLE: "ML unavailable; rule analytics continue", INSUFFICIENT_DATA: "More telemetry required", SUPPRESSED_MAINTENANCE: "Suppressed during maintenance" } as Record<string, string>)[v] ?? v;
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

const labelOptions: MlLabelType[] = ["NORMAL", "ANOMALY", "DEGRADATION", "MAINTENANCE", "UNKNOWN"];

function toInstant(value: string) {
  return new Date(value).toISOString();
}
