import hashlib
import json
import math
from typing import Any

from app.schemas.inference import InferenceRequest, InferenceResponse, PredictionSignal


MODEL_NAME = "voltcanary-baseline-heuristic"
MODEL_VERSION = "baseline-heuristic-v1"
MODEL_TYPE = "baseline_heuristic"
MODEL_STATUS = "READY_HEURISTIC"


class InferenceService:
    def predict(self, request: InferenceRequest) -> InferenceResponse:
        metric_values = self._numeric_metrics(request.metrics)
        input_hash = self._input_hash(request)
        if not metric_values:
            return InferenceResponse(
                deviceId=request.device_id,
                modelName=MODEL_NAME,
                modelVersion=MODEL_VERSION,
                modelType=MODEL_TYPE,
                modelStatus=MODEL_STATUS,
                predictionStatus="INSUFFICIENT_DATA",
                anomalyScore=0.0,
                degradationScore=0.0,
                riskLevel="LOW",
                confidence=0.2,
                signals=[],
                explanations=["Not enough telemetry input to produce a meaningful prediction."],
                recommendedActions=["Collect additional telemetry before relying on ML prediction output."],
                inputHash=input_hash,
            )

        signals = self._signals(metric_values, request)
        anomaly_score = min(1.0, max((signal.score for signal in signals), default=0.05))
        degradation_score = min(1.0, self._degradation_score(metric_values, request, anomaly_score))
        confidence = self._confidence(request, metric_values)
        risk_level = self._risk_level(max(anomaly_score, degradation_score))
        explanations = [signal.explanation for signal in signals] or ["Telemetry is within expected heuristic thresholds."]

        return InferenceResponse(
            deviceId=request.device_id,
            modelName=MODEL_NAME,
            modelVersion=MODEL_VERSION,
            modelType=MODEL_TYPE,
            modelStatus=MODEL_STATUS,
            predictionStatus="BASELINE_HEURISTIC",
            anomalyScore=round(anomaly_score, 4),
            degradationScore=round(degradation_score, 4),
            riskLevel=risk_level,
            confidence=round(confidence, 4),
            signals=signals,
            explanations=explanations,
            recommendedActions=self._recommended_actions(risk_level, signals),
            inputHash=input_hash,
        )

    def _numeric_metrics(self, metrics: dict[str, Any]) -> dict[str, float]:
        values: dict[str, float] = {}
        for key, value in metrics.items():
            if isinstance(value, bool):
                values[key] = 1.0 if value else 0.0
            elif isinstance(value, int | float) and math.isfinite(float(value)):
                values[key] = float(value)
        return values

    def _signals(self, values: dict[str, float], request: InferenceRequest) -> list[PredictionSignal]:
        signals: list[PredictionSignal] = []
        for metric, value in values.items():
            score, explanation = self._absolute_signal(metric, value)
            baseline_score, baseline_explanation = self._baseline_signal(metric, value, request)
            if baseline_score > score:
                score = baseline_score
                explanation = baseline_explanation
            if score >= 0.15:
                signals.append(PredictionSignal(
                    metric=metric,
                    score=round(score, 4),
                    severity=self._risk_level(score),
                    explanation=explanation,
                ))
        if not signals:
            metric_names = ", ".join(sorted(values))
            signals.append(PredictionSignal(
                metric="telemetry",
                score=0.05,
                severity="LOW",
                explanation=f"Metrics {metric_names} are within expected heuristic ranges.",
            ))
        return signals

    def _absolute_signal(self, metric: str, value: float) -> tuple[float, str]:
        normalized = metric.lower()
        if "voltage" in normalized:
            if value < 180 or value > 260:
                return 0.85, f"{metric}={value:g} is outside the safe voltage range."
            if value < 210 or value > 245:
                return 0.45, f"{metric}={value:g} is near the voltage warning range."
        if "current" in normalized:
            if value > 32:
                return 0.85, f"{metric}={value:g} exceeds the high current threshold."
            if value > 20:
                return 0.45, f"{metric}={value:g} is above the current warning threshold."
        if "power" in normalized:
            if value > 5000:
                return 0.9, f"{metric}={value:g} indicates a severe power spike."
            if value > 2500:
                return 0.5, f"{metric}={value:g} is above the power warning threshold."
        if "energy" in normalized and ("growth" in normalized or "delta" in normalized):
            if value > 3:
                return 0.8, f"{metric}={value:g} indicates abnormal energy growth."
            if value > 1.5:
                return 0.4, f"{metric}={value:g} indicates elevated energy growth."
        if "rssi" in normalized:
            if value < -90:
                return 0.55, f"{metric}={value:g} indicates weak connectivity."
        if "relay" in normalized and value not in (0, 1):
            return 0.6, f"{metric}={value:g} is not a normal relay state."
        return 0.05, f"{metric}={value:g} is within absolute heuristic thresholds."

    def _baseline_signal(self, metric: str, value: float, request: InferenceRequest) -> tuple[float, str]:
        baseline = request.baseline
        if baseline is None:
            return 0.0, ""
        if baseline.metric and baseline.metric != metric and request.metrics.get("metric") != metric:
            return 0.0, ""
        baseline_max = baseline.max
        baseline_min = baseline.min
        baseline_mean = baseline.mean
        if baseline_max is not None and value > baseline_max * 1.75:
            return 0.8, f"{metric}={value:g} is more than 75% above the recent baseline maximum."
        if baseline_min is not None and baseline_min > 0 and value < baseline_min * 0.5:
            return 0.6, f"{metric}={value:g} is less than half of the recent baseline minimum."
        if baseline_mean is not None and baseline_mean > 0:
            ratio = abs(value - baseline_mean) / baseline_mean
            if ratio > 1.0:
                return 0.75, f"{metric}={value:g} deviates more than 100% from baseline mean."
            if ratio > 0.5:
                return 0.35, f"{metric}={value:g} deviates more than 50% from baseline mean."
        return 0.0, ""

    def _degradation_score(self, values: dict[str, float], request: InferenceRequest, anomaly_score: float) -> float:
        score = anomaly_score * 0.7
        sample_count = request.recent_window.sample_count if request.recent_window else None
        if sample_count is not None and sample_count < 5:
            score += 0.1
        if request.excluded_intervals:
            score += 0.05
        if "riskScore" in values:
            score = max(score, min(1.0, values["riskScore"] / 100.0))
        return min(1.0, score)

    def _confidence(self, request: InferenceRequest, values: dict[str, float]) -> float:
        confidence = 0.82
        if request.baseline is None:
            confidence -= 0.18
        if request.recent_window is None:
            confidence -= 0.08
        if len(values) < 3:
            confidence -= 0.08
        if request.excluded_intervals:
            confidence -= min(0.25, 0.08 * len(request.excluded_intervals))
        return max(0.15, min(0.95, confidence))

    def _risk_level(self, score: float) -> str:
        if score >= 0.85:
            return "CRITICAL"
        if score >= 0.65:
            return "HIGH"
        if score >= 0.35:
            return "MEDIUM"
        return "LOW"

    def _recommended_actions(self, risk_level: str, signals: list[PredictionSignal]) -> list[str]:
        if risk_level in {"HIGH", "CRITICAL"}:
            return ["Inspect device load and electrical conditions for the signaled metrics."]
        if risk_level == "MEDIUM":
            return ["Monitor signaled metrics and compare against the next baseline window."]
        return ["Continue normal monitoring."]

    def _input_hash(self, request: InferenceRequest) -> str:
        payload = request.model_dump(mode="json", by_alias=True)
        serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(serialized.encode("utf-8")).hexdigest()
