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


class TrainingRequest(BaseModel):
    dataset_version_id: str = Field(alias="datasetVersionId")
    account_id: str = Field(alias="accountId")
    source_type: str = Field(alias="sourceType")
    model_family: str = Field(default="ANOMALY_DETECTION", alias="modelFamily")
    algorithm: str = "baseline-statistical-anomaly-v1"
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
    model_version: str | None = Field(default=None, alias="modelVersion")
    artifact_uri: str | None = Field(default=None, alias="artifactUri")
    metrics: dict[str, float] = Field(default_factory=dict)
    feature_snapshot: FeatureSnapshotResponse | None = Field(default=None, alias="featureSnapshot")
    logs_summary: list[str] = Field(default_factory=list, alias="logsSummary")
    failure_reason: str | None = Field(default=None, alias="failureReason")

    model_config = ConfigDict(populate_by_name=True)
