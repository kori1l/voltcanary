import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MlSettingsPage } from "./MlSettingsPage";

const accountSettings = {
  accountId: "account-1",
  deviceId: null,
  mlEnabled: true,
  predictionMode: "MODEL_OR_BASELINE_HEURISTIC",
  confidenceWarningThreshold: 0.6,
  maintenanceBehavior: "REDUCE_CONFIDENCE",
  explanationsVisible: true,
  source: "ACCOUNT",
  updatedAt: "2026-07-10T00:00:00Z",
};

const inheritedSettings = {
  ...accountSettings,
  deviceId: "device-1",
  source: "INHERITED",
};

const deviceOverrideSettings = {
  ...accountSettings,
  deviceId: "device-1",
  predictionMode: "MODEL_ONLY",
  maintenanceBehavior: "SUPPRESS_ML",
  source: "DEVICE_OVERRIDE",
};

const readyPrediction = {
  deviceId: "device-1",
  modelVersionId: "model-version-1",
  modelName: "trained-model",
  modelVersion: "v2",
  modelType: "gradient_boosted_tree",
  modelStatus: "READY",
  predictionStatus: "MODEL",
  anomalyScore: 0.2,
  degradationScore: 0.1,
  failureRiskScore: 0.37,
  failureHorizonDays: 30,
  failureRiskBand: "MEDIUM",
  confidence: 0.91,
  inputHash: "input-hash-1",
  riskDrivers: [
    { feature: "activePowerW_rolling_mean", value: 212.4, weight: 0.18, contribution: 0.42, direction: "increases_risk" },
    { feature: "voltageV_stddev", value: 2.1, weight: -0.08, contribution: -0.16, direction: "decreases_risk" },
  ],
  labelCoverage: 0.86,
  trainingDataWindow: { start: "2026-06-01T00:00:00Z", end: "2026-07-01T00:00:00Z" },
  signals: [{ metric: "activePowerW", score: 0.2 }],
  explanations: ["Failure probability is elevated by active power drift"],
  recommendedActions: ["Inspect washer load and motor temperature"],
  createdAt: "2026-07-10T00:00:00Z",
};
const testDevice = {
  id: "00000000-0000-0000-0000-000000000001",
  locationId: "line-1",
  name: "Washer 001",
  type: "washer",
  manufacturer: "VoltCanary",
  model: "W1",
  status: "ONLINE",
  metadata: { metrics: ["activePowerW", "voltageV", "currentA"] },
};
const datasetSummary = {
  datasetId: "dataset-1",
  datasetVersionId: "dataset-version-1",
  name: "Washer July",
  description: "Internal telemetry",
  sourceType: "INTERNAL_TELEMETRY",
  version: 1,
  status: "READY",
  splitStrategy: "TIME_BASED",
  featureConfigVersion: "feature-config-v1",
  sourceWindowStart: "2026-07-01T00:00:00Z",
  sourceWindowEnd: "2026-07-02T00:00:00Z",
  createdAt: "2026-07-10T00:00:00Z",
  readyAt: "2026-07-10T00:00:00Z",
  failureReason: null,
  artifactUri: null,
  artifactHash: null,
  immutableRowReference: "ml_canonical_metric_rows:dataset_version_id=dataset-version-1",
  dataHash: "sha256:data",
};
const importedDatasetSummary = {
  ...datasetSummary,
  datasetId: "dataset-2",
  datasetVersionId: "dataset-version-2",
  name: "Imported CSV July",
  description: "External import",
  sourceType: "EXTERNAL_IMPORT",
  status: "VALIDATING",
  readyAt: null,
  immutableRowReference: null,
  dataHash: null,
};
const datasetDetail = {
  dataset: datasetSummary,
  validationReports: [{
    id: "report-1",
    datasetVersionId: "dataset-version-1",
    importedFileId: null,
    acceptedRows: 120,
    rejectedRows: 0,
    warningCount: 1,
    rejectedReasons: {},
    mappingStats: { "accepted:activePowerW": 120 },
    qualityProfile: { rowCount: 120, deviceCount: 1, metricCount: 1 },
    summary: "Telemetry selection is ready for training.",
    createdAt: "2026-07-10T00:00:00Z",
  }],
  lineage: [{
    id: "lineage-1",
    datasetVersionId: "dataset-version-1",
    sourceType: "INTERNAL_TELEMETRY",
    sourceReference: "telemetry-service:/telemetry/devices/{deviceId}/readings",
    filterJson: "{}",
    selectedDevicesJson: "[]",
    selectedMetricsJson: "[\"activePowerW\"]",
    sourceWindowStart: "2026-07-01T00:00:00Z",
    sourceWindowEnd: "2026-07-02T00:00:00Z",
    contentHash: "sha256:lineage",
  }],
};
const workbenchPreview = {
  previewId: "prev-test-1",
  previewHash: "sha256:preview-test-1",
  expiresAt: "2026-07-10T00:15:00Z",
  status: "READY_WITH_WARNINGS",
  recommendedCandidateId: "candidate-best",
  selectedWindow: {
    from: "2026-07-01T00:00:00Z",
    to: "2026-07-02T00:00:00Z",
    source: "USER_SELECTED",
  },
  availableMetrics: [
    {
      metricName: "activePowerW",
      displayName: "Active power",
      canonicalMetricName: "active_power",
      unit: "W",
      devicesWithMetric: 1,
      selectedDeviceCount: 1,
      coverageRatio: 1,
      sampleCount: 120,
      maxSamplingGapSeconds: 3600,
      duplicateTimestampRate: 0,
      nonFiniteImpossibleValueCount: 0,
      unitNormalizationWarnings: 0,
      status: "READY",
      blockingReasons: [],
      warningReasons: [],
    },
    {
      metricName: "voltageV",
      displayName: "Voltage",
      canonicalMetricName: "voltage",
      unit: "V",
      devicesWithMetric: 1,
      selectedDeviceCount: 1,
      coverageRatio: 0.96,
      sampleCount: 118,
      maxSamplingGapSeconds: 3600,
      duplicateTimestampRate: 0,
      nonFiniteImpossibleValueCount: 0,
      unitNormalizationWarnings: 0,
      status: "READY_WITH_WARNINGS",
      blockingReasons: [],
      warningReasons: ["Minor telemetry gaps detected."],
    },
    {
      metricName: "currentA",
      displayName: "Current",
      canonicalMetricName: "current",
      unit: "A",
      devicesWithMetric: 1,
      selectedDeviceCount: 1,
      coverageRatio: 0.95,
      sampleCount: 114,
      maxSamplingGapSeconds: 7200,
      duplicateTimestampRate: 0,
      nonFiniteImpossibleValueCount: 0,
      unitNormalizationWarnings: 0,
      status: "READY",
      blockingReasons: [],
      warningReasons: [],
    },
  ],
  candidateWindows: [
    {
      id: "candidate-best",
      kind: "BEST_COVERAGE",
      from: "2026-07-01T00:00:00Z",
      to: "2026-07-02T00:00:00Z",
      score: 0.93,
      status: "READY_WITH_WARNINGS",
      recommended: true,
      sampleCount: 120,
      deviceCount: 1,
      metricCount: 3,
      coverageRatio: 0.97,
      maxSamplingGapSeconds: 3600,
      positiveLabels: 4,
      normalLabels: 100,
      unobservableLabels: 2,
      maintenanceExcludedLabels: 1,
      blockingReasons: [],
      warningReasons: ["Window overlaps excluded maintenance periods."],
      explanation: "Highest coverage with manageable gaps.",
    },
  ],
  timeline: [
    {
      bucketStart: "2026-07-01T00:00:00Z",
      bucketEnd: "2026-07-01T12:00:00Z",
      sampleCount: 60,
      coverageRatio: 1,
      status: "READY",
      events: [],
    },
    {
      bucketStart: "2026-07-01T12:00:00Z",
      bucketEnd: "2026-07-02T00:00:00Z",
      sampleCount: 58,
      coverageRatio: 0.93,
      status: "READY_WITH_WARNINGS",
      events: ["corrective maintenance label"],
    },
  ],
  labelReadiness: {
    horizonDays: 30,
    positiveLabels: 4,
    normalLabels: 100,
    unresolvedLabels: 2,
    unobservableRows: 2,
    maintenanceExcludedLabels: 1,
    coverageRatio: 0.97,
    status: "READY",
  },
  qualityGates: [
    {
      key: "minSamplesPerMetric",
      label: "Minimum samples per metric",
      status: "PASS",
      actual: 114,
      threshold: 24,
      blocking: true,
      message: "Selected metrics have enough samples.",
    },
  ],
  blockingReasons: [],
  warningReasons: ["Window overlaps excluded maintenance periods."],
  createRequestDefaults: {
    metrics: ["activePowerW", "voltageV", "currentA"],
    from: "2026-07-01T00:00:00Z",
    to: "2026-07-02T00:00:00Z",
    splitStrategy: "TIME_BASED",
    featureConfigVersion: "feature-config-v1",
    minSamplesPerMetric: 24,
    maxSamplingGapSeconds: 21600,
    modelFamily: "ANOMALY_DETECTION",
    horizonDays: null,
    metricSelectionMode: "COMMON",
  },
};
const modelVersion = {
  id: "model-1",
  accountId: "account-1",
  modelKey: "washer-anomaly",
  version: 2,
  family: "ANOMALY_DETECTION",
  algorithm: "baseline-statistical-anomaly-v1",
  status: "APPROVED",
  trainingRunId: "run-1",
  datasetVersionId: "dataset-version-1",
  featureSnapshotId: "feature-1",
  featureSchemaHash: "sha256:feature",
  artifactUri: "s3://model",
  artifactManifestUri: "s3://model/manifest.json",
  artifactHash: "sha256:model",
  artifactSizeBytes: 1024,
  compatibleDeviceTypes: ["washer"],
  supportedMetrics: ["activePowerW"],
  evaluationMetrics: { auc: 0.93 },
  approvedBy: "user-1",
  approvedAt: "2026-07-10T00:00:00Z",
  createdAt: "2026-07-10T00:00:00Z",
};
const failureModelVersion = {
  ...modelVersion,
  id: "model-failure-1",
  modelKey: "washer-failure",
  version: 1,
  family: "FAILURE_PREDICTION",
  algorithm: "sklearn-logistic-regression-failure-v1",
  status: "CANDIDATE",
  evaluationMetrics: {
    failureHorizonDays: 30,
    failurePrecision: 0.72,
    failureRecall: 0.61,
    failurePrAuc: 0.74,
    failurePrAucLift: 0.22,
    failureFalseAlertsPerDeviceMonth: 1.2,
    failureAverageLeadTimeDays: 5.5,
    labelCoverage: 0.84,
    failurePositiveLabels: 18,
    failureNegativeLabels: 240,
  },
  approvedBy: null,
  approvedAt: null,
};
const blockedFailureModelVersion = {
  ...failureModelVersion,
  id: "model-failure-blocked",
  modelKey: "washer-failure-low-labels",
  artifactUri: null,
  artifactManifestUri: null,
  artifactHash: null,
  artifactSizeBytes: null,
  evaluationMetrics: {
    failureHorizonDays: 30,
    failurePrecision: 0.2,
    failureRecall: 0.1,
    failureFalseAlertsPerDeviceMonth: 36,
    labelCoverage: 0.42,
    failurePositiveLabels: 0,
    failureNegativeLabels: 240,
    failureValidationSamples: 0,
  },
};
const labelItem = {
  id: "label-1",
  accountId: "account-1",
  datasetVersionId: "dataset-version-1",
  deviceId: null,
  externalDeviceKey: "external-device-1",
  metricName: "activePowerW",
  labelStartAt: "2026-07-01T00:00:00Z",
  labelEndAt: "2026-07-01T01:00:00Z",
  label: "ANOMALY",
  source: "MANUAL",
  createdBy: "user-1",
  createdAt: "2026-07-10T00:00:00Z",
  reason: "Operator review",
};
const featureSnapshot = {
  id: "feature-1",
  accountId: "account-1",
  datasetVersionId: "dataset-version-1",
  featureSetVersion: "feature-set-v1",
  codeConfigVersion: "analytics-feature-pipeline-v1",
  featureSchemaHash: "sha256:feature",
  featureDataHash: "sha256:feature-data",
  featureConfigHash: "sha256:feature-config",
  rowCount: 120,
  rejectedRowCount: 0,
  artifactUri: "db://feature",
  immutableRowReference: "ml_feature_rows:feature_snapshot_id=feature-1",
  modelFamily: "ANOMALY_DETECTION",
  horizonDays: null,
  featureTemplateId: "generic-appliance",
  featureTemplateVersion: "template-v1",
  lifecycleStatus: "ACTIVE",
  createdAt: "2026-07-10T00:00:00Z",
};
const failureFeatureSnapshot = {
  ...featureSnapshot,
  id: "feature-failure-1",
  modelFamily: "FAILURE_PREDICTION",
  horizonDays: 30,
};
const failedTrainingRun = {
  id: "run-failed-1",
  accountId: "account-1",
  datasetVersionId: "dataset-version-1",
  featureSnapshotId: "feature-1",
  modelFamily: "ANOMALY_DETECTION",
  algorithm: "baseline-statistical-anomaly-v1",
  status: "FAILED",
  evaluationMetricsJson: null,
  baselineMetricsJson: null,
  artifactUri: null,
  logSummary: null,
  failureReason: "worker error",
  createdAt: "2026-07-10T00:00:00Z",
  startedAt: "2026-07-10T00:01:00Z",
  completedAt: "2026-07-10T00:02:00Z",
};
const disabledPolicy = {
  id: "policy-1",
  accountId: "account-1",
  scope: "ACCOUNT",
  deviceId: null,
  deviceType: null,
  modelVersionId: null,
  mode: "DISABLED",
  canaryPercent: null,
  fallbackEnabled: true,
  updatedBy: "user-1",
  updatedAt: "2026-07-10T00:00:00Z",
};

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
});

describe("MlSettingsPage", () => {
  it("validates account settings and saves confirmed values", async () => {
    const requests: Request[] = [];
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.endsWith("/analytics/ml-settings") && init?.method === "PUT") {
        return json({ ...accountSettings, confidenceWarningThreshold: 0.7 });
      }
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    const threshold = await screen.findByLabelText(/Confidence warning threshold/i);
    await userEvent.clear(threshold);
    await userEvent.type(threshold, "1.2");
    await userEvent.click(screen.getByRole("button", { name: /Save settings/i }));

    expect(threshold).toBeInvalid();
    expect(requests.filter((request) => request.method === "PUT")).toHaveLength(0);

    await userEvent.clear(threshold);
    await userEvent.type(threshold, "0.7");
    await userEvent.click(screen.getByRole("button", { name: /Save settings/i }));

    await screen.findByText("ML settings saved");
    const saveRequest = requests.find((request) => request.method === "PUT");
    expect(saveRequest).toBeDefined();
    expect(await saveRequest!.json()).toMatchObject({ confidenceWarningThreshold: 0.7 });
  });

  it("shows inherited device defaults, saves overrides, and clears them", async () => {
    const requests: Request[] = [];
    let deviceGetCount = 0;
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.includes("/analytics/devices/device-1/predictions/latest")) {
        return json(readyPrediction);
      }
      if (url.endsWith("/analytics/ml-settings/devices/device-1") && init?.method === "PUT") {
        return json(deviceOverrideSettings);
      }
      if (url.endsWith("/analytics/ml-settings/devices/device-1") && init?.method === "DELETE") {
        return new Response(null, { status: 204 });
      }
      if (url.endsWith("/analytics/ml-settings/devices/device-1")) {
        deviceGetCount += 1;
        return json(deviceGetCount > 1 ? inheritedSettings : inheritedSettings);
      }
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await loadDevice();
    expect(await screen.findByText("INHERITED")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText(/Allowed prediction mode/i), "MODEL_ONLY");
    await userEvent.selectOptions(screen.getByLabelText(/Maintenance exclusions/i), "SUPPRESS_ML");
    await userEvent.click(screen.getByRole("button", { name: /Save settings/i }));

    expect(await screen.findByText("DEVICE_OVERRIDE")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Clear override/i }));

    expect(await screen.findByText("Device now inherits account defaults")).toBeInTheDocument();
    expect(requests.some((request) => request.method === "DELETE")).toBe(true);
  });

  it.each([
    ["MODEL", "trained-model / v2"],
    ["UNAVAILABLE", "ML unavailable; rule analytics continue"],
    ["INSUFFICIENT_DATA", "More telemetry required"],
    ["SUPPRESSED_MAINTENANCE", "Suppressed during maintenance"],
  ])("renders %s latest prediction status", async (predictionStatus, expectedText) => {
    mockFetch((url) => {
      if (url.includes("/analytics/devices/device-1/predictions/latest")) {
        return json({ ...readyPrediction, predictionStatus });
      }
      if (url.endsWith("/analytics/ml-settings/devices/device-1")) {
        return json(inheritedSettings);
      }
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await loadDevice();

    expect(await screen.findAllByText(expectedText)).not.toHaveLength(0);
    expect(screen.getByText("input-hash-1")).toBeInTheDocument();
  });

  it("keeps settings editable when latest prediction metadata is unavailable", async () => {
    mockFetch((url) => {
      if (url.includes("/analytics/devices/device-1/predictions/latest")) {
        return new Response(JSON.stringify({ message: "http://localhost:9000 stack trace" }), {
          status: 503,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (url.endsWith("/analytics/ml-settings/devices/device-1")) {
        return json(inheritedSettings);
      }
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await loadDevice();

    expect(await screen.findByText("ML settings are temporarily unavailable. Try again later.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Save settings/i })).toBeEnabled();
  });

  it("guides failure prediction training, promotion gates, and risk-driver review", async () => {
    const requests: Request[] = [];
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.includes("/analytics/devices/device-1/predictions/latest")) return json(readyPrediction);
      if (url.endsWith("/analytics/ml-settings/devices/device-1")) return json(inheritedSettings);
      if (url.endsWith("/analytics/ml/datasets")) return json([datasetSummary]);
      if (url.endsWith("/analytics/ml/training-runs") && init?.method === "POST") return json({
        id: "run-failure-1",
        accountId: "account-1",
        datasetVersionId: "dataset-version-1",
        featureSnapshotId: "feature-1",
        modelFamily: "FAILURE_PREDICTION",
        algorithm: "sklearn-logistic-regression-failure-v1",
        status: "QUEUED",
        evaluationMetricsJson: null,
        baselineMetricsJson: null,
        artifactUri: null,
        logSummary: null,
        failureReason: null,
        createdAt: "2026-07-10T00:00:00Z",
        startedAt: null,
        completedAt: null,
      });
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([failureModelVersion, blockedFailureModelVersion]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await loadDevice();

    await userEvent.click(screen.getByRole("button", { name: "Training" }));
    const failureTemplateButtons = await screen.findAllByRole("button", { name: /Failure prediction/i });
    await userEvent.click(failureTemplateButtons[failureTemplateButtons.length - 1]);
    expect(screen.getByText("Outcome supervised")).toBeInTheDocument();
    expect(screen.getByText(/Generate horizon labels for the same dataset and horizon/i)).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText(/Prediction horizon/i));
    await userEvent.type(screen.getByLabelText(/Prediction horizon/i), "30");
    await userEvent.clear(screen.getByLabelText(/Minimum positive labels/i));
    await userEvent.type(screen.getByLabelText(/Minimum positive labels/i), "4");
    await userEvent.clear(screen.getByLabelText(/Minimum label coverage/i));
    await userEvent.type(screen.getByLabelText(/Minimum label coverage/i), "0.75");
    expect(screen.getByText(/Select or generate a feature snapshot before queueing training/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Queue training/i })).toBeDisabled();
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/training-runs") && request.method === "POST")).toBe(false);

    await userEvent.click(screen.getByRole("button", { name: "Models" }));
    expect(await screen.findByText("Promotion gates passed")).toBeInTheDocument();
    expect(screen.getByText("Promotion blocked")).toBeInTheDocument();
    expect(screen.getByText("Minimum Label Coverage")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Approve washer-failure-low-labels v1/i })).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "Monitoring" }));
    expect(await screen.findByText("30-day failure risk")).toBeInTheDocument();
    expect(screen.getByText("Active Power W Rolling Mean")).toBeInTheDocument();
    expect(screen.getByText(/Raises risk \/ value 212.4 \/ weight 0.18/i)).toBeInTheDocument();
    expect(screen.getByText("Failure risk")).toBeInTheDocument();
    expect(screen.getByText("Anomaly score")).toBeInTheDocument();
    expect(screen.getByText("Degradation score")).toBeInTheDocument();
  });

  it("queues training with an older compatible feature snapshot from inventory", async () => {
    const requests: Request[] = [];
    const olderFailureSnapshot = {
      ...failureFeatureSnapshot,
      id: "feature-failure-older",
      rowCount: 88,
      featureSchemaHash: "sha256:older-failure-schema",
      featureDataHash: "sha256:older-failure-data",
      createdAt: "2026-07-09T00:00:00Z",
    };
    const newerFailureSnapshot = {
      ...failureFeatureSnapshot,
      id: "feature-failure-newer",
      horizonDays: 14,
      rowCount: 144,
      featureSchemaHash: "sha256:newer-failure-schema",
      featureDataHash: "sha256:newer-failure-data",
      createdAt: "2026-07-11T00:00:00Z",
    };
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.endsWith("/analytics/ml/datasets")) return json([datasetSummary]);
      if (url.endsWith("/analytics/ml/datasets/feature-snapshots") && init?.method !== "POST") {
        return json([newerFailureSnapshot, olderFailureSnapshot, featureSnapshot]);
      }
      if (url.endsWith("/analytics/ml/training-runs") && init?.method === "POST") {
        return json({
          id: "run-failure-older",
          accountId: "account-1",
          datasetVersionId: "dataset-version-1",
          featureSnapshotId: "feature-failure-older",
          modelFamily: "FAILURE_PREDICTION",
          algorithm: "sklearn-logistic-regression-failure-v1",
          status: "QUEUED",
          evaluationMetricsJson: null,
          baselineMetricsJson: null,
          artifactUri: null,
          logSummary: null,
          failureReason: null,
          createdAt: "2026-07-10T00:00:00Z",
          startedAt: null,
          completedAt: null,
        });
      }
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);

    await userEvent.click(screen.getByRole("button", { name: "Training" }));
    const failureTemplateButtons = await screen.findAllByRole("button", { name: /Failure prediction/i });
    await userEvent.click(failureTemplateButtons[failureTemplateButtons.length - 1]);
    expect(await screen.findByText("2 compatible feature snapshots")).toBeInTheDocument();
    expect(screen.getAllByText(/sha256:older-failure-schema/i)).not.toHaveLength(0);

    await userEvent.selectOptions(screen.getByLabelText(/Feature snapshot/i), "feature-failure-older");
    await userEvent.clear(screen.getByLabelText(/Prediction horizon/i));
    await userEvent.type(screen.getByLabelText(/Prediction horizon/i), "30");
    await userEvent.clear(screen.getByLabelText(/Minimum positive labels/i));
    await userEvent.type(screen.getByLabelText(/Minimum positive labels/i), "4");
    await userEvent.clear(screen.getByLabelText(/Minimum label coverage/i));
    await userEvent.type(screen.getByLabelText(/Minimum label coverage/i), "0.75");
    await userEvent.click(screen.getByRole("button", { name: /Queue training/i }));

    expect(await screen.findByText("Training workflow updated")).toBeInTheDocument();
    const trainingRequest = requests.find((request) => request.url.endsWith("/analytics/ml/training-runs") && request.method === "POST");
    expect(trainingRequest).toBeDefined();
    expect(await trainingRequest!.json()).toMatchObject({
      datasetVersionId: "dataset-version-1",
      featureSnapshotId: "feature-failure-older",
      modelFamily: "FAILURE_PREDICTION",
      failureHorizonDays: 30,
      minimumPositiveLabels: 4,
      minimumLabelCoverage: 0.75,
    });
  });

  it("shows ML backend JSON errors as readable operator messages", async () => {
    mockFetch((url, init) => {
      if (url.includes("/analytics/devices/device-1/predictions/latest")) return json(readyPrediction);
      if (url.endsWith("/analytics/ml-settings/devices/device-1")) return json(inheritedSettings);
      if (url.endsWith("/analytics/ml/datasets")) return json([]);
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models/model-failure-1/approve") && init?.method === "POST") {
        return errorJson(400, {
          timestamp: "2026-08-03T00:42:56.614+00:00",
          status: 400,
          error: "Bad Request",
          path: "/analytics/ml/models/model-failure-1/approve",
        });
      }
      if (url.endsWith("/analytics/ml/models")) return json([failureModelVersion]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await loadDevice();

    await userEvent.click(screen.getByRole("button", { name: "Models" }));
    await userEvent.click(await screen.findByRole("button", { name: /Approve washer-failure v1/i }));

    expect(await screen.findByText("Bad Request for /analytics/ml/models/model-failure-1/approve")).toBeInTheDocument();
    expect(screen.queryByText(/timestamp/i)).not.toBeInTheDocument();
  });

  it("restores unsaved dataset drafts for the current account", async () => {
    localStorage.setItem("voltcanary.ml.account-1.datasets.ANOMALY_DETECTION.internal-dataset", JSON.stringify({
      name: "Restored washer draft",
      deviceIds: [testDevice.id],
      metrics: ["activePowerW"],
      from: "2026-07-03T00:00",
      to: "2026-07-04T00:00",
    }));
    mockFetch((url) => {
      if (url.endsWith("/analytics/ml/datasets")) return json([]);
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));

    expect(await screen.findByText("Restored unsaved dataset draft.")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Restored washer draft")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /Washer 001/i })).toBeChecked();
  });

  it("previews workbench metrics from backend coverage only and keeps stale preview from creating", async () => {
    const requests: Request[] = [];
    const pressurePreview = {
      ...workbenchPreview,
      availableMetrics: [{
        ...workbenchPreview.availableMetrics[0],
        metricName: "linePressurePa",
        displayName: "Line pressure",
        canonicalMetricName: "line_pressure",
        unit: "Pa",
      }],
      createRequestDefaults: {
        ...workbenchPreview.createRequestDefaults,
        metrics: ["linePressurePa"],
      },
    };
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.endsWith("/analytics/ml/datasets/internal-telemetry/preview")) return json(pressurePreview);
      if (url.endsWith("/analytics/ml/datasets")) return json([]);
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    await selectWorkbenchDevice();

    expect(await screen.findAllByText("Line pressure")).toHaveLength(2);
    expect(screen.queryByText("Active power")).not.toBeInTheDocument();
    await userEvent.click(screen.getByLabelText(/All available with coverage/i));
    expect(screen.getByRole("button", { name: /Refresh preview/i })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Refresh preview/i }));
    await screen.findAllByText("Line pressure");
    const previewRequests = requests.filter((request) => request.url.endsWith("/analytics/ml/datasets/internal-telemetry/preview"));
    expect(await previewRequests[previewRequests.length - 1].json()).toMatchObject({ metricSelectionMode: "ALL_AVAILABLE" });
    await userEvent.clear(screen.getByLabelText(/Workbench range from/i));
    await userEvent.type(screen.getByLabelText(/Workbench range from/i), "2026-07-01T01:00");
    expect(screen.getByRole("button", { name: /Create dataset version/i })).toBeDisabled();
    await waitFor(() => expect(requests.filter((request) => request.url.endsWith("/analytics/ml/datasets/internal-telemetry/preview")).length).toBeGreaterThanOrEqual(3));
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/datasets/internal-telemetry") && request.method === "POST")).toBe(false);
  });

  it("blocks oversized workbench preview ranges before calling the preview API", async () => {
    const requests: Request[] = [];
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.endsWith("/analytics/ml/datasets/internal-telemetry/preview")) return json(workbenchPreview);
      if (url.endsWith("/analytics/ml/datasets")) return json([]);
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    await userEvent.clear(screen.getByLabelText(/Workbench range from/i));
    await userEvent.type(screen.getByLabelText(/Workbench range from/i), "2026-06-01T00:00");
    await selectWorkbenchDevice();

    await userEvent.click(screen.getByRole("button", { name: /Preview dataset/i }));

    expect(await screen.findAllByText(/Preview range must be 30 days or less/i)).not.toHaveLength(0);
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/datasets/internal-telemetry/preview"))).toBe(false);
  });

  it("blocks stale restored metrics until the preview selection is corrected", async () => {
    localStorage.setItem("voltcanary.ml.account-1.datasets.ANOMALY_DETECTION.internal-dataset", JSON.stringify({
      name: "Stale metric draft",
      deviceIds: [testDevice.id],
      selectedMetrics: ["legacyMetric"],
      from: "2026-07-01T00:00",
      to: "2026-07-02T00:00",
    }));
    mockFetch((url) => {
      if (url.endsWith("/analytics/ml/datasets/internal-telemetry/preview")) return json(workbenchPreview);
      if (url.endsWith("/analytics/ml/datasets")) return json([]);
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    await userEvent.click(await screen.findByRole("button", { name: /Preview dataset/i }));

    expect(await screen.findByText(/Unavailable metrics in draft: legacyMetric/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Create dataset version/i })).toBeDisabled();
  });

  it("renders preview dependency errors and blocked readiness reasons", async () => {
    let failPreview = true;
    const blockedPreview = {
      ...workbenchPreview,
      status: "BLOCKED",
      blockingReasons: ["Minimum samples per metric is below the required threshold."],
      warningReasons: [],
      qualityGates: [{
        ...workbenchPreview.qualityGates[0],
        status: "BLOCK",
        actual: 3,
        message: "Minimum samples per metric is below the required threshold.",
      }],
    };
    mockFetch((url) => {
      if (url.endsWith("/analytics/ml/datasets/internal-telemetry/preview")) {
        if (failPreview) {
          failPreview = false;
          return errorJson(503, { message: "Telemetry service unavailable" });
        }
        return json(blockedPreview);
      }
      if (url.endsWith("/analytics/ml/datasets")) return json([]);
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    await selectWorkbenchDevice();
    expect(await screen.findByText(/Dataset preview is temporarily unavailable/i)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /Preview dataset/i }));
    expect(await screen.findAllByText(/Minimum samples per metric is below the required threshold/i)).not.toHaveLength(0);
    expect(screen.getByRole("button", { name: /Create dataset version/i })).toBeDisabled();
  });

  it("does not render raw Spring error JSON for preview warmup failures", async () => {
    mockFetch((url) => {
      if (url.endsWith("/analytics/ml/datasets/internal-telemetry/preview")) {
        return errorJson(503, {
          timestamp: "2026-08-12T23:41:00.212+00:00",
          status: 503,
          error: "Service Unavailable",
          path: "/analytics/ml/datasets/internal-telemetry/preview",
        });
      }
      if (url.endsWith("/analytics/ml/datasets")) return json([]);
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    await selectWorkbenchDevice();

    expect(await screen.findByText(/Dataset preview is temporarily unavailable/i)).toBeInTheDocument();
    expect(screen.queryByText(/"timestamp"/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/\/analytics\/ml\/datasets\/internal-telemetry\/preview/i)).not.toBeInTheDocument();
  });

  it("shows failure horizon readiness and syncs candidate range selection", async () => {
    mockFetch((url) => {
      if (url.endsWith("/analytics/ml/datasets/internal-telemetry/preview")) return json({
        ...workbenchPreview,
        status: "READY",
        warningReasons: [],
        createRequestDefaults: {
          ...workbenchPreview.createRequestDefaults,
          modelFamily: "FAILURE_PREDICTION",
          horizonDays: 30,
        },
      });
      if (url.endsWith("/analytics/ml/datasets")) return json([]);
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    await userEvent.selectOptions(screen.getByLabelText(/Model family/i), "FAILURE_PREDICTION");
    await selectWorkbenchDevice();

    expect(await screen.findByText(/30-day horizon readiness/i)).toBeInTheDocument();
    expect(screen.getByText(/4 positive \/ 100 normal \/ 2 unresolved/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Use window/i }));
    expect((screen.getByLabelText(/Workbench range from/i) as HTMLInputElement).value).toMatch(/^2026-07-0?1T/);
    expect(screen.getByRole("button", { name: /Create dataset version/i })).toBeDisabled();
    await waitFor(() => expect(screen.queryByText(/Preview is stale; refresh preview first/i)).not.toBeInTheDocument());
  });

  it("runs lifecycle actions with reasons and renders dependency conflicts", async () => {
    const requests: Request[] = [];
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const prompt = vi.spyOn(window, "prompt").mockReturnValue("cleanup reason");
    let labelsVisible = true;
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.endsWith("/analytics/ml-settings")) return json(accountSettings);
      if (url.includes("/analytics/ml/datasets?statusFilter=archived")) return json([{ ...datasetSummary, status: "ARCHIVED" }]);
      if (url.endsWith("/analytics/ml/datasets")) return json([datasetSummary]);
      if (url.endsWith("/analytics/ml/datasets/versions/dataset-version-1")) return json(datasetDetail);
      if (url.includes("/analytics/ml/datasets/versions/dataset-version-1/lifecycle-impact")) return json(impact("archive", "Dataset version action is allowed", { labels: 0, featureSnapshots: 0, trainingRuns: 0, modelVersions: 0 }));
      if (url.endsWith("/analytics/ml/datasets/versions/dataset-version-1/archive") && init?.method === "POST") return json({ ...datasetSummary, status: "ARCHIVED" });
      if (url.endsWith("/analytics/ml/datasets/feature-snapshots") && init?.method === "POST") return json(featureSnapshot);
      if (url.includes("/analytics/ml/datasets/feature-snapshots/feature-1/lifecycle-impact")) return json(impact("delete", "Feature snapshot action is allowed", { trainingRuns: 0, modelVersions: 0 }));
      if (url.endsWith("/analytics/ml/datasets/feature-snapshots/feature-1/delete") && init?.method === "POST") return json({ ...featureSnapshot, lifecycleStatus: "DELETED" });
      if (url.includes("/analytics/ml/labels?datasetVersionId=dataset-version-1")) return json(labelsVisible ? [labelItem] : []);
      if (url.includes("/analytics/ml/labels/label-1/lifecycle-impact")) return json(impact("delete", "Manual label deletion is allowed", { source: "MANUAL" }));
      if (url.endsWith("/analytics/ml/labels/label-1/delete") && init?.method === "POST") {
        labelsVisible = false;
        return json(labelItem);
      }
      if (url.includes("/analytics/ml/training-runs?statusFilter=failed")) return json([failedTrainingRun]);
      if (url.includes("/analytics/ml/training-runs/run-failed-1/lifecycle-impact")) return json(impact("delete", "Training run action is allowed", { modelVersions: 0, status: "FAILED" }));
      if (url.endsWith("/analytics/ml/training-runs/run-failed-1/delete") && init?.method === "POST") return json({ ...failedTrainingRun, status: "DELETED" });
      if (url.endsWith("/analytics/ml/training-runs")) return json([failedTrainingRun]);
      if (url.includes("/analytics/ml/models?statusFilter=failed")) return json([failureModelVersion]);
      if (url.includes("/analytics/ml/models/model-failure-1/lifecycle-impact")) return json(impact("delete", "Model action is allowed", { count: 0 }));
      if (url.endsWith("/analytics/ml/models/model-failure-1/delete") && init?.method === "POST") return json({ ...failureModelVersion, status: "DELETED" });
      if (url.endsWith("/analytics/ml/models")) return json([failureModelVersion]);
      if (url.includes("/analytics/ml/deployment-policies?statusFilter=deleted")) return json([disabledPolicy]);
      if (url.includes("/analytics/ml/deployment-policies/policy-1/lifecycle-impact")) return json(impact("delete", "Deployment policy action is allowed", { scope: "ACCOUNT", mode: "DISABLED" }));
      if (url.endsWith("/analytics/ml/deployment-policies/policy-1/delete") && init?.method === "POST") return json(disabledPolicy);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([disabledPolicy]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);

    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    await userEvent.selectOptions(await screen.findByLabelText(/Dataset status filter/i), "archived");
    expect(await screen.findByRole("button", { name: /ARCHIVED/i })).toBeInTheDocument();
    await userEvent.click(await screen.findByRole("button", { name: /Washer July/i }));
    await userEvent.click(screen.getByRole("button", { name: /Archive dataset/i }));
    await userEvent.click(screen.getByRole("button", { name: /Washer July/i }));
    await userEvent.click(screen.getByRole("button", { name: /Generate feature snapshot/i }));
    await userEvent.click(await screen.findByRole("button", { name: /Delete snapshot/i }));
    await userEvent.selectOptions(screen.getByLabelText(/Dataset status filter/i), "default");

    await userEvent.click(screen.getByRole("button", { name: "Labels" }));
    await userEvent.click(screen.getByRole("button", { name: /Refresh labels/i }));
    await userEvent.click(await screen.findByRole("button", { name: /Delete/i }));

    await userEvent.click(screen.getByRole("button", { name: "Training" }));
    await userEvent.selectOptions(await screen.findByLabelText(/Training status filter/i), "failed");
    await userEvent.click(await screen.findByRole("button", { name: /Delete/i }));

    await userEvent.click(screen.getByRole("button", { name: "Models" }));
    await userEvent.selectOptions(await screen.findByLabelText(/Model status filter/i), "failed");
    await userEvent.click(await screen.findByRole("button", { name: /Delete washer-failure v1/i }));

    await userEvent.click(screen.getByRole("button", { name: "Policies" }));
    await userEvent.selectOptions(await screen.findByLabelText(/Policy status filter/i), "deleted");
    await userEvent.click(await screen.findByRole("button", { name: /Delete/i }));

    expect(confirm).toHaveBeenCalled();
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("Impact:"));
    expect(prompt).toHaveBeenCalled();
    const lifecycleRequests = requests.filter((request) => request.method === "POST" && /(archive|delete)$/.test(request.url));
    expect(lifecycleRequests.map((request) => new URL(request.url).pathname)).toEqual(expect.arrayContaining([
      "/analytics/ml/datasets/versions/dataset-version-1/archive",
      "/analytics/ml/datasets/feature-snapshots/feature-1/delete",
      "/analytics/ml/labels/label-1/delete",
      "/analytics/ml/training-runs/run-failed-1/delete",
      "/analytics/ml/models/model-failure-1/delete",
      "/analytics/ml/deployment-policies/policy-1/delete",
    ]));
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/datasets?statusFilter=archived"))).toBe(true);
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/training-runs?statusFilter=failed"))).toBe(true);
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/models?statusFilter=failed"))).toBe(true);
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/deployment-policies?statusFilter=deleted"))).toBe(true);
    expect(await lifecycleRequests[0].json()).toMatchObject({ reason: "cleanup reason", force: false });
  });

  it("shows lifecycle dependency counts before blocked destructive actions", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.spyOn(window, "prompt").mockReturnValue("cleanup reason");
    const requests: Request[] = [];
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.endsWith("/analytics/ml-settings")) return json(accountSettings);
      if (url.endsWith("/analytics/ml/datasets")) return json([datasetSummary]);
      if (url.endsWith("/analytics/ml/datasets/versions/dataset-version-1")) return json({ ...datasetDetail, dataset: { ...datasetSummary, status: "FAILED" } });
      if (url.includes("/analytics/ml/datasets/versions/dataset-version-1/lifecycle-impact")) {
        return json(impact("delete", "Dataset version is referenced and should be archived instead of deleted", { trainingRuns: 2, labels: 4 }, true));
      }
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    await userEvent.click(await screen.findByRole("button", { name: /Washer July/i }));
    await userEvent.click(screen.getByRole("button", { name: /Delete dataset/i }));

    expect(await screen.findByText("Dataset version is referenced and should be archived instead of deleted (datasetVersion Training runs 2, Labels 4)")).toBeInTheDocument();
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/datasets/versions/dataset-version-1/delete"))).toBe(false);
  });

  it("supports dataset, import, training, registry, and deployment workflows", async () => {
    const requests: Request[] = [];
    let importedDatasetVisible = false;
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.includes("/analytics/devices/device-1/predictions/latest")) return json(readyPrediction);
      if (url.endsWith("/analytics/ml-settings/devices/device-1")) return json(inheritedSettings);
      if (url.endsWith("/analytics/ml/datasets")) return json(importedDatasetVisible ? [importedDatasetSummary, datasetSummary] : [datasetSummary]);
      if (url.endsWith("/analytics/ml/datasets/versions/dataset-version-1")) return json(datasetDetail);
      if (url.endsWith("/analytics/ml/datasets/internal-telemetry/preview") && init?.method === "POST") return json(workbenchPreview);
      if (url.endsWith("/analytics/ml/datasets/internal-telemetry") && init?.method === "POST") return json({
        datasetId: "dataset-1",
        datasetVersionId: "dataset-version-1",
        accountId: "account-1",
        version: 1,
        status: "READY",
        splitStrategy: "TIME_BASED",
        acceptedRows: 120,
        rejectedRows: 0,
        warningCount: 1,
        validationMessages: ["Telemetry selection is ready for training."],
        createdAt: "2026-07-10T00:00:00Z",
        readyAt: "2026-07-10T00:00:00Z",
      });
      if (url.endsWith("/analytics/ml/datasets/feature-snapshots")) {
        const body = JSON.parse(String(init?.body ?? "{}")) as { modelFamily?: string; horizonDays?: number | null };
        const id = body.modelFamily === "FAILURE_PREDICTION" ? "feature-failure-1" : "feature-1";
        return json({
          ...featureSnapshot,
          id,
          immutableRowReference: `ml_feature_rows:feature_snapshot_id=${id}`,
          modelFamily: body.modelFamily ?? "ANOMALY_DETECTION",
          horizonDays: body.horizonDays ?? null,
          featureTemplateId: "fridge-freezer",
        });
      }
      if (url.endsWith("/analytics/ml/datasets/external-csv")) {
        importedDatasetVisible = true;
        return json({
          datasetId: "dataset-2",
          datasetVersionId: "dataset-version-2",
          importedFileId: "file-1",
          validationReportId: "report-2",
          contentHash: "sha256:file",
          acceptedRows: 5,
          rejectedRows: 0,
          warningCount: 0,
          validationMessages: [],
          importedAt: "2026-07-10T00:00:00Z",
        });
      }
      if (url.includes("/analytics/ml/labels?datasetVersionId=dataset-version-1")) return json([labelItem]);
      if (url.endsWith("/analytics/ml/datasets/horizon-labels") && init?.method === "POST") return json({
        jobId: "label-job-1",
        datasetVersionId: "dataset-version-1",
        status: "COMPLETED",
        submittedAt: "2026-07-10T00:00:00Z",
        startedAt: "2026-07-10T00:00:01Z",
        completedAt: "2026-07-10T00:00:10Z",
        progress: 100,
        message: "Horizon label generation completed",
        failureReason: null,
        horizonDays: 30,
        generationVersion: "failure-outcome-v1",
        includePreventiveMaintenance: false,
        inputLineageHash: "sha256:data",
        result: {
          lineageHash: "sha256:labels",
          generatedLabels: 120,
          positiveLabels: 4,
          normalLabels: 100,
          maintenanceExcludedLabels: 6,
          unresolvedLabels: 2,
          unobservableRows: 8,
        },
      });
      if (url.endsWith("/analytics/ml/labels") && init?.method === "POST") return json(labelItem);
      if (url.endsWith("/analytics/ml/labels/label-1") && init?.method === "PUT") return json({ ...labelItem, label: "NORMAL", reason: "Corrected during label review" });
      if (url.endsWith("/analytics/ml/labels/label-1/audit")) return json([{
        id: "audit-1",
        labelId: "label-1",
        previousLabel: "ANOMALY",
        newLabel: "NORMAL",
        editedBy: "user-1",
        editedAt: "2026-07-10T00:10:00Z",
        reason: "Corrected during label review",
      }]);
      if (url.endsWith("/analytics/ml/training-runs") && init?.method === "POST") return json({
        id: "run-1",
        accountId: "account-1",
        datasetVersionId: "dataset-version-1",
        featureSnapshotId: "feature-1",
        modelFamily: "ANOMALY_DETECTION",
        algorithm: "baseline-statistical-anomaly-v1",
        status: "QUEUED",
        evaluationMetricsJson: null,
        baselineMetricsJson: null,
        artifactUri: null,
        logSummary: null,
        failureReason: null,
        createdAt: "2026-07-10T00:00:00Z",
        startedAt: null,
        completedAt: null,
      });
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models/model-1/reject")) return json({ ...modelVersion, status: "REJECTED" });
      if (url.endsWith("/analytics/ml/models")) return json([modelVersion, failureModelVersion, blockedFailureModelVersion]);
      if (url.endsWith("/analytics/ml/deployment-policies") && init?.method === "POST") return json({
        id: "policy-1",
        accountId: "account-1",
        scope: "ACCOUNT",
        deviceId: null,
        deviceType: null,
        modelVersionId: "model-1",
        mode: "SHADOW",
        canaryPercent: null,
        fallbackEnabled: true,
        updatedBy: "user-1",
        updatedAt: "2026-07-10T00:00:00Z",
      });
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([{
        id: "policy-1",
        accountId: "account-1",
        scope: "ACCOUNT",
        deviceId: null,
        deviceType: null,
        modelVersionId: "model-1",
        mode: "SHADOW",
        canaryPercent: null,
        fallbackEnabled: true,
        updatedBy: "user-1",
        updatedAt: "2026-07-10T00:00:00Z",
      }]);
      if (url.includes("/analytics/ml/deployment-policies/resolve")) return json({
        policySource: "ACCOUNT",
        policyId: "policy-1",
        modelVersionId: "model-1",
        mode: "SHADOW",
        fallbackEnabled: true,
        fallbackReason: null,
      });
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await loadDevice();

    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    await selectWorkbenchDevice();
    expect(await screen.findAllByText("Active power")).not.toHaveLength(0);
    await userEvent.click(screen.getByRole("button", { name: /Create dataset version/i }));
    expect(await screen.findByText(/Internal telemetry dataset READY: 120 accepted \/ 0 rejected/i)).toBeInTheDocument();
    await userEvent.click(await screen.findByRole("button", { name: /Washer July/i }));
    expect(await screen.findByText("Telemetry selection is ready for training.")).toBeInTheDocument();
    expect(screen.getByText("sha256:data")).toBeInTheDocument();
    expect(screen.getByText("ml_canonical_metric_rows:dataset_version_id=dataset-version-1")).toBeInTheDocument();
    expect(screen.getByText("Row count")).toBeInTheDocument();
    expect(screen.getByText("Device count")).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText(/Feature template/i), "fridge-freezer");
    await userEvent.clear(screen.getByLabelText(/Rolling window/i));
    await userEvent.type(screen.getByLabelText(/Rolling window/i), "3");
    await userEvent.click(screen.getByRole("button", { name: /Generate feature snapshot/i }));
    expect(await screen.findByText(/Feature snapshot generated/i)).toBeInTheDocument();
    expect(await screen.findByText("sha256:feature-data")).toBeInTheDocument();
    expect(screen.getByText("sha256:feature-config")).toBeInTheDocument();
    expect(screen.getByText("fridge-freezer / template-v1")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Import" }));
    await userEvent.upload(screen.getByLabelText(/Dataset file/i), new File(["ts,device,metric,value"], "sample.csv", { type: "text/csv" }));
    await userEvent.click(screen.getByRole("button", { name: /Import dataset/i }));
    expect(await screen.findByText(/Imported 5 rows/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    expect(await screen.findByRole("button", { name: /Imported CSV July.*VALIDATING/i })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Labels" }));
    await userEvent.click(screen.getByRole("button", { name: /Refresh labels/i }));
    expect(await screen.findByText(/Operator review/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Generate horizon labels/i }));
    expect(await screen.findByText("sha256:labels")).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText(/Correct label label-1/i), "NORMAL");
    expect(await screen.findByText("Label review updated")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Audit/i }));
    expect(await screen.findByText(/Corrected during label review/i)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Training" }));
    await userEvent.click(screen.getByRole("button", { name: /Queue training/i }));
    expect(await screen.findByText("Training workflow updated")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Datasets" }));
    await userEvent.selectOptions(screen.getAllByLabelText(/Model family/i)[1], "FAILURE_PREDICTION");
    await userEvent.clear(screen.getByLabelText(/Snapshot horizon days/i));
    await userEvent.type(screen.getByLabelText(/Snapshot horizon days/i), "30");
    await userEvent.click(screen.getByRole("button", { name: /Generate feature snapshot/i }));
    expect(await screen.findByText("FAILURE_PREDICTION")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Training" }));
    const failureTrainingButtons = screen.getAllByRole("button", { name: /Failure prediction/i });
    await userEvent.click(failureTrainingButtons[failureTrainingButtons.length - 1]);
    await userEvent.selectOptions(screen.getByLabelText(/Feature snapshot/i), "feature-failure-1");
    await userEvent.clear(screen.getByLabelText(/Prediction horizon/i));
    await userEvent.type(screen.getByLabelText(/Prediction horizon/i), "30");
    await userEvent.clear(screen.getByLabelText(/Minimum positive labels/i));
    await userEvent.type(screen.getByLabelText(/Minimum positive labels/i), "4");
    await userEvent.clear(screen.getByLabelText(/Minimum label coverage/i));
    await userEvent.type(screen.getByLabelText(/Minimum label coverage/i), "0.75");
    await userEvent.click(screen.getByRole("button", { name: /Queue training/i }));
    expect(await screen.findByText("Training workflow updated")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Models" }));
    expect(await screen.findAllByText("30-day failure horizon")).toHaveLength(2);
    expect(screen.getByText("72%")).toBeInTheDocument();
    expect(screen.getByText("61%")).toBeInTheDocument();
    expect(screen.getByText("84%")).toBeInTheDocument();
    expect(screen.getByText("18 positive / 240 negative")).toBeInTheDocument();
    expect(screen.getByText("Promotion gates passed")).toBeInTheDocument();
    expect(screen.getByText("Promotion blocked")).toBeInTheDocument();
    expect(screen.getByText("Minimum Positive Labels")).toBeInTheDocument();
    expect(screen.getAllByText(/Observed 0 \/ required 1/i)).toHaveLength(3);
    expect(screen.getByText("Artifact Load Test")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Approve washer-failure-low-labels v1/i })).toBeDisabled();
    await userEvent.click((await screen.findAllByRole("button", { name: /Reject/i }))[0]);
    expect(await screen.findByText("Model registry updated")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Policies" }));
    await userEvent.click(screen.getByRole("button", { name: /Save policy/i }));
    expect(await screen.findByText("Deployment policy saved")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Resolve policy/i }));
    expect(await screen.findAllByText(/ACCOUNT.*SHADOW/i)).not.toHaveLength(0);

    await userEvent.click(screen.getByRole("button", { name: "Monitoring" }));
    expect(await screen.findByText("Fallback rate")).toBeInTheDocument();
    expect(screen.getByText("Error rate")).toBeInTheDocument();
    expect(screen.getByText("Prediction quality")).toBeInTheDocument();
    expect(screen.getByText("Drift")).toBeInTheDocument();
    expect(screen.getByText("30-day failure risk")).toBeInTheDocument();
    expect(screen.getAllByText("37%")).toHaveLength(2);
    expect(screen.getByText(/MEDIUM \/ confidence 91%/i)).toBeInTheDocument();
    expect(screen.getByText("86%")).toBeInTheDocument();
    expect(screen.getByText("Active Power W Rolling Mean")).toBeInTheDocument();
    expect(screen.getByText(/Raises risk \/ value 212.4 \/ weight 0.18/i)).toBeInTheDocument();
    expect(screen.getByText("Voltage V Stddev")).toBeInTheDocument();
    expect(screen.getByText(/Lowers risk \/ value 2.1 \/ weight -0.08/i)).toBeInTheDocument();
    expect(screen.getByText(/2026-06-01T00:00:00Z - 2026-07-01T00:00:00Z/i)).toBeInTheDocument();

    expect(requests.some((request) => request.url.endsWith("/analytics/ml/datasets/external-csv") && request.method === "POST")).toBe(true);
    const internalDatasetRequest = requests.find((request) => request.url.endsWith("/analytics/ml/datasets/internal-telemetry") && request.method === "POST");
    expect(internalDatasetRequest).toBeDefined();
    expect(await internalDatasetRequest!.json()).toMatchObject({
      name: "Internal telemetry training set",
      deviceIds: ["00000000-0000-0000-0000-000000000001"],
      metrics: ["activePowerW", "voltageV", "currentA"],
      previewId: "prev-test-1",
      previewHash: "sha256:preview-test-1",
      modelFamily: "ANOMALY_DETECTION",
      horizonDays: null,
      metricSelectionMode: "COMMON",
    });
    const featureSnapshotRequests = requests.filter((request) => request.url.endsWith("/analytics/ml/datasets/feature-snapshots") && request.method === "POST");
    expect(featureSnapshotRequests).toHaveLength(2);
    expect(await featureSnapshotRequests[0].json()).toMatchObject({
      datasetVersionId: "dataset-version-1",
      rollingWindowSize: 3,
      featureTemplateId: "fridge-freezer",
      featureTemplateVersion: "template-v1",
      enabledFeatureFamilies: ["rolling_stats", "baseline_deviation", "missing_data", "time", "label"],
      modelFamily: "ANOMALY_DETECTION",
      horizonDays: null,
    });
    expect(await featureSnapshotRequests[1].json()).toMatchObject({
      datasetVersionId: "dataset-version-1",
      modelFamily: "FAILURE_PREDICTION",
      horizonDays: 30,
      enabledFeatureFamilies: ["rolling_stats", "baseline_deviation", "missing_data", "time", "device_context", "label"],
    });
    const trainingRequests = requests.filter((request) => request.url.endsWith("/analytics/ml/training-runs") && request.method === "POST");
    expect(trainingRequests).toHaveLength(2);
    expect(await trainingRequests[0].json()).toMatchObject({
      datasetVersionId: "dataset-version-1",
      featureSnapshotId: "feature-1",
      modelFamily: "ANOMALY_DETECTION",
      algorithm: "baseline-statistical-anomaly-v1",
    });
    expect(await trainingRequests[1].json()).toMatchObject({
      datasetVersionId: "dataset-version-1",
      featureSnapshotId: "feature-failure-1",
      modelFamily: "FAILURE_PREDICTION",
      algorithm: "sklearn-logistic-regression-failure-v1",
      failureHorizonDays: 30,
      minimumPositiveLabels: 4,
      minimumLabelCoverage: 0.75,
    });
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/labels/label-1") && request.method === "PUT")).toBe(true);
    const horizonLabelRequest = requests.find((request) => request.url.endsWith("/analytics/ml/datasets/horizon-labels") && request.method === "POST");
    expect(horizonLabelRequest).toBeDefined();
    expect(await horizonLabelRequest!.json()).toMatchObject({
      datasetVersionId: "dataset-version-1",
      horizonDays: 30,
      generationVersion: "failure-outcome-v1",
      includePreventiveMaintenance: false,
    });
  });

  it("keeps horizon label generation in processing status when the gateway times out", async () => {
    const requests: Request[] = [];
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.endsWith("/analytics/ml/datasets")) return json([datasetSummary]);
      if (url.includes("/analytics/ml/labels?datasetVersionId=dataset-version-1")) return json([]);
      if (url.endsWith("/analytics/ml/datasets/horizon-labels") && init?.method === "POST") {
        return errorJson(504, { error: "Gateway Timeout", path: "/analytics/ml/datasets/horizon-labels" });
      }
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Labels" }));
    await userEvent.click(await screen.findByRole("button", { name: /Generate horizon labels/i }));

    expect(await screen.findByText(/Label generation start timed out before a job id was returned/i)).toBeInTheDocument();
    expect(screen.queryByText(/Gateway Timeout for \/analytics\/ml\/datasets\/horizon-labels/i)).not.toBeInTheDocument();
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/datasets/horizon-labels") && request.method === "POST")).toBe(true);
  });

  it("checks active horizon label generation status and refreshes labels after completion", async () => {
    const requests: Request[] = [];
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.endsWith("/analytics/ml/datasets")) return json([datasetSummary]);
      if (url.includes("/analytics/ml/labels?datasetVersionId=dataset-version-1")) return json([labelItem]);
      if (url.endsWith("/analytics/ml/datasets/horizon-labels") && init?.method === "POST") return json({
        jobId: "label-job-1",
        datasetVersionId: "dataset-version-1",
        status: "RUNNING",
        submittedAt: "2026-07-10T00:00:00Z",
        startedAt: "2026-07-10T00:00:01Z",
        completedAt: null,
        progress: 40,
        message: "Generating horizon labels",
        failureReason: null,
        horizonDays: 30,
        generationVersion: "failure-outcome-v1",
        includePreventiveMaintenance: false,
        inputLineageHash: "sha256:data",
        result: null,
      });
      if (url.endsWith("/analytics/ml/datasets/horizon-labels/label-job-1")) return json({
        jobId: "label-job-1",
        datasetVersionId: "dataset-version-1",
        status: "COMPLETED",
        submittedAt: "2026-07-10T00:00:00Z",
        startedAt: "2026-07-10T00:00:01Z",
        completedAt: "2026-07-10T00:00:10Z",
        progress: 100,
        message: "Horizon label generation completed",
        failureReason: null,
        horizonDays: 30,
        generationVersion: "failure-outcome-v1",
        includePreventiveMaintenance: false,
        inputLineageHash: "sha256:data",
        result: {
          lineageHash: "sha256:labels",
          generatedLabels: 120,
          positiveLabels: 4,
          normalLabels: 100,
          maintenanceExcludedLabels: 6,
          unresolvedLabels: 2,
          unobservableRows: 8,
        },
      });
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Labels" }));
    await userEvent.click(await screen.findByRole("button", { name: /Generate horizon labels/i }));

    expect(await screen.findByText(/Running horizon label generation/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Check generation status/i }));
    expect(await screen.findByText(/Generated 120 labels/i)).toBeInTheDocument();
    expect(await screen.findByText("sha256:labels")).toBeInTheDocument();
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/datasets/horizon-labels/label-job-1") && request.method === "GET")).toBe(true);
  });

  it("recovers the last active horizon label job after page refresh", async () => {
    localStorage.setItem("voltcanary.horizonLabelJob.dataset-version-1.30.failure-outcome-v1.false", JSON.stringify({
      jobId: "label-job-1",
      datasetVersionId: "dataset-version-1",
      status: "RUNNING",
      submittedAt: "2026-07-10T00:00:00Z",
      startedAt: "2026-07-10T00:00:01Z",
      completedAt: null,
      progress: 40,
      message: "Generating horizon labels",
      failureReason: null,
      horizonDays: 30,
      generationVersion: "failure-outcome-v1",
      includePreventiveMaintenance: false,
      inputLineageHash: "sha256:data",
      result: null,
    }));
    const requests: Request[] = [];
    mockFetch((url) => {
      requests.push(new Request(url));
      if (url.endsWith("/analytics/ml/datasets")) return json([datasetSummary]);
      if (url.includes("/analytics/ml/labels?datasetVersionId=dataset-version-1")) return json([labelItem]);
      if (url.endsWith("/analytics/ml/datasets/horizon-labels/label-job-1")) return json({
        jobId: "label-job-1",
        datasetVersionId: "dataset-version-1",
        status: "COMPLETED",
        submittedAt: "2026-07-10T00:00:00Z",
        startedAt: "2026-07-10T00:00:01Z",
        completedAt: "2026-07-10T00:00:10Z",
        progress: 100,
        message: "Horizon label generation completed",
        failureReason: null,
        horizonDays: 30,
        generationVersion: "failure-outcome-v1",
        includePreventiveMaintenance: false,
        inputLineageHash: "sha256:data",
        result: {
          lineageHash: "sha256:labels",
          generatedLabels: 120,
          positiveLabels: 4,
          normalLabels: 100,
          maintenanceExcludedLabels: 6,
          unresolvedLabels: 2,
          unobservableRows: 8,
        },
      });
      if (url.endsWith("/analytics/ml/training-runs")) return json([]);
      if (url.endsWith("/analytics/ml/models")) return json([]);
      if (url.endsWith("/analytics/ml/deployment-policies")) return json([]);
      return json(accountSettings);
    });

    render(<MlSettingsPage />);
    await userEvent.click(screen.getByRole("button", { name: "Labels" }));

    expect(await screen.findByText(/Running horizon label generation \(40%\)/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Check generation status/i }));
    expect(await screen.findByText(/Generated 120 labels/i)).toBeInTheDocument();
    expect(requests.some((request) => request.url.endsWith("/analytics/ml/datasets/horizon-labels/label-job-1"))).toBe(true);
  });
});

async function loadDevice() {
  await screen.findByLabelText(/Device ID/i);
  await userEvent.type(screen.getByLabelText(/Device ID/i), "device-1");
  await userEvent.click(screen.getByRole("button", { name: /Load device/i }));
  await waitFor(() => expect(screen.getByDisplayValue("device-1")).toBeInTheDocument());
}

async function selectWorkbenchDevice() {
  await userEvent.click(await screen.findByRole("checkbox", { name: /Washer 001/i }));
}

function mockFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
    const url = input instanceof Request ? input.url : input.toString();
    const pathname = new URL(url).pathname;
    if (pathname.endsWith("/devices")) {
      return Promise.resolve(json([testDevice]));
    }
    if (pathname.endsWith("/device-types")) {
      return Promise.resolve(json([{ code: "washer", displayName: "Washer" }]));
    }
    return Promise.resolve(handler(url, init));
  });
}

function json(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function errorJson(status: number, value: unknown) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function impact(action: string, message: string, details: Record<string, unknown>, blocked = false) {
  return {
    action,
    blocked,
    message,
    dependencies: [{ type: action === "delete" ? "datasetVersion" : "lifecycleTarget", id: "impact-1", details }],
  };
}
