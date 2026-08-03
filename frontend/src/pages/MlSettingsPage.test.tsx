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

afterEach(() => {
  cleanup();
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

    expect(await screen.findByText(expectedText)).toBeInTheDocument();
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
    await userEvent.click(await screen.findByRole("button", { name: /Failure prediction/i }));
    expect(screen.getByText("Outcome supervised")).toBeInTheDocument();
    expect(screen.getByText(/Generate horizon labels for the same dataset and horizon/i)).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText(/Prediction horizon/i));
    await userEvent.type(screen.getByLabelText(/Prediction horizon/i), "30");
    await userEvent.clear(screen.getByLabelText(/Minimum positive labels/i));
    await userEvent.type(screen.getByLabelText(/Minimum positive labels/i), "4");
    await userEvent.clear(screen.getByLabelText(/Minimum label coverage/i));
    await userEvent.type(screen.getByLabelText(/Minimum label coverage/i), "0.75");
    await userEvent.click(screen.getByRole("button", { name: /Queue training/i }));

    const trainingRequest = requests.find((request) => request.url.endsWith("/analytics/ml/training-runs") && request.method === "POST");
    expect(trainingRequest).toBeDefined();
    expect(await trainingRequest!.json()).toMatchObject({
      modelFamily: "FAILURE_PREDICTION",
      failureHorizonDays: 30,
      minimumPositiveLabels: 4,
      minimumLabelCoverage: 0.75,
    });

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

  it("supports dataset, import, training, registry, and deployment workflows", async () => {
    const requests: Request[] = [];
    let importedDatasetVisible = false;
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.includes("/analytics/devices/device-1/predictions/latest")) return json(readyPrediction);
      if (url.endsWith("/analytics/ml-settings/devices/device-1")) return json(inheritedSettings);
      if (url.endsWith("/analytics/ml/datasets")) return json(importedDatasetVisible ? [importedDatasetSummary, datasetSummary] : [datasetSummary]);
      if (url.endsWith("/analytics/ml/datasets/versions/dataset-version-1")) return json(datasetDetail);
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
      if (url.endsWith("/analytics/ml/datasets/feature-snapshots")) return json({
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
        featureTemplateId: "fridge-freezer",
        featureTemplateVersion: "template-v1",
        createdAt: "2026-07-10T00:00:00Z",
      });
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
        datasetVersionId: "dataset-version-1",
        horizonDays: 30,
        generationVersion: "failure-outcome-v1",
        lineageHash: "sha256:labels",
        generatedLabels: 120,
        positiveLabels: 4,
        normalLabels: 100,
        maintenanceExcludedLabels: 6,
        unresolvedLabels: 2,
        unobservableRows: 8,
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
    await userEvent.type(await screen.findByLabelText(/Device IDs/i), "00000000-0000-0000-0000-000000000001");
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
    await userEvent.click(screen.getByRole("button", { name: /Failure prediction/i }));
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
    });
    const featureSnapshotRequest = requests.find((request) => request.url.endsWith("/analytics/ml/datasets/feature-snapshots") && request.method === "POST");
    expect(featureSnapshotRequest).toBeDefined();
    expect(await featureSnapshotRequest!.json()).toMatchObject({
      datasetVersionId: "dataset-version-1",
      rollingWindowSize: 3,
      featureTemplateId: "fridge-freezer",
      featureTemplateVersion: "template-v1",
      enabledFeatureFamilies: ["rolling_stats", "baseline_deviation", "missing_data", "time", "label"],
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
      featureSnapshotId: "feature-1",
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
});

async function loadDevice() {
  await screen.findByLabelText(/Device ID/i);
  await userEvent.type(screen.getByLabelText(/Device ID/i), "device-1");
  await userEvent.click(screen.getByRole("button", { name: /Load device/i }));
  await waitFor(() => expect(screen.getByDisplayValue("device-1")).toBeInTheDocument());
}

function mockFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
    const url = input instanceof Request ? input.url : input.toString();
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
