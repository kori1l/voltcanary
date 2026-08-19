import { FormEvent, useEffect, useMemo, useState } from "react";
import { PredictionResponse } from "../analytics/analyticsApi";
import {
  approveMlModel,
  archiveFeatureSnapshot,
  archiveMlDatasetVersion,
  archiveMlModel,
  archiveTrainingRun,
  cancelTrainingRun,
  createInternalTelemetryDataset,
  correctMlLabel,
  createTrainingRun,
  deleteDeploymentPolicy,
  deleteFeatureSnapshot,
  deleteMlDatasetVersion,
  deleteMlLabel,
  deleteMlModel,
  deleteTrainingRun,
  disableDeploymentPolicy,
  createMlLabel,
  generateFeatureSnapshot,
  generateHorizonLabels,
  getHorizonLabelGenerationStatus,
  getMlDatasetVersion,
  importMlDataset,
  listFeatureSnapshots,
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
  MlInternalDatasetResponse,
  MlLabel,
  MlLabelAudit,
  MlLabelType,
  MlLifecycleImpact,
  MlLifecycleStatusFilter,
  MlModelVersion,
  MlTrainingRun,
  previewDeploymentPolicyLifecycle,
  previewFeatureSnapshotLifecycle,
  previewMlDatasetVersionLifecycle,
  previewMlLabelLifecycle,
  previewMlModelLifecycle,
  previewTrainingRunLifecycle,
  rejectMlModel,
  resolveDeploymentPolicy,
  retryTrainingRun,
  saveDeploymentPolicy,
} from "../analytics/mlModuleApi";
import {
  MlSettingsResponse,
  MlSettingsValues,
  safeMlError,
} from "../analytics/mlSettingsApi";
import { Device, DeviceType } from "../devices/devicesApi";
import { DatasetWorkbench } from "../features/ml-datasets/components/DatasetWorkbench";
import type { DatasetPipelineStep, DatasetWorkbenchDefaults, DatasetWorkbenchModelFamily } from "../features/ml-datasets/model/datasetWorkbench.types";
import { useHorizonLabelGeneration } from "../features/ml-settings/hooks/useHorizonLabelGeneration";
import { useMlWorkspaceData } from "../features/ml-settings/hooks/useMlWorkspaceData";
import { trainingBlockReason, useTrainingDraft } from "../features/ml-settings/hooks/useTrainingDraft";
import { formatImpactDependencies, requestLifecycleReason } from "../features/ml-settings/model/mlLifecycleActions";
import { MlModelsTab, MlMonitoringTab, MlPoliciesTab } from "../features/ml-settings/components/MlOperationalTabs";

type MlTab = "settings" | "datasets" | "import" | "labels" | "training" | "models" | "policies" | "monitoring";
type FeatureSnapshotConfig = Parameters<typeof generateFeatureSnapshot>[0];
type DatasetLifecycleKind = "dataset" | "snapshot";
type ArchiveDeleteAction = "archive" | "delete";
const lifecycleStatusFilters: MlLifecycleStatusFilter[] = ["default", "active", "failed", "archived", "deleted", "all"];
type WorkflowMode = DatasetWorkbenchModelFamily;

function buildPipelineSteps(
  mode: WorkflowMode,
  datasets: MlDatasetSummary[],
  labels: MlLabel[],
  featureSnapshots: MlFeatureSnapshotResponse[],
  trainingRuns: MlTrainingRun[],
  models: MlModelVersion[],
  policies: MlDeploymentPolicy[],
  prediction: PredictionResponse | null,
): DatasetPipelineStep[] {
  const datasetItems = Array.isArray(datasets) ? datasets : [];
  const labelItems = Array.isArray(labels) ? labels : [];
  const trainingItems = Array.isArray(trainingRuns) ? trainingRuns : [];
  const modelItems = Array.isArray(models) ? models : [];
  const policyItems = Array.isArray(policies) ? policies : [];
  const snapshotItems = Array.isArray(featureSnapshots) ? featureSnapshots : [];
  const readyDatasets = datasetItems.filter((dataset) => dataset.status === "READY");
  const matchingSnapshots = snapshotItems.filter((snapshot) => (snapshot.modelFamily ?? "ANOMALY_DETECTION") === mode);
  const matchingRuns = trainingItems.filter((run) => run.modelFamily === mode && ["QUEUED", "RUNNING", "SUCCEEDED"].includes(run.status));
  const approvedModels = modelItems.filter((model) => model.family === mode && ["APPROVED", "ACTIVE"].includes(model.status));
  const activePolicies = policyItems.filter((policy) => policy.mode !== "DISABLED" && Boolean(policy.modelVersionId));
  const hasPositiveFailureLabels = labelItems.some((label) => ["CONFIRMED_FAILURE", "CORRECTIVE_MAINTENANCE"].includes(label.label));
  const labelsRequired = mode === "FAILURE_PREDICTION";

  return [
    {
      id: "dataset",
      label: "Dataset",
      status: readyDatasets.length > 0 ? "ready" : datasetItems.length > 0 ? "review" : "blocked",
      detail: readyDatasets.length > 0 ? `${readyDatasets.length} ready` : "Create a previewed dataset",
    },
    {
      id: "labels",
      label: "Labels",
      status: labelsRequired ? (hasPositiveFailureLabels ? "ready" : labelItems.length > 0 ? "review" : "blocked") : "ready",
      detail: labelsRequired ? `${labelItems.length} loaded / failure positives ${hasPositiveFailureLabels ? "present" : "missing"}` : "Not required",
    },
    {
      id: "snapshot",
      label: "Feature snapshot",
      status: matchingSnapshots.length > 0 ? "ready" : snapshotItems.length > 0 ? "review" : "blocked",
      detail: matchingSnapshots.length > 0 ? `${matchingSnapshots.length} snapshots` : `Need ${humanizeKey(mode)} snapshot`,
    },
    {
      id: "training",
      label: "Training",
      status: matchingRuns.length > 0 ? "ready" : trainingItems.length > 0 ? "review" : "blocked",
      detail: matchingRuns.length > 0 ? `${matchingRuns.length} matching runs` : "Queue a matching run",
    },
    {
      id: "model",
      label: "Model",
      status: approvedModels.length > 0 ? "ready" : modelItems.some((model) => model.family === mode) ? "review" : "blocked",
      detail: approvedModels.length > 0 ? `${approvedModels.length} approved` : "Approve a matching model",
    },
    {
      id: "policy",
      label: "Policy",
      status: activePolicies.length > 0 ? "ready" : policyItems.length > 0 ? "review" : "blocked",
      detail: activePolicies.length > 0 ? `${activePolicies.length} active` : "Enable deployment policy",
    },
    {
      id: "monitoring",
      label: "Monitoring",
      status: prediction ? "ready" : "review",
      detail: prediction ? statusText(prediction.predictionStatus) : "Load device prediction",
    },
  ];
}

export function MlSettingsPage() {
  const [activeTab, setActiveTab] = useState<MlTab>("settings");
  const [workflowMode, setWorkflowMode] = useState<WorkflowMode>("ANOMALY_DETECTION");
  const [datasetStatusFilter, setDatasetStatusFilter] = useState<MlLifecycleStatusFilter>("default");
  const [trainingStatusFilter, setTrainingStatusFilter] = useState<MlLifecycleStatusFilter>("default");
  const [modelStatusFilter, setModelStatusFilter] = useState<MlLifecycleStatusFilter>("default");
  const [policyStatusFilter, setPolicyStatusFilter] = useState<MlLifecycleStatusFilter>("default");
  const workspace = useMlWorkspaceData({
    datasetStatusFilter,
    trainingStatusFilter,
    modelStatusFilter,
    policyStatusFilter,
  });
  const {
    values,
    effective,
    deviceId,
    setDeviceId,
    deviceMode,
    prediction,
    statusError,
    error,
    setError,
    message,
    setMessage,
    busy,
    setBusy,
    datasets,
    setDatasets,
    datasetDetail,
    setDatasetDetail,
    trainingRuns,
    setTrainingRuns,
    models,
    setModels,
    policies,
    setPolicies,
    devices,
    deviceTypes,
    featureSnapshots,
    setFeatureSnapshots,
    labels,
    setLabels,
    labelAudit,
    loadAccount,
    loadDevice,
    refreshMlWorkspace,
    save,
    clearOverride,
    loadDatasetDetail,
    loadLabelAudit,
    refreshLabels,
    set,
  } = workspace;
  const pipelineSteps = useMemo(
    () => buildPipelineSteps(workflowMode, datasets, labels, featureSnapshots, trainingRuns, models, policies, prediction),
    [datasets, featureSnapshots, labels, models, policies, prediction, trainingRuns, workflowMode],
  );

  return (
    <>
      <header className="page-header">
        <h1>ML Settings</h1>
        <p>Prediction policy, training assets, deployment, and read-only model readiness.</p>
      </header>
      {message && <div className="success-message">{message}</div>}
      {error && <div className="error-message">{error}</div>}
      <WorkflowPipelineRail mode={workflowMode} onModeChange={setWorkflowMode} steps={pipelineSteps} />
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
          devices={devices}
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
          devices={devices}
          detail={datasetDetail}
          featureSnapshots={featureSnapshots}
          workflowMode={workflowMode}
          accountId={effective?.accountId ?? "unknown-account"}
          statusFilter={datasetStatusFilter}
          onFilterChange={async (value) => {
            setDatasetStatusFilter(value);
            setDatasets(await listMlDatasets(value));
          }}
          onCreateInternalDataset={async (body) => {
            setBusy(true);
            setError(null);
            try {
              const response = await createInternalTelemetryDataset(body);
              await refreshMlWorkspace();
              await loadDatasetDetail(response.datasetVersionId);
              setMessage(`Internal telemetry dataset ${response.status}: ${response.acceptedRows} accepted / ${response.rejectedRows} rejected`);
              return response;
            } catch (e) {
              setError(safeMlError(e));
              throw e;
            } finally {
              setBusy(false);
            }
          }}
          onGenerateFeatureSnapshot={async (body) => {
            setBusy(true);
            setError(null);
            try {
              const snapshot = await generateFeatureSnapshot(body);
              setFeatureSnapshots((current) => upsertFeatureSnapshot(current, snapshot));
              setMessage("Feature snapshot generated");
              await refreshMlWorkspace();
            } catch (e) {
              setError(safeMlError(e));
            } finally {
              setBusy(false);
            }
          }}
          onLifecycle={async (kind, id, action, label) => {
            setBusy(true);
            setError(null);
            try {
              const impact = kind === "dataset"
                ? await previewMlDatasetVersionLifecycle(id, action)
                : await previewFeatureSnapshotLifecycle(id, action);
              if (impact.blocked) {
                setError(`${impact.message}${formatImpactDependencies(impact)}`);
                return;
              }
              const reason = requestLifecycleReason(action, label, impact);
              if (!reason) return;
              if (kind === "dataset") {
                await (action === "archive" ? archiveMlDatasetVersion(id, reason) : deleteMlDatasetVersion(id, reason));
                setDatasetDetail(null);
              } else {
                await (action === "archive" ? archiveFeatureSnapshot(id, reason) : deleteFeatureSnapshot(id, reason));
                setFeatureSnapshots((current) => current.filter((snapshot) => snapshot.id !== id));
              }
              await refreshMlWorkspace();
              setMessage(`${label} ${action}d`);
            } catch (e) {
              setError(safeMlError(e));
            } finally {
              setBusy(false);
            }
          }}
          onSelect={loadDatasetDetail}
          onWorkflowModeChange={setWorkflowMode}
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
          onAudit={loadLabelAudit}
          onChanged={async (datasetVersionId) => {
            await refreshLabels(datasetVersionId);
            setMessage("Label review updated");
          }}
          onDelete={async (label) => {
            try {
              const impact = await previewMlLabelLifecycle(label.id, "delete");
              if (impact.blocked) {
                setError(`${impact.message}${formatImpactDependencies(impact)}`);
                return;
              }
              const reason = requestLifecycleReason("delete", `${label.label} label`, impact);
              if (!reason) return;
              await deleteMlLabel(label.id, reason);
              setLabels(await listMlLabels(label.datasetVersionId));
              setMessage("Label deleted");
            } catch (e) {
              setError(safeMlError(e));
            }
          }}
          setError={setError}
        />
      )}
      {activeTab === "training" && (
        <TrainingTab
          datasets={datasets}
          accountId={effective?.accountId ?? "unknown-account"}
          featureSnapshots={featureSnapshots}
          runs={trainingRuns}
          statusFilter={trainingStatusFilter}
          onFilterChange={async (value) => {
            setTrainingStatusFilter(value);
            setTrainingRuns(await listTrainingRuns(value));
          }}
          onChanged={async () => {
            await refreshMlWorkspace();
            setMessage("Training workflow updated");
          }}
          onLifecycle={async (run, action) => {
            try {
              const impact = await previewTrainingRunLifecycle(run.id, action);
              if (impact.blocked) {
                setError(`${impact.message}${formatImpactDependencies(impact)}`);
                return;
              }
              const reason = requestLifecycleReason(action, `${run.modelFamily} training run`, impact);
              if (!reason) return;
              await (action === "archive" ? archiveTrainingRun(run.id, reason) : deleteTrainingRun(run.id, reason));
              await refreshMlWorkspace();
              setMessage(`Training run ${action}d`);
            } catch (e) {
              setError(safeMlError(e));
            }
          }}
          setError={setError}
        />
      )}
      {activeTab === "models" && (
        <MlModelsTab
          models={models}
          statusFilter={modelStatusFilter}
          onFilterChange={async (value) => {
            setModelStatusFilter(value);
            setModels(await listMlModels(value));
          }}
          onChanged={async () => {
            await refreshMlWorkspace();
            setMessage("Model registry updated");
          }}
          onDelete={async (model) => {
            try {
              const impact = await previewMlModelLifecycle(model.id, "delete");
              if (impact.blocked) {
                setError(`${impact.message}${formatImpactDependencies(impact)}`);
                return;
              }
              const reason = requestLifecycleReason("delete", `${model.modelKey} v${model.version}`, impact);
              if (!reason) return;
              await deleteMlModel(model.id, reason);
              await refreshMlWorkspace();
              setMessage("Model deleted");
            } catch (e) {
              setError(safeMlError(e));
            }
          }}
          setError={setError}
        />
      )}
      {activeTab === "policies" && (
        <MlPoliciesTab
          models={models}
          policies={policies}
          devices={devices}
          deviceTypes={deviceTypes}
          statusFilter={policyStatusFilter}
          onFilterChange={async (value) => {
            setPolicyStatusFilter(value);
            setPolicies(await listDeploymentPolicies(value));
          }}
          onChanged={async () => {
            await refreshMlWorkspace();
            setMessage("Deployment policy saved");
          }}
          onLifecycle={async (policy, action) => {
            try {
              const impact = await previewDeploymentPolicyLifecycle(policy.id, action);
              if (impact.blocked) {
                setError(`${impact.message}${formatImpactDependencies(impact)}`);
                return;
              }
              const reason = requestLifecycleReason(action, `${policy.scope} policy`, impact);
              if (!reason) return;
              await (action === "disable" ? disableDeploymentPolicy(policy.id, reason) : deleteDeploymentPolicy(policy.id, reason));
              await refreshMlWorkspace();
              setMessage(`Deployment policy ${action}d`);
            } catch (e) {
              setError(safeMlError(e));
            }
          }}
          setError={setError}
        />
      )}
      {activeTab === "monitoring" && (
        <MlMonitoringTab
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
  devices: Device[];
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
          <input aria-label="Device ID" list="ml-device-options" placeholder="Search or paste device UUID" value={props.deviceId} onChange={(e) => props.setDeviceId(e.target.value)} />
          <datalist id="ml-device-options">
            {props.devices.map((device) => <option key={device.id} value={device.id}>{deviceLabel(device)}</option>)}
          </datalist>
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

function WorkflowPipelineRail(props: {
  mode: WorkflowMode;
  onModeChange: (mode: WorkflowMode) => void;
  steps: DatasetPipelineStep[];
}) {
  return (
    <section className="ml-pipeline-rail" aria-label="ML lifecycle pipeline">
      <div className="ml-pipeline-mode" role="group" aria-label="Workflow mode">
        <button
          className={props.mode === "ANOMALY_DETECTION" ? "ml-pipeline-mode-active" : ""}
          onClick={() => props.onModeChange("ANOMALY_DETECTION")}
          type="button"
        >
          Anomaly detection
        </button>
        <button
          className={props.mode === "FAILURE_PREDICTION" ? "ml-pipeline-mode-active" : ""}
          onClick={() => props.onModeChange("FAILURE_PREDICTION")}
          type="button"
        >
          Failure prediction
        </button>
      </div>
      <ol className="ml-pipeline-steps">
        {props.steps.map((step) => (
          <li className={`ml-pipeline-step ml-pipeline-step-${step.status}`} key={step.id}>
            <strong>{step.label}</strong>
            <span>{step.status}</span>
            <small>{step.detail}</small>
          </li>
        ))}
      </ol>
    </section>
  );
}

function DatasetsTab(props: {
  accountId: string;
  datasets: MlDatasetSummary[];
  devices: Device[];
  detail: MlDatasetDetail | null;
  featureSnapshots: MlFeatureSnapshotResponse[];
  workflowMode: WorkflowMode;
  statusFilter: MlLifecycleStatusFilter;
  onCreateInternalDataset: (body: Parameters<typeof createInternalTelemetryDataset>[0]) => Promise<MlInternalDatasetResponse>;
  onFilterChange: (value: MlLifecycleStatusFilter) => Promise<void>;
  onGenerateFeatureSnapshot: (body: FeatureSnapshotConfig) => Promise<void>;
  onLifecycle: (kind: DatasetLifecycleKind, id: string, action: ArchiveDeleteAction, label: string) => Promise<void>;
  onSelect: (datasetVersionId: string) => Promise<void>;
  onWorkflowModeChange: (mode: WorkflowMode) => void;
}) {
  const [modelFamily, setModelFamily] = useState<WorkflowMode>(props.workflowMode);
  const [horizonDays, setHorizonDays] = useState(30);
  const [featureTemplateId, setFeatureTemplateId] = useState("generic-appliance");
  const [featureTemplateVersion, setFeatureTemplateVersion] = useState("template-v1");
  const [rollingWindowSize, setRollingWindowSize] = useState(6);
  const [missingGapSeconds, setMissingGapSeconds] = useState(21600);
  const [featureFamiliesText, setFeatureFamiliesText] = useState(defaultFeatureFamilies("ANOMALY_DETECTION").join("\n"));
  const selectedDatasetVersionId = props.detail?.dataset.datasetVersionId ?? "";
  const detailSnapshots = props.featureSnapshots.filter((snapshot) => snapshot.datasetVersionId === selectedDatasetVersionId);

  useEffect(() => {
    setModelFamily(props.workflowMode);
    setFeatureFamiliesText(defaultFeatureFamilies(props.workflowMode).join("\n"));
  }, [props.workflowMode]);

  function applyWorkbenchDefaults(defaults: DatasetWorkbenchDefaults) {
    setModelFamily(defaults.modelFamily);
    props.onWorkflowModeChange(defaults.modelFamily);
    setHorizonDays(defaults.horizonDays ?? 30);
    setFeatureFamiliesText(defaultFeatureFamilies(defaults.modelFamily).join("\n"));
  }

  function chooseModelFamily(nextModelFamily: WorkflowMode) {
    setModelFamily(nextModelFamily);
    props.onWorkflowModeChange(nextModelFamily);
    setFeatureFamiliesText(defaultFeatureFamilies(nextModelFamily).join("\n"));
  }

  function generateSnapshot() {
    if (!selectedDatasetVersionId) return;
    if (modelFamily === "FAILURE_PREDICTION" && (!horizonDays || horizonDays < 1)) {
      return;
    }
    void props.onGenerateFeatureSnapshot({
      datasetVersionId: selectedDatasetVersionId,
      featureSetVersion: "feature-set-v1",
      codeConfigVersion: "analytics-feature-pipeline-v1",
      rollingWindowSize,
      missingGapSeconds,
      enabledFeatureFamilies: splitList(featureFamiliesText),
      modelFamily,
      horizonDays: modelFamily === "FAILURE_PREDICTION" ? horizonDays : null,
      featureTemplateId,
      featureTemplateVersion,
    });
  }

  return (
    <div className="ml-datasets-workspace">
      <DatasetWorkbench
        accountId={props.accountId}
        devices={props.devices}
        workflowMode={props.workflowMode}
        onCreate={props.onCreateInternalDataset}
        onCreatedFromWorkbench={applyWorkbenchDefaults}
        onWorkflowModeChange={props.onWorkflowModeChange}
      />
      <div className="ml-datasets-secondary-grid">
        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Dataset versions</h2>
              <p>Validation status, lineage, and feature preparation.</p>
            </div>
            <StatusFilterSelect label="Dataset status filter" value={props.statusFilter} onChange={props.onFilterChange} />
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
        <section className="panel ml-datasets-detail-panel">
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
            <div className="action-row action-row-start">
              <button className="ghost-button" onClick={() => void props.onLifecycle("dataset", props.detail!.dataset.datasetVersionId, "archive", props.detail!.dataset.name)} type="button">Archive dataset</button>
              {["DRAFT", "FAILED"].includes(props.detail.dataset.status) && (
                <button className="ghost-button" onClick={() => void props.onLifecycle("dataset", props.detail!.dataset.datasetVersionId, "delete", props.detail!.dataset.name)} type="button">Delete dataset</button>
              )}
            </div>
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
                <label>Model family<select value={modelFamily} onChange={(event) => chooseModelFamily(event.target.value as typeof modelFamily)}><option value="ANOMALY_DETECTION">Anomaly detection</option><option value="FAILURE_PREDICTION">Failure prediction</option></select></label>
                <label>Feature template<select value={featureTemplateId} onChange={(event) => setFeatureTemplateId(event.target.value)}><option value="generic-appliance">Generic appliance</option><option value="fridge-freezer">Fridge/freezer</option></select></label>
                <label>Template version<input value={featureTemplateVersion} onChange={(event) => setFeatureTemplateVersion(event.target.value)} /></label>
                <label>Rolling window<input min="1" type="number" value={rollingWindowSize} onChange={(event) => setRollingWindowSize(Number(event.target.value))} /></label>
                <label>Max gap seconds<input min="1" type="number" value={missingGapSeconds} onChange={(event) => setMissingGapSeconds(Number(event.target.value))} /></label>
                {modelFamily === "FAILURE_PREDICTION" && <label>Snapshot horizon days<input min="1" max="365" type="number" value={horizonDays} onChange={(event) => setHorizonDays(Number(event.target.value))} required /></label>}
              </div>
              {modelFamily === "FAILURE_PREDICTION" && (
                <div className="ml-readout">
                  <strong>Failure feature snapshot</strong>
                  <span>Use the same horizon as generated labels and failure-prediction training.</span>
                </div>
              )}
              <label>Feature families<textarea className="ml-compact-editor" value={featureFamiliesText} onChange={(event) => setFeatureFamiliesText(event.target.value)} /></label>
              <div className="action-row action-row-start">
                <button className="primary-button" disabled={modelFamily === "FAILURE_PREDICTION" && (!horizonDays || horizonDays < 1)} onClick={generateSnapshot} type="button">Generate feature snapshot</button>
              </div>
              {detailSnapshots.length === 0 ? (
                <div className="empty-state">No feature snapshots generated for this dataset yet.</div>
              ) : detailSnapshots.map((snapshot) => (
                <div className="ml-readout" key={snapshot.id}>
                  <FeatureSnapshotSummary snapshot={snapshot} />
                  <div className="action-row action-row-start">
                    <button className="ghost-button" onClick={() => void props.onLifecycle("snapshot", snapshot.id, "archive", "feature snapshot")} type="button">Archive snapshot</button>
                    <button className="ghost-button" onClick={() => void props.onLifecycle("snapshot", snapshot.id, "delete", "feature snapshot")} type="button">Delete snapshot</button>
                  </div>
                </div>
              ))}
            </section>
          </>
        )}
      </section>
      </div>
    </div>
  );
}

function FeatureSnapshotSummary({ snapshot }: { snapshot: MlFeatureSnapshotResponse }) {
  return (
    <div className={snapshot.rejectedRowCount > 0 ? "warning-message" : "success-message"}>
      <dl className="detail-list">
        <div><dt>Snapshot id</dt><dd className="mono-value">{snapshot.id}</dd></div>
        <div><dt>Family</dt><dd>{snapshot.modelFamily ?? "ANOMALY_DETECTION"}</dd></div>
        <div><dt>Horizon</dt><dd>{snapshot.horizonDays ? `${snapshot.horizonDays} days` : "Not required"}</dd></div>
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

function upsertFeatureSnapshot(snapshots: MlFeatureSnapshotResponse[], snapshot: MlFeatureSnapshotResponse) {
  return [snapshot, ...snapshots.filter((item) => item.id !== snapshot.id)];
}

function LabelsTab(props: {
  audit: MlLabelAudit[];
  datasets: MlDatasetSummary[];
  labels: MlLabel[];
  onAudit: (labelId: string) => Promise<void>;
  onChanged: (datasetVersionId: string) => Promise<void>;
  onDelete: (label: MlLabel) => Promise<void>;
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
  const {
    generationJob,
    generationStatusMessage,
    generationResult,
    generationActive,
    generateLabels,
    refreshGenerationStatus,
  } = useHorizonLabelGeneration({
    datasetVersionId,
    horizonDays,
    includePreventiveMaintenance,
    onChanged: props.onChanged,
    setError: props.setError,
  });

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
            <button className="ghost-button" disabled={generationActive} onClick={() => void generateLabels()} type="button">
              {generationActive ? "Generation active" : generationJob?.status === "FAILED" ? "Retry horizon labels" : "Generate horizon labels"}
            </button>
            {generationJob && <button className="ghost-button" onClick={() => void refreshGenerationStatus()} type="button">Check generation status</button>}
          </div>
          {generationStatusMessage && (
            <div className={generationJob?.status === "COMPLETED" ? "success-message" : "warning-message"}>{generationStatusMessage}</div>
          )}
          {generationJob && (
            <dl className="ml-quality-grid">
              <div><dt>Status</dt><dd>{generationJob.status}</dd></div>
              <div><dt>Job</dt><dd className="mono-value">{generationJob.jobId}</dd></div>
              <div><dt>Submitted</dt><dd>{new Date(generationJob.submittedAt).toLocaleString()}</dd></div>
              <div><dt>Progress</dt><dd>{generationJob.progress ?? 0}%</dd></div>
            </dl>
          )}
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
              <button className="ghost-button" onClick={() => void props.onDelete(item)} type="button">Delete</button>
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
  accountId: string;
  datasets: MlDatasetSummary[];
  featureSnapshots: MlFeatureSnapshotResponse[];
  runs: MlTrainingRun[];
  statusFilter: MlLifecycleStatusFilter;
  onChanged: () => Promise<void>;
  onFilterChange: (value: MlLifecycleStatusFilter) => Promise<void>;
  onLifecycle: (run: MlTrainingRun, action: ArchiveDeleteAction) => Promise<void>;
  setError: (value: string | null) => void;
}) {
  const readyDatasets = props.datasets.filter((dataset) => dataset.status === "READY");
  const [draft, setDraft, clearDraft] = useTrainingDraft(props.accountId);
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
    if (!datasetVersionId && readyDatasets[0]) {
      setDraft((current) => ({ ...current, datasetVersionId: readyDatasets[0].datasetVersionId }));
    }
  }, [datasetVersionId, readyDatasets, setDraft]);

  useEffect(() => {
    if (datasetVersionId && featureSnapshotId && !availableSnapshots.some((snapshot) => snapshot.id === featureSnapshotId)) {
      setDraft((current) => ({ ...current, featureSnapshotId: "" }));
    }
  }, [availableSnapshots, datasetVersionId, featureSnapshotId, setDraft]);

  useEffect(() => {
    if (datasetVersionId && !featureSnapshotId && compatibleSnapshots[0]) {
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
      clearDraft();
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
          <label>Dataset version<select value={datasetVersionId} onChange={(e) => setDraft((current) => ({ ...current, datasetVersionId: e.target.value }))}>{readyDatasets.map((dataset) => <option key={dataset.datasetVersionId} value={dataset.datasetVersionId}>{dataset.name} v{dataset.version}</option>)}</select></label>
          <label>
            Feature snapshot
            <select value={featureSnapshotId} onChange={(e) => setDraft((current) => ({ ...current, featureSnapshotId: e.target.value }))}>
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
              <label>Prediction horizon<input min="1" max="365" type="number" value={failureHorizonDays} onChange={(e) => setDraft((current) => ({ ...current, failureHorizonDays: Number(e.target.value) }))} /></label>
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
        <button className="primary-button" disabled={Boolean(trainingDisabledReason)} title={trainingDisabledReason ?? undefined}>Queue training</button>
      </form>
      <section className="panel">
        <div className="section-header">
          <div>
            <h2>Training runs</h2>
          </div>
          <StatusFilterSelect label="Training status filter" value={props.statusFilter} onChange={props.onFilterChange} />
        </div>
        {props.runs.length === 0 ? <div className="empty-state">No training runs yet.</div> : props.runs.map((run) => (
          <div className="ml-readout" key={run.id}>
            <strong>{run.modelFamily} / {run.algorithm}</strong>
            <span>{run.status} / {run.logSummary ?? run.failureReason ?? "Waiting for worker"}</span>
            <div className="action-row action-row-start">
              {["QUEUED", "RUNNING"].includes(run.status) && <button className="ghost-button" onClick={() => void cancelTrainingRun(run.id).then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Cancel</button>}
              {["FAILED", "CANCELED"].includes(run.status) && <button className="ghost-button" onClick={() => void retryTrainingRun(run.id).then(props.onChanged).catch((e: unknown) => props.setError(safeMlError(e)))} type="button">Retry</button>}
              {["SUCCEEDED", "FAILED", "CANCELED"].includes(run.status) && <button className="ghost-button" onClick={() => void props.onLifecycle(run, "archive")} type="button">Archive</button>}
              {["FAILED", "CANCELED"].includes(run.status) && <button className="ghost-button" onClick={() => void props.onLifecycle(run, "delete")} type="button">Delete</button>}
            </div>
          </div>
        ))}
      </section>
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
  const location = device.locationId ? ` / ${device.locationId}` : "";
  return `${device.name} / ${device.type} / ${device.status}${location} / ${device.id}`;
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
