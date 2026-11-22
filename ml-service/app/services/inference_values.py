import math
from typing import Any

from app.schemas.inference import PredictionSignal


def risk_level(score: float) -> str:
    if score >= 0.85:
        return "CRITICAL"
    if score >= 0.65:
        return "HIGH"
    if score >= 0.35:
        return "MEDIUM"
    return "LOW"


def recommended_actions(current_risk_level: str, signals: list[PredictionSignal]) -> list[str]:
    if current_risk_level in {"HIGH", "CRITICAL"}:
        return ["Inspect device load and electrical conditions for the signaled metrics."]
    if current_risk_level == "MEDIUM":
        return ["Monitor signaled metrics and compare against the next baseline window."]
    return ["Continue normal monitoring."]


class MetricValueExtractor:
    def numeric_metrics(self, metrics: dict[str, Any]) -> dict[str, float]:
        values: dict[str, float] = {}
        for key, value in metrics.items():
            if isinstance(value, bool):
                values[key] = 1.0 if value else 0.0
            elif isinstance(value, int | float) and math.isfinite(float(value)):
                values[key] = float(value)
        return values
