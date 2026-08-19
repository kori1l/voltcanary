from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class MetricRow(BaseModel):
    timestamp: datetime
    device_id: str = Field(alias="deviceId")
    metric: str
    value: float
    unit: str | None = None
    label: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)


class ApprovalGateProfile(BaseModel):
    profile_id: str = Field(default="failure-production-default", alias="profileId")
    profile_version: str = Field(default="2026-08-approval-v1", alias="profileVersion")
    threshold_source: str = Field(default="ml-service-default-config", alias="thresholdSource")
    minimum_positive_labels: int = Field(default=1, alias="minimumPositiveLabels")
    minimum_label_coverage: float = Field(default=0.8, alias="minimumLabelCoverage")
    minimum_precision: float = Field(default=0.6, alias="minimumPrecision")
    minimum_recall: float = Field(default=0.6, alias="minimumRecall")
    minimum_pr_auc: float = Field(default=0.6, alias="minimumPrAuc")
    minimum_pr_auc_lift: float = Field(default=0.0, alias="minimumPrAucLift")
    maximum_false_alerts_per_device_month: float = Field(default=10.0, alias="maximumFalseAlertsPerDeviceMonth")
    minimum_lead_time_days: float = Field(default=1.0, alias="minimumLeadTimeDays")
    require_artifact_load_test: bool = Field(default=True, alias="requireArtifactLoadTest")
    require_feature_schema_compatibility: bool = Field(default=True, alias="requireFeatureSchemaCompatibility")

    model_config = ConfigDict(populate_by_name=True)


class TrainingRequest(BaseModel):
    training_run_id: str | None = Field(default=None, alias="trainingRunId")
    idempotency_key: str | None = Field(default=None, alias="idempotencyKey")
    artifact_bundle_id: str | None = Field(default=None, alias="artifactBundleId")
    dataset_version_id: str = Field(alias="datasetVersionId")
    account_id: str = Field(alias="accountId")
    source_type: str = Field(alias="sourceType")
    model_family: str = Field(default="ANOMALY_DETECTION", alias="modelFamily")
    algorithm: str = "baseline-statistical-anomaly-v1"
    failure_horizon_days: int | None = Field(default=None, alias="failureHorizonDays")
    minimum_positive_labels: int | None = Field(default=None, alias="minimumPositiveLabels")
    minimum_label_coverage: float | None = Field(default=None, alias="minimumLabelCoverage")
    approval_gate_profile: ApprovalGateProfile | None = Field(default=None, alias="approvalGateProfile")
    cancellation_requested: bool = Field(default=False, alias="cancellationRequested")
    training_timeout_seconds: float | None = Field(default=None, alias="trainingTimeoutSeconds")
    rows: list[MetricRow]

    model_config = ConfigDict(populate_by_name=True)


class FeatureSnapshotResponse(BaseModel):
    feature_snapshot_id: str = Field(alias="featureSnapshotId")
    dataset_version_id: str = Field(alias="datasetVersionId")
    row_count: int = Field(alias="rowCount")
    feature_schema_hash: str = Field(alias="featureSchemaHash")
    artifact_uri: str = Field(alias="artifactUri")

    model_config = ConfigDict(populate_by_name=True)


class TrainingRunResponse(BaseModel):
    run_id: str = Field(alias="runId")
    status: str
    dataset_version_id: str = Field(alias="datasetVersionId")
    training_run_id: str | None = Field(default=None, alias="trainingRunId")
    idempotency_key: str | None = Field(default=None, alias="idempotencyKey")
    artifact_bundle_id: str | None = Field(default=None, alias="artifactBundleId")
    model_version_id: str | None = Field(default=None, alias="modelVersionId")
    model_version: str | None = Field(default=None, alias="modelVersion")
    artifact_uri: str | None = Field(default=None, alias="artifactUri")
    manifest_uri: str | None = Field(default=None, alias="manifestUri")
    artifact_hash: str | None = Field(default=None, alias="artifactHash")
    artifact_size_bytes: int | None = Field(default=None, alias="artifactSizeBytes")
    metrics: dict[str, Any] = Field(default_factory=dict)
    feature_snapshot: FeatureSnapshotResponse | None = Field(default=None, alias="featureSnapshot")
    logs_summary: list[str] = Field(default_factory=list, alias="logsSummary")
    failure_reason: str | None = Field(default=None, alias="failureReason")

    model_config = ConfigDict(populate_by_name=True)
