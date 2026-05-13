from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field


class InferenceRequest(BaseModel):
    device_id: str = Field(alias="deviceId")
    timestamp: datetime | None = None
    metrics: dict[str, Any] = Field(default_factory=dict)


class InferenceResponse(BaseModel):
    device_id: str = Field(alias="deviceId")
    model_version: str = Field(alias="modelVersion")
    anomaly_score: float = Field(alias="anomalyScore")
    degradation_score: float = Field(alias="degradationScore")
    risk_level: str = Field(alias="riskLevel")
    confidence: float
    explanations: list[str]
