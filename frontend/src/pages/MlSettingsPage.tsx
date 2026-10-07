import { MlImportTab } from "../features/ml-settings/components/MlImportTab";
import { useMemo, useState } from "react";
import { archiveFeatureSnapshot, archiveMlDatasetVersion, archiveTrainingRun, createInternalTelemetryDataset, deleteDeploymentPolicy, deleteFeatureSnapshot, deleteMlDatasetVersion, deleteMlLabel, deleteMlModel, deleteTrainingRun, disableDeploymentPolicy, generateFeatureSnapshot, listMlLabels, MlLifecycleStatusFilter, previewDeploymentPolicyLifecycle, previewFeatureSnapshotLifecycle, previewMlDatasetVersionLifecycle, previewMlLabelLifecycle, previewMlModelLifecycle, previewTrainingRunLifecycle } from "../analytics/mlModuleApi";
import { safeMlError } from "../analytics/mlSettingsApi";
import { useMlWorkspaceData } from "../features/ml-settings/hooks/useMlWorkspaceData";
import { formatImpactDependencies, requestLifecycleReason } from "../features/ml-settings/model/mlLifecycleActions";
import { MlModelsTab, MlMonitoringTab, MlPoliciesTab } from "../features/ml-settings/components/MlOperationalTabs";
import { SectionFeedback } from "../components/SectionFeedback";
import { useActionConfirmation } from "../components/useActionConfirmation";

import { WorkflowMode, buildPipelineSteps, upsertFeatureSnapshot } from "../features/ml-settings/model/mlConsolePresentation";
import { SettingsTab } from "../features/ml-settings/components/MlSettingsTab";
import { WorkflowPipelineRail } from "../features/ml-settings/components/MlPipelineRail";
import { DatasetsTab } from "../features/ml-settings/components/MlDatasetsTab";
import { LabelsTab } from "../features/ml-settings/components/MlLabelsTab";
import { TrainingTab } from "../features/ml-settings/components/MlTrainingTab";
type MlTab = "settings" | "datasets" | "import" | "labels" | "training" | "models" | "policies" | "monitoring";
export function MlSettingsPage() {
  const confirmation = useActionConfirmation();
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
      {confirmation.dialog}
      {message && <div className="success-message">{message}</div>}
      {error && <div className="error-message">{error}</div>}
      {workspace.sections.filter(({ resource }) => resource.error).map(({ label, resource }) => <SectionFeedback key={label} resource={resource} label={label} onRetry={() => void refreshMlWorkspace()} />)}
      <WorkflowPipelineRail mode={workflowMode} onModeChange={setWorkflowMode} steps={pipelineSteps} onStep={(id) => setActiveTab(({ dataset: "datasets", labels: "labels", snapshot: "datasets", training: "training", model: "models", policy: "policies", monitoring: "monitoring" } as Record<string, MlTab>)[id] ?? "datasets")} />
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
          datasetsAvailable={workspace.dataAvailable.datasets}
          datasets={datasets}
          devices={devices}
          detail={datasetDetail}
          featureSnapshots={featureSnapshots}
          workflowMode={workflowMode}
          accountId={effective?.accountId ?? "unknown-account"}
          statusFilter={datasetStatusFilter}
          onFilterChange={async (value) => {
            setDatasetStatusFilter(value);
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
              setFeatureSnapshots(upsertFeatureSnapshot(featureSnapshots, snapshot));
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
              const reason = await requestLifecycleReason(confirmation.confirm, action, label, impact);
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
        <MlImportTab
          onImported={async (summary) => {
            setMessage(summary);
            await refreshMlWorkspace();
          }}
          setError={setError}
        />
      )}
      {activeTab === "labels" && (
        <LabelsTab
          devices={devices}
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
              const reason = await requestLifecycleReason(confirmation.confirm, "delete", `${label.label} label`, impact);
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
          runsAvailable={workspace.dataAvailable.trainingRuns}
          datasets={datasets}
          accountId={effective?.accountId ?? "unknown-account"}
          featureSnapshots={featureSnapshots}
          runs={trainingRuns}
          statusFilter={trainingStatusFilter}
          onFilterChange={async (value) => {
            setTrainingStatusFilter(value);
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
              const reason = await requestLifecycleReason(confirmation.confirm, action, `${run.modelFamily} training run`, impact);
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
          modelsAvailable={workspace.dataAvailable.models}
          models={models}
          statusFilter={modelStatusFilter}
          onFilterChange={async (value) => {
            setModelStatusFilter(value);
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
              const reason = await requestLifecycleReason(confirmation.confirm, "delete", `${model.modelKey} v${model.version}`, impact);
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
          policiesAvailable={workspace.dataAvailable.policies}
          models={models}
          policies={policies}
          devices={devices}
          deviceTypes={deviceTypes}
          statusFilter={policyStatusFilter}
          onFilterChange={async (value) => {
            setPolicyStatusFilter(value);
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
              const reason = await requestLifecycleReason(confirmation.confirm, action, `${policy.scope} policy`, impact);
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
          dataAvailable={workspace.dataAvailable}
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
