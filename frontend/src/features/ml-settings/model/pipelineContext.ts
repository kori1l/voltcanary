import type { PredictionResponse } from "../../../analytics/analyticsApi";
import type { MlDatasetSummary, MlDeploymentPolicy, MlFeatureSnapshotResponse, MlLabel, MlModelVersion, MlTrainingRun } from "../../../analytics/mlModuleApi";
import type { DatasetPipelineStep, DatasetWorkbenchModelFamily } from "../../ml-datasets/model/datasetWorkbench.types";

export interface PipelineContext {
  accountId: string | null;
  deviceId: string | null;
  deviceType: string | null;
  datasetVersionId: string;
  family: DatasetWorkbenchModelFamily;
  horizonDays: number | null;
  snapshotId: string;
  runId: string;
  modelId: string;
  policyId: string;
}
export const initialPipelineContext = (): PipelineContext => ({ accountId: null, deviceId: null, deviceType: null, datasetVersionId: "", family: "ANOMALY_DETECTION", horizonDays: null, snapshotId: "", runId: "", modelId: "", policyId: "" });
export interface PipelineEvidence {
  datasets: MlDatasetSummary[];
  snapshots: MlFeatureSnapshotResponse[];
  runs: MlTrainingRun[];
  models: MlModelVersion[];
  policies: MlDeploymentPolicy[];
  labels: MlLabel[] | null;
  datasetDeviceIds: string[] | null;
  prediction: PredictionResponse | null;
}

export function selectedPipelineSteps(context: PipelineContext, evidence: PipelineEvidence): DatasetPipelineStep[] {
  const step = (id: string, label: string, status: DatasetPipelineStep["status"], detail: string): DatasetPipelineStep => ({ id, label, status, detail });
  const dataset = evidence.datasets.find((item) => item.datasetVersionId === context.datasetVersionId);
  const snapshot = evidence.snapshots.find((item) => item.id === context.snapshotId);
  const run = evidence.runs.find((item) => item.id === context.runId);
  const model = evidence.models.find((item) => item.id === context.modelId);
  const policy = evidence.policies.find((item) => item.id === context.policyId);
  const failure = context.family === "FAILURE_PREDICTION";
  const accountMatches = (accountId: string | null | undefined) => Boolean(context.accountId && accountId === context.accountId);
  const horizonValid = !failure || Boolean(context.horizonDays && context.horizonDays > 0);
  let runHorizonCompatible = true;
  if (failure && run?.hyperparametersJson) {
    try { const parameters = JSON.parse(run.hyperparametersJson) as { failureHorizonDays?: number }; if (parameters.failureHorizonDays !== undefined) runHorizonCompatible = parameters.failureHorizonDays === context.horizonDays; } catch { runHorizonCompatible = false; }
  }
  const datasetCompatible = Boolean(dataset && accountMatches(dataset.accountId) && (!context.deviceId || evidence.datasetDeviceIds?.includes(context.deviceId)));
  const snapshotCompatible = Boolean(snapshot && datasetCompatible && dataset?.status === "READY" && snapshot.datasetVersionId === context.datasetVersionId && accountMatches(snapshot.accountId) && snapshot.modelFamily === context.family && horizonValid && (!failure || snapshot.horizonDays === context.horizonDays) && snapshot.lifecycleStatus === "ACTIVE" && snapshot.rowCount > 0 && snapshot.artifactUri);
  const runCompatible = Boolean(run && snapshotCompatible && runHorizonCompatible && run.datasetVersionId === context.datasetVersionId && run.featureSnapshotId === context.snapshotId && accountMatches(run.accountId) && run.modelFamily === context.family);
  const modelCompatible = Boolean(model && runCompatible && model.datasetVersionId === context.datasetVersionId && model.featureSnapshotId === context.snapshotId && model.trainingRunId === context.runId && accountMatches(model.accountId) && model.family === context.family && (!context.deviceId || Boolean(context.deviceType && model.compatibleDeviceTypes?.includes(context.deviceType))));
  const policyScopeMatches = Boolean(policy && accountMatches(policy.accountId) && (context.deviceId ? policy.scope === "DEVICE" && policy.deviceId === context.deviceId || policy.scope === "DEVICE_TYPE" && policy.deviceType === context.deviceType || policy.scope === "ACCOUNT" : policy.scope === "ACCOUNT"));
  const approved = Boolean(modelCompatible && model && run?.status === "SUCCEEDED" && run.artifactUri && run.completedAt && ["APPROVED", "ACTIVE"].includes(model.status) && model.approvedAt && model.artifactUri);
  const policyCompatible = Boolean(policyScopeMatches && approved && policy?.modelVersionId === context.modelId);
  const labels = evidence.labels?.filter((label) => label.datasetVersionId === context.datasetVersionId && accountMatches(label.accountId) && label.horizonDays === context.horizonDays && (!context.deviceId || label.deviceId === context.deviceId));
  const positive = labels?.some((label) => ["CONFIRMED_FAILURE", "CORRECTIVE_MAINTENANCE"].includes(label.label));
  const snapshotDetail = !snapshot ? "Select a feature snapshot" : !snapshot.modelFamily ? "Legacy snapshot family is unknown" : snapshot.modelFamily !== context.family ? `Family mismatch: ${snapshot.modelFamily}` : failure && snapshot.horizonDays !== context.horizonDays ? `Horizon mismatch: snapshot ${snapshot.horizonDays ?? "unknown"} days; selected ${context.horizonDays ?? "unknown"} days` : !snapshotCompatible ? "Snapshot dataset, account, device scope or lifecycle is incompatible or unknown" : "Selected snapshot lineage verified";
  const prediction = evidence.prediction;
  const modelSnapshot = evidence.snapshots.find((item) => item.id === model?.featureSnapshotId);
  const modelHorizonMismatch = failure && modelSnapshot && modelSnapshot.horizonDays !== context.horizonDays;
  const observed = Boolean(prediction && context.deviceId && prediction.deviceId === context.deviceId && prediction.modelVersionId === context.modelId && prediction.modelFamily === context.family && (!failure || prediction.failureHorizonDays === context.horizonDays) && ["MODEL", "TRAINED_MODEL"].includes(prediction.predictionStatus));
  return [
    step("dataset", "Dataset", !dataset ? "blocked" : !datasetCompatible ? "review" : dataset.status === "READY" ? "ready" : "review", !dataset ? "Select a dataset version" : !datasetCompatible ? "Dataset account or device lineage is incompatible or unknown" : `${dataset.status} · selected version`),
    step("labels", "Labels", !failure ? "ready" : !horizonValid || !datasetCompatible ? "review" : evidence.labels === null ? "unavailable" : positive ? "ready" : "blocked", !failure ? "Not required for anomaly detection" : !horizonValid ? "Choose a positive failure horizon" : evidence.labels === null ? "Load labels for the selected dataset to verify" : `${labels?.length ?? 0} compatible labels; failure positives ${positive ? "present" : "missing"}`),
    step("snapshot", "Feature snapshot", snapshotCompatible && dataset?.status === "READY" && Boolean(snapshot?.rowCount && snapshot.artifactUri) ? "ready" : snapshot ? "review" : "blocked", snapshotDetail),
    step("training", "Training", !run ? "blocked" : !runCompatible ? "review" : run.status === "QUEUED" ? "queued" : run.status === "RUNNING" ? "running" : run.status === "SUCCEEDED" && run.artifactUri && run.completedAt ? "ready" : "review", !run ? "Select a training run" : !runCompatible ? "Training lineage does not match the selected snapshot, dataset, family or account" : `${run.status}${run.status === "SUCCEEDED" && (!run.artifactUri || !run.completedAt) ? " · completion artifact metadata unknown" : " · selected run"}`),
    step("model", "Model approval", approved && run?.status === "SUCCEEDED" ? "ready" : model ? "review" : "blocked", !model ? "Select an evaluated model" : modelHorizonMismatch ? `Model horizon mismatch: ${modelSnapshot.horizonDays ?? "unknown"} days; selected ${context.horizonDays ?? "unknown"} days` : !modelCompatible ? "Model training lineage, family or scope is incompatible or unknown" : `${model.status} · server approval ${model.approvedAt ? "recorded" : "not recorded"}`),
    step("policy", "Deployment configuration", policyCompatible && policy?.mode !== "DISABLED" ? "ready" : "blocked", !policy ? "Select a deployment policy" : !policyCompatible ? "Policy model or deployment scope does not match selected approved lineage" : `${policy.mode} configuration${policy.mode === "DISABLED" ? " · deployment disabled" : " · runtime inference remains independent"}`),
    step("monitoring", "Observed inference", observed ? "ready" : "review", observed ? `Selected model inference recorded at ${prediction!.createdAt}` : "No matching observed inference; approval and configured policy do not establish runtime success"),
  ];
}
