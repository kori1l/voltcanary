import { FormEvent, useEffect, useState } from "react";
import { getLatestPrediction, PredictionResponse } from "../../../analytics/analyticsApi";
import {
  getMlDatasetVersion,
  listDeploymentPolicies,
  listFeatureSnapshots,
  listMlDatasets,
  listMlLabels,
  listMlModels,
  listTrainingRuns,
  MlDatasetDetail,
  MlDatasetSummary,
  MlDeploymentPolicy,
  MlFeatureSnapshotResponse,
  MlLabel,
  MlLabelAudit,
  MlLifecycleStatusFilter,
  MlModelVersion,
  MlTrainingRun,
  getMlLabelAudit,
} from "../../../analytics/mlModuleApi";
import {
  clearDeviceMlSettings,
  getAccountMlSettings,
  getDeviceMlSettings,
  MlSettingsResponse,
  MlSettingsValues,
  safeMlError,
  saveAccountMlSettings,
  saveDeviceMlSettings,
} from "../../../analytics/mlSettingsApi";
import { Device, DeviceType, listDevices, listDeviceTypes } from "../../../devices/devicesApi";

const initial: MlSettingsValues = {
  mlEnabled: true,
  predictionMode: "MODEL_OR_BASELINE_HEURISTIC",
  confidenceWarningThreshold: 0.6,
  maintenanceBehavior: "REDUCE_CONFIDENCE",
  explanationsVisible: true,
};

export function useMlWorkspaceData({
  datasetStatusFilter,
  trainingStatusFilter,
  modelStatusFilter,
  policyStatusFilter,
}: {
  datasetStatusFilter: MlLifecycleStatusFilter;
  trainingStatusFilter: MlLifecycleStatusFilter;
  modelStatusFilter: MlLifecycleStatusFilter;
  policyStatusFilter: MlLifecycleStatusFilter;
}) {
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
  const [devices, setDevices] = useState<Device[]>([]);
  const [deviceTypes, setDeviceTypes] = useState<DeviceType[]>([]);
  const [featureSnapshots, setFeatureSnapshots] = useState<MlFeatureSnapshotResponse[]>([]);
  const [labels, setLabels] = useState<MlLabel[]>([]);
  const [labelAudit, setLabelAudit] = useState<MlLabelAudit[]>([]);

  useEffect(() => {
    void loadAccount();
    void refreshMlWorkspace();
    void loadSelectors();
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
    const [datasetResult, snapshotResult, trainingResult, modelResult, policyResult] = await Promise.allSettled([
      listMlDatasets(datasetStatusFilter),
      listFeatureSnapshots(),
      listTrainingRuns(trainingStatusFilter),
      listMlModels(modelStatusFilter),
      listDeploymentPolicies(policyStatusFilter),
    ]);
    if (datasetResult.status === "fulfilled") {
      setDatasets(datasetResult.value);
    }
    if (trainingResult.status === "fulfilled") {
      setTrainingRuns(trainingResult.value);
    }
    if (snapshotResult.status === "fulfilled" && Array.isArray(snapshotResult.value)) {
      setFeatureSnapshots(snapshotResult.value);
    }
    if (modelResult.status === "fulfilled") {
      setModels(modelResult.value);
    }
    if (policyResult.status === "fulfilled") {
      setPolicies(policyResult.value);
    }
  }

  async function loadSelectors() {
    const [deviceResult, deviceTypeResult] = await Promise.allSettled([
      listDevices({ limit: 100 }),
      listDeviceTypes(),
    ]);
    if (deviceResult.status === "fulfilled") {
      setDevices(deviceResult.value);
    }
    if (deviceTypeResult.status === "fulfilled") {
      setDeviceTypes(deviceTypeResult.value);
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

  async function loadLabelAudit(labelId: string) {
    setError(null);
    try {
      setLabelAudit(await getMlLabelAudit(labelId));
    } catch (e) {
      setError(safeMlError(e));
    }
  }

  async function refreshLabels(datasetVersionId: string) {
    setLabels(await listMlLabels(datasetVersionId));
  }

  const set = <K extends keyof MlSettingsValues>(key: K, value: MlSettingsValues[K]) =>
    setValues((v) => ({ ...v, [key]: value }));

  return {
    values,
    setValues,
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
  };
}
