from datetime import datetime
import math
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator


class BaselineSummary(BaseModel):
    mean: float | None = None
    median: float | None = None
    p90: float | None = None
    min: float | None = None
    max: float | None = None
    sample_count: int | None = Field(default=None, alias="sampleCount")
    metric: str | None = None

    model_config = ConfigDict(populate_by_name=True)


class RecentWindowSummary(BaseModel):
    sample_count: int | None = Field(default=None, alias="sampleCount")
    excluded_sample_count: int | None = Field(default=None, alias="excludedSampleCount")
    window_start: datetime | None = Field(default=None, alias="windowStart")
    window_end: datetime | None = Field(default=None, alias="windowEnd")

    model_config = ConfigDict(populate_by_name=True)


class ModelPreference(BaseModel):
    model_name: str | None = Field(default=None, alias="modelName")
    model_version_id: str | None = Field(default=None, alias="modelVersionId")
    model_family: str | None = Field(default=None, alias="modelFamily")
    model_version: str | None = Field(default=None, alias="modelVersion")
    model_type: str | None = Field(default=None, alias="modelType")
    model_status: str | None = Field(default=None, alias="modelStatus")
    artifact_uri: str | None = Field(default=None, alias="artifactUri")
    manifest_uri: str | None = Field(default=None, alias="manifestUri")
    artifact_hash: str | None = Field(default=None, alias="artifactHash")
    allow_baseline_heuristic: bool = Field(default=True, alias="allowBaselineHeuristic")
    required_feature_schema_hash: str | None = Field(default=None, alias="requiredFeatureSchemaHash")
    required_feature_contract_version: str | None = Field(default=None, alias="requiredFeatureContractVersion")
    required_feature_contract_metadata_hash: str | None = Field(default=None, alias="requiredFeatureContractMetadataHash")
    required_feature_set_version: str | None = Field(default=None, alias="requiredFeatureSetVersion")
    required_feature_generator_version: str | None = Field(default=None, alias="requiredFeatureGeneratorVersion")
    required_feature_template_id: str | None = Field(default=None, alias="requiredFeatureTemplateId")
    required_feature_template_version: str | None = Field(default=None, alias="requiredFeatureTemplateVersion")
    required_horizon_days: int | None = Field(default=None, alias="requiredHorizonDays")

    model_config = ConfigDict(populate_by_name=True)


class PredictionSignal(BaseModel):
    metric: str
    score: float
    severity: str
    explanation: str


class InferenceRequest(BaseModel):
    device_id: str = Field(alias="deviceId")
    timestamp: datetime | None = None
    metrics: dict[str, Any] = Field(default_factory=dict)
    baseline: BaselineSummary | None = None
    recent_window: RecentWindowSummary | None = Field(default=None, alias="recentWindow")
    excluded_intervals: list[dict[str, Any]] = Field(default_factory=list, alias="excludedIntervals")
    model_preference: ModelPreference | None = Field(default=None, alias="modelPreference")
    feature_vector: dict[str, Any] = Field(default_factory=dict, alias="featureVector")
    feature_contract: dict[str, Any] = Field(default_factory=dict, alias="featureContract")
    feature_generation_status: str | None = Field(default=None, alias="featureGenerationStatus")
    feature_generation_errors: list[str] = Field(default_factory=list, alias="featureGenerationErrors")

    model_config = ConfigDict(populate_by_name=True)

    @field_validator("metrics")
    @classmethod
    def metrics_must_not_contain_non_finite_numbers(cls, value: dict[str, Any]) -> dict[str, Any]:
        def validate_number(item: Any) -> None:
            if isinstance(item, float) and not math.isfinite(item):
                raise ValueError("metrics must not contain non-finite numeric values")
            if isinstance(item, dict):
                for nested in item.values():
                    validate_number(nested)
            if isinstance(item, list):
                for nested in item:
                    validate_number(nested)

        validate_number(value)
        return value


class InferenceResponse(BaseModel):
    device_id: str = Field(alias="deviceId")
    model_version_id: str | None = Field(default=None, alias="modelVersionId")
    model_family: str | None = Field(default=None, alias="modelFamily")
    model_name: str = Field(alias="modelName")
    model_version: str = Field(alias="modelVersion")
    model_type: str = Field(alias="modelType")
    model_status: str = Field(alias="modelStatus")
    prediction_status: str = Field(alias="predictionStatus")
    anomaly_score: float = Field(alias="anomalyScore")
    degradation_score: float = Field(alias="degradationScore")
    failure_risk_score: float | None = Field(default=None, alias="failureRiskScore")
    failure_horizon_days: int | None = Field(default=None, alias="failureHorizonDays")
    failure_risk_band: str | None = Field(default=None, alias="failureRiskBand")
    risk_level: str = Field(alias="riskLevel")
    confidence: float
    risk_drivers: list[dict[str, Any]] = Field(default_factory=list, alias="riskDrivers")
    label_coverage: float | None = Field(default=None, alias="labelCoverage")
    training_data_window: dict[str, Any] = Field(default_factory=dict, alias="trainingDataWindow")
    signals: list[PredictionSignal]
    explanations: list[str]
    recommended_actions: list[str] = Field(alias="recommendedActions")
    input_hash: str = Field(alias="inputHash")

    model_config = ConfigDict(populate_by_name=True)
