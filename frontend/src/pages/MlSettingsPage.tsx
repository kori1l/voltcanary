import { MlImportTab } from "../features/ml-settings/components/MlImportTab";
import { useEffect, useState } from "react";
import { archiveFeatureSnapshot, archiveMlDatasetVersion, archiveTrainingRun, createInternalTelemetryDataset, deleteDeploymentPolicy, deleteFeatureSnapshot, deleteMlDatasetVersion, deleteMlLabel, deleteMlModel, deleteTrainingRun, disableDeploymentPolicy, generateFeatureSnapshot, MlLifecycleStatusFilter, previewDeploymentPolicyLifecycle, previewFeatureSnapshotLifecycle, previewMlDatasetVersionLifecycle, previewMlLabelLifecycle, previewMlModelLifecycle, previewTrainingRunLifecycle } from "../analytics/mlModuleApi";
import { safeMlError } from "../analytics/mlSettingsApi";
import { useMlWorkspaceData } from "../features/ml-settings/hooks/useMlWorkspaceData";
import { MlModelsTab, MlMonitoringTab, MlPoliciesTab } from "../features/ml-settings/components/MlOperationalTabs";
import { SectionFeedback } from "../components/SectionFeedback";
import { useActionConfirmation } from "../components/useActionConfirmation";

import { WorkflowMode, upsertFeatureSnapshot } from "../features/ml-settings/model/mlConsolePresentation";
import { SettingsTab } from "../features/ml-settings/components/MlSettingsTab";
import { WorkflowPipelineRail } from "../features/ml-settings/components/MlPipelineRail";
import { DatasetsTab } from "../features/ml-settings/components/MlDatasetsTab";
import { LabelsTab } from "../features/ml-settings/components/MlLabelsTab";
import { TrainingTab } from "../features/ml-settings/components/MlTrainingTab";
import { applyPipelineEvidence } from "../features/ml-settings/model/pipelineEvidence";
import { ResourceErrors } from "../components/ResourceErrors";
import { ReadRefresh } from "../components/ReadRefresh";
import { initialPipelineContext, selectedPipelineSteps, type PipelineContext, type PipelineEvidence } from "../features/ml-settings/model/pipelineContext";
import { PipelineContextSelection } from "../features/ml-settings/components/PipelineContextSelection";
import { ConsoleRouteContext, useConsoleQuery } from "../app/useConsoleQuery";
import { MlSectionPicker, MlWorkspaceSections, type MlSection } from "../features/ml-settings/components/MlWorkspaceSections";
import { useMlLifecycleCommands } from "../features/ml-settings/hooks/useMlLifecycleCommands";
import { CommandFeedback } from "../components/CommandFeedback";
import { useTaskEvidence } from "../aiAgent/taskEvidence";
type MlTab = MlSection;
export function MlSettingsPage() {
  return <ConsoleRouteContext fallbackPath="/settings/ml"><MlSettingsScreen /></ConsoleRouteContext>;
}
function MlSettingsScreen() {
  const query = useConsoleQuery();
  const confirmation = useActionConfirmation();
  const [activeTab, setActiveTab] = query.state<MlTab>("tab", "settings");
  const [workflowMode, setWorkflowMode] = query.state<WorkflowMode>("family", "ANOMALY_DETECTION");
  const pipelineSelection: PipelineContext = { ...initialPipelineContext(), datasetVersionId: query.params.get("datasetVersionId") ?? "", snapshotId: query.params.get("snapshotId") ?? "", runId: query.params.get("runId") ?? "", modelId: query.params.get("modelId") ?? "", policyId: query.params.get("policyId") ?? "", family: workflowMode, horizonDays: query.params.has("horizonDays") ? Number(query.params.get("horizonDays")) : null };
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
    loadAccount: loadAccountSettings,
    loadDevice: loadDeviceSettings,
    refreshMlWorkspace,
    save,
    clearOverride,
    loadDatasetDetail,
    loadLabelAudit,
    refreshLabels,
    set,
  } = workspace;
  const lifecycle = useMlLifecycleCommands(confirmation.confirm, refreshMlWorkspace, setError, setMessage);
  const pipelineContext: PipelineContext = { ...pipelineSelection, family: workflowMode, accountId: effective?.accountId ?? null, deviceId: deviceMode ? effective?.deviceId ?? null : null, deviceType: deviceMode ? devices.find((device) => device.id === effective?.deviceId)?.type ?? null : null };
  let datasetDeviceIds: string[] | null = null;
  if (datasetDetail?.dataset?.datasetVersionId === pipelineContext.datasetVersionId) {
    try { const rows = datasetDetail.lineage.map((lineage) => JSON.parse(lineage.selectedDevicesJson) as unknown); if (rows.length && rows.every((row) => Array.isArray(row) && row.every((id) => typeof id === "string"))) datasetDeviceIds = rows.flat() as string[]; } catch { /* Unknown legacy scope remains unverifiable. */ }
  }
  const pipelineEvidence: PipelineEvidence = { datasets, snapshots: featureSnapshots, runs: trainingRuns, models, policies, prediction, datasetDeviceIds, labels: workspace.labelsDatasetVersionId === pipelineContext.datasetVersionId && ["ready", "empty"].includes(workspace.labelResource.status) ? labels : null };
  const pipelineSteps = applyPipelineEvidence(selectedPipelineSteps(pipelineContext, pipelineEvidence), [...workspace.sections, ...(workflowMode === "FAILURE_PREDICTION" ? [{ id: "labels", resource: workspace.labelResource }] : [])]);
  const selectedDataset = datasets.find((dataset) => dataset.datasetVersionId === pipelineContext.datasetVersionId);
  useTaskEvidence({ names: { dataset: selectedDataset?.name, device: devices.find((device) => device.id === pipelineContext.deviceId)?.name }, interval: selectedDataset ? { from: selectedDataset.sourceWindowStart, to: selectedDataset.sourceWindowEnd } : undefined });
  function selectPipeline(value: Partial<PipelineContext>, replace = false) {
    const patch: Record<string, string | number | null> = {};
    for (const key of ["datasetVersionId", "snapshotId", "runId", "modelId", "policyId", "family", "horizonDays"] as const) if (key in value) patch[key] = value[key] ?? null;
    query.update(patch, replace);
  }
  async function loadAccount() { if (query.params.get("scope") !== "device") await loadAccountSettings(); else query.update({ scope: "account", deviceId: null }); }
  async function loadDevice() { if (!deviceId.trim()) { setError("Device ID is required"); return; } if (query.params.get("scope") === "device" && query.params.get("deviceId") === deviceId.trim()) await loadDeviceSettings(deviceId); else query.update({ scope: "device", deviceId: deviceId.trim() }); }
  useEffect(() => {
    const selected = query.params.get("deviceId");
    if (query.params.get("scope") === "device" && selected) { setDeviceId(selected); void loadDeviceSettings(selected); }
    else void loadAccountSettings();
  }, [query.params.get("scope"), query.params.get("deviceId")]);
  useEffect(() => {
    void refreshLabels(pipelineSelection.datasetVersionId);
    if (pipelineSelection.datasetVersionId) void loadDatasetDetail(pipelineSelection.datasetVersionId);
    else setDatasetDetail(null);
  }, [pipelineSelection.datasetVersionId]);
  useEffect(() => {
    if (!pipelineSelection.datasetVersionId && datasets[0]) selectPipeline({ datasetVersionId: datasets[0].datasetVersionId }, true);
  }, [datasets, pipelineSelection.datasetVersionId]);
  useEffect(() => {
    if (!pipelineSelection.snapshotId && pipelineSelection.datasetVersionId) {
      const snapshot = featureSnapshots.find((item) => item.datasetVersionId === pipelineSelection.datasetVersionId && item.modelFamily === workflowMode && (workflowMode !== "FAILURE_PREDICTION" || item.horizonDays === pipelineSelection.horizonDays));
      if (snapshot) selectPipeline({ snapshotId: snapshot.id }, true);
    }
  }, [featureSnapshots, pipelineSelection.datasetVersionId, pipelineSelection.snapshotId, pipelineSelection.horizonDays, workflowMode]);

  return (
    <>
      <header className="page-header">
        <h1>ML workspace</h1>
        <p>Prediction policy, training assets, deployment, and read-only model readiness.</p>
      </header>
      <MlSectionPicker value={activeTab} onChange={setActiveTab} />
      {confirmation.dialog}
      <CommandFeedback outcomes={lifecycle.outcomes} onRetryRead={lifecycle.retryRead} />
      {lifecycle.outcomes["snapshot:generate"]?.phase === "unknown-result" && pipelineContext.snapshotId ? <button type="button" className="ghost-button" onClick={() => { const record = featureSnapshots.find((snapshot) => snapshot.id === pipelineContext.snapshotId); if (record) lifecycle.reconcile("snapshot:generate", record); }}>Confirm reviewed snapshot {pipelineContext.snapshotId}</button> : null}
      <ReadRefresh scope={JSON.stringify([datasetStatusFilter, trainingStatusFilter, modelStatusFilter, policyStatusFilter])} refresh={refreshMlWorkspace} />
      {message && <div className="success-message" role="status">{message}</div>}
      {error && <div className="error-message" role="alert">{error}</div>}
      <ResourceErrors sections={workspace.sections} />
      <PipelineContextSelection context={pipelineContext} evidence={pipelineEvidence} onChange={selectPipeline} />
      {pipelineContext.family === "FAILURE_PREDICTION" && pipelineContext.datasetVersionId ? <SectionFeedback resource={workspace.labelResource} label="selected dataset labels" onRetry={() => void refreshLabels(pipelineContext.datasetVersionId)} /> : null}
      <WorkflowPipelineRail mode={workflowMode} onModeChange={setWorkflowMode} steps={pipelineSteps} onStep={(id) => setActiveTab(({ dataset: "datasets", labels: "labels", snapshot: "datasets", training: "training", model: "models", policy: "policies", monitoring: "monitoring" } as Record<string, MlTab>)[id] ?? "datasets")} />
      <MlWorkspaceSections value={activeTab} onChange={setActiveTab}>

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
          refreshRecords={refreshMlWorkspace}
          isLifecyclePending={(kind, id, action) => lifecycle.isPending(`${kind}:${id}:${action}`)}
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
              setMessage(`Internal telemetry dataset ${response.status}: ${response.acceptedRows} accepted / ${response.rejectedRows} rejected`);
              selectPipeline({ datasetVersionId: response.datasetVersionId, snapshotId: "", runId: "", modelId: "", policyId: "" });
              return response;
            } catch (e) {
              setError(safeMlError(e));
              throw e;
            } finally {
              setBusy(false);
            }
          }}
          isGeneratingSnapshot={lifecycle.isBlocked("snapshot:generate")}
          onGenerateFeatureSnapshot={async (body) => {
            await lifecycle.run("snapshot:generate", { label: "Generate feature snapshot", nonIdempotent: true,
              mutate: () => generateFeatureSnapshot(body),
              onCommitted: (snapshot) => { selectPipeline({ datasetVersionId: body.datasetVersionId, snapshotId: snapshot.id, family: snapshot.modelFamily === "FAILURE_PREDICTION" ? "FAILURE_PREDICTION" : "ANOMALY_DETECTION", horizonDays: snapshot.horizonDays, runId: "", modelId: "", policyId: "" }); setFeatureSnapshots(upsertFeatureSnapshot(featureSnapshots, snapshot)); setMessage("Feature snapshot generated"); }, refresh: refreshMlWorkspace });
          }}
          onLifecycle={async (kind, id, action, label) => {
            await lifecycle.execute(`${kind}:${id}:${action}`, id, action, label,
              () => kind === "dataset" ? previewMlDatasetVersionLifecycle(id, action) : previewFeatureSnapshotLifecycle(id, action),
              (reason) => kind === "dataset" ? (action === "archive" ? archiveMlDatasetVersion(id, reason) : deleteMlDatasetVersion(id, reason)) : (action === "archive" ? archiveFeatureSnapshot(id, reason) : deleteFeatureSnapshot(id, reason)),
              `${label} ${action}d`, () => { if (kind === "dataset") setDatasetDetail(null); else setFeatureSnapshots((current) => current.filter((snapshot) => snapshot.id !== id)); });
          }}
          onSelect={async (datasetVersionId) => {
            if (datasetVersionId === pipelineSelection.datasetVersionId) await loadDatasetDetail(datasetVersionId);
            else selectPipeline({ datasetVersionId, snapshotId: "", runId: "", modelId: "", policyId: "" });
          }}
          onWorkflowModeChange={setWorkflowMode}
        />
      )}
      {activeTab === "import" && (
        <MlImportTab
          existingDatasets={datasets}
          refreshRecords={refreshMlWorkspace}
          onImported={async (summary) => {
            setMessage(summary);
            if (!await refreshMlWorkspace()) throw new Error("Import committed; workspace reads are unavailable.");
          }}
          setError={setError}
        />
      )}
      {activeTab === "labels" && (
        <LabelsTab
          onConfirmed={() => setMessage("Label review updated")}
          selectedDatasetVersionId={pipelineContext.datasetVersionId}
          selectedHorizonDays={pipelineContext.horizonDays}
          onSelectionChange={(datasetVersionId) => selectPipeline({ datasetVersionId, snapshotId: "", runId: "", modelId: "", policyId: "" })}
          onHorizonChange={(horizonDays) => selectPipeline({ horizonDays })}
          devices={devices}
          audit={labelAudit}
          datasets={datasets}
          labels={labels}
          onAudit={loadLabelAudit}
          onChanged={async (datasetVersionId) => {
            if (await refreshLabels(datasetVersionId) === null) throw new Error("Label records unavailable. Retry this read.");
          }}
          isDeletePending={(id) => lifecycle.isPending(`label:${id}:delete`)}
          onDelete={async (label) => {
            await lifecycle.execute(`label:${label.id}:delete`, label.id, "delete", `${label.label} label`, () => previewMlLabelLifecycle(label.id, "delete"), (reason) => deleteMlLabel(label.id, reason), "Label deleted", () => setLabels(labels.filter((item) => item.id !== label.id)), () => refreshLabels(label.datasetVersionId));
          }}
          setError={setError}
        />
      )}
      {activeTab === "training" && !effective?.accountId ? <p role="status">Loading account context before editing the training draft…</p> : null}
      {activeTab === "training" && effective?.accountId && (
        <TrainingTab
          onConfirmed={() => setMessage("Training workflow updated")}
          isLifecyclePending={(id, action) => lifecycle.isPending(`run:${id}:${action}`)}
          pipelineContext={pipelineContext}
          onPipelineChange={selectPipeline}
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
            if (!await refreshMlWorkspace()) throw new Error("Saved command; workspace reads are unavailable.");
          }}
          onLifecycle={async (run, action) => {
            await lifecycle.execute(`run:${run.id}:${action}`, run.id, action, `${run.modelFamily} training run`, () => previewTrainingRunLifecycle(run.id, action), (reason) => action === "archive" ? archiveTrainingRun(run.id, reason) : deleteTrainingRun(run.id, reason), `Training run ${action}d`);
          }}
          setError={setError}
        />
      )}
      {activeTab === "models" && (
        <MlModelsTab
          onConfirmed={() => setMessage("Model registry updated")}
          isDeletePending={(id) => lifecycle.isPending(`model:${id}:delete`)}
          modelsAvailable={workspace.dataAvailable.models}
          models={models}
          statusFilter={modelStatusFilter}
          onFilterChange={async (value) => {
            setModelStatusFilter(value);
          }}
          onChanged={async () => {
            if (!await refreshMlWorkspace()) throw new Error("Saved command; workspace reads are unavailable.");
          }}
          onDelete={async (model) => {
            await lifecycle.execute(`model:${model.id}:delete`, model.id, "delete", `${model.modelKey} v${model.version}`, () => previewMlModelLifecycle(model.id, "delete"), (reason) => deleteMlModel(model.id, reason), "Model deleted");
          }}
          setError={setError}
        />
      )}
      {activeTab === "policies" && (
        <MlPoliciesTab
          onConfirmed={() => setMessage("Deployment policy saved")}
          isLifecyclePending={(id, action) => lifecycle.isPending(`policy:${id}:${action}`)}
          selectedModelId={pipelineContext.modelId}
          selectedDeviceId={pipelineContext.deviceId}
          onModelSelected={(modelId) => selectPipeline({ modelId, policyId: "" })}
          onPolicySelected={(policyId) => selectPipeline({ policyId })}
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
            if (!await refreshMlWorkspace()) throw new Error("Saved command; workspace reads are unavailable.");
          }}
          onLifecycle={async (policy, action) => {
            await lifecycle.execute(`policy:${policy.id}:${action}`, policy.id, action, `${policy.scope} policy`, () => previewDeploymentPolicyLifecycle(policy.id, action), (reason) => action === "disable" ? disableDeploymentPolicy(policy.id, reason) : deleteDeploymentPolicy(policy.id, reason), `Deployment policy ${action}d`);
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
      </MlWorkspaceSections>
    </>
  );
}
