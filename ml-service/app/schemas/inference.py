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
    allow_baseline_heuristic: bool = Field(default=True, alias="allowBaselineHeuristic")

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
    model_name: str = Field(alias="modelName")
    model_version: str = Field(alias="modelVersion")
    model_type: str = Field(alias="modelType")
    model_status: str = Field(alias="modelStatus")
    prediction_status: str = Field(alias="predictionStatus")
    anomaly_score: float = Field(alias="anomalyScore")
    degradation_score: float = Field(alias="degradationScore")
    risk_level: str = Field(alias="riskLevel")
    confidence: float
    signals: list[PredictionSignal]
    explanations: list[str]
    recommended_actions: list[str] = Field(alias="recommendedActions")
    input_hash: str = Field(alias="inputHash")

    model_config = ConfigDict(populate_by_name=True)
