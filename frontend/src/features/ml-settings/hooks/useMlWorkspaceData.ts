import { FormEvent, useEffect, useRef, useState } from "react";
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
import { useSectionResource } from "../../../components/useSectionResource";

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
  const scopeGeneration = useRef(0);
  const detailGeneration = useRef(0);
  const labelsGeneration = useRef(0);
  const auditGeneration = useRef(0);
  useEffect(() => () => { scopeGeneration.current++; detailGeneration.current++; labelsGeneration.current++; auditGeneration.current++; }, []);
  const [values, setValues] = useState(initial);
  const [effective, setEffective] = useState<MlSettingsResponse | null>(null);
  const [deviceId, setDeviceId] = useState("");
  const [deviceMode, setDeviceMode] = useState(false);
  const [prediction, setPrediction] = useState<PredictionResponse | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const datasetSection = useSectionResource<MlDatasetSummary[]>(datasetStatusFilter);
  const datasets = datasetSection.data ?? [];
  const setDatasets = datasetSection.setData;
  const [datasetDetail, setDatasetDetail] = useState<MlDatasetDetail | null>(null);
  const trainingSection = useSectionResource<MlTrainingRun[]>(trainingStatusFilter);
  const trainingRuns = trainingSection.data ?? [];
  const setTrainingRuns = trainingSection.setData;
  const modelSection = useSectionResource<MlModelVersion[]>(modelStatusFilter);
  const models = modelSection.data ?? [];
  const setModels = modelSection.setData;
  const policySection = useSectionResource<MlDeploymentPolicy[]>(policyStatusFilter);
  const policies = policySection.data ?? [];
  const setPolicies = policySection.setData;
  const [devices, setDevices] = useState<Device[]>([]);
  const [deviceTypes, setDeviceTypes] = useState<DeviceType[]>([]);
  const snapshotSection = useSectionResource<MlFeatureSnapshotResponse[]>();
  const featureSnapshots = snapshotSection.data ?? [];
  const setFeatureSnapshots = snapshotSection.setData;
  const [labels, setLabels] = useState<MlLabel[]>([]);
  const [labelAudit, setLabelAudit] = useState<MlLabelAudit[]>([]);

  useEffect(() => {
    void loadAccount();
    void loadSelectors();
  }, []);

  useEffect(() => { void refreshMlWorkspace(); }, [datasetStatusFilter, trainingStatusFilter, modelStatusFilter, policyStatusFilter]);

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
    const generation = ++scopeGeneration.current;
    setPrediction(null);
    setStatusError(null);
    setBusy(true);
    setError(null);
    try {
      const response = await getAccountMlSettings();
      if (generation !== scopeGeneration.current) return;
      adopt(response);
      setDeviceMode(false);
    } catch (e) {
      if (generation !== scopeGeneration.current) return;
      setError(safeMlError(e));
    } finally {
      if (generation === scopeGeneration.current) setBusy(false);
    }
  }

  async function loadDevice() {
    const generation = ++scopeGeneration.current;
    const requestedDeviceId = deviceId.trim();
    setPrediction(null);
    if (!deviceId.trim()) {
      setError("Device ID is required");
      return;
    }
    setBusy(true);
    setError(null);
    setStatusError(null);
    try {
      const response = await getDeviceMlSettings(requestedDeviceId);
      if (generation !== scopeGeneration.current) return;
      adopt(response);
      setDeviceMode(true);
      getLatestPrediction(requestedDeviceId)
        .then((response) => { if (generation === scopeGeneration.current) setPrediction(response); })
        .catch((e) => {
          if (generation !== scopeGeneration.current) return;
          setPrediction(null);
          setStatusError(safeMlError(e));
        });
    } catch (e) {
      if (generation !== scopeGeneration.current) return;
      setError(safeMlError(e));
    } finally {
      if (generation === scopeGeneration.current) setBusy(false);
    }
  }

  async function refreshMlWorkspace() {
    await Promise.allSettled([
      datasetSection.load(() => listMlDatasets(datasetStatusFilter), (data) => !data.length),
      snapshotSection.load(async () => {
        const response = await listFeatureSnapshots();
        if (!Array.isArray(response)) throw new Error("Feature snapshot list is unavailable: invalid response");
        return response;
      }, (data) => !data.length),
      trainingSection.load(() => listTrainingRuns(trainingStatusFilter), (data) => !data.length),
      modelSection.load(() => listMlModels(modelStatusFilter), (data) => !data.length),
      policySection.load(() => listDeploymentPolicies(policyStatusFilter), (data) => !data.length),
    ]);
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
    if (busy || !effective || (deviceMode && effective.deviceId !== deviceId.trim())) { setError("Load the selected scope before saving its settings."); return; }
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
    if (busy || !effective || effective.deviceId !== deviceId.trim()) return;
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
    const generation = ++detailGeneration.current;
    setDatasetDetail(null);
    setBusy(true);
    setError(null);
    try {
      const response = await getMlDatasetVersion(datasetVersionId);
      if (generation === detailGeneration.current) setDatasetDetail(response);
    } catch (e) {
      if (generation === detailGeneration.current) setError(safeMlError(e));
    } finally {
      if (generation === detailGeneration.current) setBusy(false);
    }
  }

  async function loadLabelAudit(labelId: string) {
    const generation = ++auditGeneration.current;
    setLabelAudit([]);
    setError(null);
    try {
      const response = await getMlLabelAudit(labelId);
      if (generation === auditGeneration.current) setLabelAudit(response);
    } catch (e) {
      if (generation === auditGeneration.current) setError(safeMlError(e));
    }
  }

  async function refreshLabels(datasetVersionId: string) {
    const generation = ++labelsGeneration.current;
    setLabels([]);
    const response = await listMlLabels(datasetVersionId);
    if (generation === labelsGeneration.current) setLabels(response);
  }

  const set = <K extends keyof MlSettingsValues>(key: K, value: MlSettingsValues[K]) =>
    setValues((v) => ({ ...v, [key]: value }));

  return {
    sections: [{ label: "datasets", resource: datasetSection }, { label: "snapshots", resource: snapshotSection }, { label: "training runs", resource: trainingSection }, { label: "models", resource: modelSection }, { label: "policies", resource: policySection }],
    dataAvailable: { datasets: datasetSection.data !== null, models: modelSection.data !== null, trainingRuns: trainingSection.data !== null, policies: policySection.data !== null },
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
