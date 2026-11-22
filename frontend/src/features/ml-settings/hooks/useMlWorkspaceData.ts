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
  getTrainingRun,
  trainingPageSize,
  datasetPageSize,
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
import {
  Device,
  DeviceType,
  getDevice,
  listDevices,
  listDeviceTypes,
} from "../../../devices/devicesApi";
import { useSectionResource } from "../../../components/useSectionResource";
import { initialResource } from "../../../components/useSectionResource";
import { useEntityResources } from "../../../components/useEntityResources";

function isEmptyList(data: unknown): boolean {
  if (!Array.isArray(data)) throw new Error("Inventory unavailable: invalid list response");
  return data.length === 0;
}

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
  selectedTrainingRunId = "",
  selectedDatasetVersionId = "",
}: {
  datasetStatusFilter: MlLifecycleStatusFilter;
  trainingStatusFilter: MlLifecycleStatusFilter;
  modelStatusFilter: MlLifecycleStatusFilter;
  policyStatusFilter: MlLifecycleStatusFilter;
  selectedTrainingRunId?: string;
  selectedDatasetVersionId?: string;
}) {
  const scopeGeneration = useRef(0);
  const detailGeneration = useRef(0);
  const labelsGeneration = useRef(0);
  const auditGeneration = useRef(0);
  useEffect(
    () => () => {
      scopeGeneration.current++;
      detailGeneration.current++;
      labelsGeneration.current++;
      auditGeneration.current++;
    },
    [],
  );
  const [values, setValues] = useState(initial);
  const [effective, setEffective] = useState<MlSettingsResponse | null>(null);
  const [deviceId, setDeviceId] = useState("");
  const [deviceMode, setDeviceMode] = useState(false);
  const [prediction, setPrediction] = useState<PredictionResponse | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [datasetPaging, setDatasetPaging] = useState({ filter: datasetStatusFilter, page: 0 });
  const datasetPage = datasetPaging.filter === datasetStatusFilter ? datasetPaging.page : 0;
  const setDatasetPage = (page: number) =>
    setDatasetPaging({ filter: datasetStatusFilter, page: Math.max(0, page) });
  const datasetSection = useSectionResource<MlDatasetSummary[]>(
    `${datasetStatusFilter}:${datasetPage}`,
  );
  const datasetPageRecords = datasetSection.data ?? [];
  const selectedDataset = useSectionResource<MlDatasetSummary>(selectedDatasetVersionId);
  const datasets =
    selectedDataset.data &&
    selectedDataset.data.datasetVersionId === selectedDatasetVersionId &&
    !datasetPageRecords.some((item) => item.datasetVersionId === selectedDatasetVersionId)
      ? [...datasetPageRecords, selectedDataset.data]
      : datasetPageRecords;
  useEffect(() => {
    if (
      !selectedDatasetVersionId ||
      datasetSection.data === null ||
      datasetPageRecords.some((item) => item.datasetVersionId === selectedDatasetVersionId)
    )
      return;
    void selectedDataset.load(async () => {
      const detail = await getMlDatasetVersion(selectedDatasetVersionId);
      if (detail.dataset.datasetVersionId !== selectedDatasetVersionId)
        throw new Error("Selected dataset version unavailable");
      return detail.dataset;
    });
  }, [selectedDatasetVersionId, datasetSection.data, selectedDataset.load]);
  const setDatasets = datasetSection.setData;
  const [datasetDetail, setDatasetDetail] = useState<MlDatasetDetail | null>(null);
  const [trainingPaging, setTrainingPaging] = useState({ filter: trainingStatusFilter, page: 0 });
  const trainingPage = trainingPaging.filter === trainingStatusFilter ? trainingPaging.page : 0;
  const setTrainingPage = (page: number) =>
    setTrainingPaging({ filter: trainingStatusFilter, page });
  const trainingSection = useSectionResource<MlTrainingRun[]>(
    `${trainingStatusFilter}:${trainingPage}`,
  );
  const trainingPageRuns = trainingSection.data ?? [];
  const selectedRun = useSectionResource<MlTrainingRun>(
    `${trainingStatusFilter}:${trainingPage}:${selectedTrainingRunId}`,
  );
  const trainingRuns =
    selectedRun.data && !trainingPageRuns.some((run) => run.id === selectedRun.data?.id)
      ? [...trainingPageRuns, selectedRun.data]
      : trainingPageRuns;
  useEffect(() => {
    if (
      trainingSection.data === null ||
      !selectedTrainingRunId ||
      trainingPageRuns.some((run) => run.id === selectedTrainingRunId)
    )
      return;
    void selectedRun.load(async () => {
      const run = await getTrainingRun(selectedTrainingRunId);
      if (run.id !== selectedTrainingRunId) throw new Error("Selected training run unavailable");
      return run;
    });
  }, [selectedTrainingRunId, trainingSection.data]);
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
  const labelResources = useEntityResources<MlLabel[]>("ml-labels");
  const [labelsDatasetVersionId, setLabelsDatasetVersionId] = useState("");
  const labelResource =
    labelResources.items[labelsDatasetVersionId] ?? initialResource<MlLabel[]>();
  const labels = labelResource.data ?? [];
  const setLabels = (data: MlLabel[]) => labelResources.setData(labelsDatasetVersionId, data);
  const [labelAudit, setLabelAudit] = useState<MlLabelAudit[]>([]);

  useEffect(() => {
    void loadAccount();
    void loadSelectors();
  }, []);

  useEffect(() => {
    void refreshMlWorkspace();
  }, [
    datasetStatusFilter,
    trainingStatusFilter,
    modelStatusFilter,
    policyStatusFilter,
    trainingPage,
    datasetPage,
  ]);

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
    setEffective(null);
    setDeviceMode(false);
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

  async function loadDevice(selectedDeviceId = deviceId) {
    const generation = ++scopeGeneration.current;
    const requestedDeviceId = selectedDeviceId.trim();
    setPrediction(null);
    if (!requestedDeviceId) {
      setError("Device ID is required");
      return;
    }
    setEffective(null);
    setDeviceMode(true);
    setBusy(true);
    setError(null);
    setStatusError(null);
    try {
      const response = await getDeviceMlSettings(requestedDeviceId);
      if (generation !== scopeGeneration.current) return;
      adopt(response);
      setDeviceMode(true);
      if (!devices.some((device) => device.id === requestedDeviceId))
        void getDevice(requestedDeviceId)
          .then((device) => {
            if (generation === scopeGeneration.current && device.id === requestedDeviceId)
              setDevices((current) => [...current.filter((item) => item.id !== device.id), device]);
          })
          .catch(() => {
            /* Ownership/settings evidence remains separate from unavailable device names/type. */
          });
      getLatestPrediction(requestedDeviceId)
        .then((response) => {
          if (generation === scopeGeneration.current) setPrediction(response);
        })
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
    const results = await Promise.allSettled([
      datasetSection.load(() => listMlDatasets(datasetStatusFilter, datasetPage), isEmptyList),
      snapshotSection.load(async () => {
        const response = await listFeatureSnapshots();
        if (!Array.isArray(response))
          throw new Error("Feature snapshot list is unavailable: invalid response");
        return response;
      }, isEmptyList),
      trainingSection.load(() => listTrainingRuns(trainingStatusFilter, trainingPage), isEmptyList),
      modelSection.load(() => listMlModels(modelStatusFilter), isEmptyList),
      policySection.load(() => listDeploymentPolicies(policyStatusFilter), isEmptyList),
    ]);
    return results.every((result) => result.status === "fulfilled" && result.value !== null);
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
    if (busy || !effective || (deviceMode && effective.deviceId !== deviceId.trim())) {
      setError("Load the selected scope before saving its settings.");
      return;
    }
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
      adopt(
        deviceMode
          ? await saveDeviceMlSettings(deviceId, values)
          : await saveAccountMlSettings(values),
      );
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
      if (
        !response?.dataset ||
        response.dataset.datasetVersionId !== datasetVersionId ||
        !Array.isArray(response.lineage)
      )
        throw new Error("Selected dataset detail unavailable: invalid lineage response");
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
    setLabelsDatasetVersionId(datasetVersionId);
    if (!datasetVersionId) return;
    return labelResources.load(datasetVersionId, () => listMlLabels(datasetVersionId), isEmptyList);
  }

  const set = <K extends keyof MlSettingsValues>(key: K, value: MlSettingsValues[K]) =>
    setValues((v) => ({ ...v, [key]: value }));

  return {
    labelResource,
    labelsDatasetVersionId,
    sections: [
      {
        label: "datasets",
        id: "dataset",
        resource: datasetSection,
        retry: () =>
          datasetSection.load(() => listMlDatasets(datasetStatusFilter, datasetPage), isEmptyList),
      },
      {
        label: "snapshots",
        id: "snapshot",
        resource: snapshotSection,
        retry: () => snapshotSection.load(listFeatureSnapshots, isEmptyList),
      },
      {
        label: "training runs",
        id: "training",
        resource: trainingSection,
        retry: () =>
          trainingSection.load(
            () => listTrainingRuns(trainingStatusFilter, trainingPage),
            isEmptyList,
          ),
      },
      {
        label: "models",
        id: "model",
        resource: modelSection,
        retry: () => modelSection.load(() => listMlModels(modelStatusFilter), isEmptyList),
      },
      {
        label: "policies",
        id: "policy",
        resource: policySection,
        retry: () =>
          policySection.load(() => listDeploymentPolicies(policyStatusFilter), isEmptyList),
      },
    ],
    dataAvailable: {
      datasets: datasetSection.data !== null,
      models: modelSection.data !== null,
      trainingRuns: trainingSection.data !== null,
      policies: policySection.data !== null,
    },
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
    datasetPageRecords,
    datasetPage,
    setDatasetPage,
    datasetHasMore: datasetPageRecords.length === datasetPageSize,
    datasetLoading: datasetSection.status === "loading" || datasetSection.refreshing,
    setDatasets,
    datasetDetail,
    setDatasetDetail,
    trainingRuns,
    trainingPageRuns,
    trainingPage,
    setTrainingPage,
    trainingHasMore: trainingPageRuns.length === trainingPageSize,
    trainingLoading: trainingSection.status === "loading" || trainingSection.refreshing,
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
