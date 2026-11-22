from app.schemas.inference import InferenceRequest, InferenceResponse, PredictionSignal
from app.services.inference_responses import InferenceResponseFactory
from app.services.inference_values import MetricValueExtractor, recommended_actions, risk_level
from app.services.model_registry_service import MODEL_NAME, MODEL_STATUS, MODEL_TYPE, MODEL_VERSION


class BaselineHeuristicScorer:
    def __init__(
        self,
        extractor: MetricValueExtractor | None = None,
        response_factory: InferenceResponseFactory | None = None,
    ) -> None:
        self.extractor = extractor or MetricValueExtractor()
        self.response_factory = response_factory or InferenceResponseFactory()

    def predict(self, request: InferenceRequest) -> InferenceResponse:
        metric_values = self.extractor.numeric_metrics(request.metrics)
        if not metric_values:
            return self.response_factory.insufficient_data_response(request)

        signals = self._signals(metric_values, request)
        anomaly_score = min(1.0, max((signal.score for signal in signals), default=0.05))
        degradation_score = min(1.0, self._degradation_score(metric_values, request, anomaly_score))
        confidence = self._confidence(request, metric_values)
        current_risk_level = risk_level(max(anomaly_score, degradation_score))
        explanations = [signal.explanation for signal in signals] or [
            "Telemetry is within expected heuristic thresholds."
        ]

        return InferenceResponse(
            deviceId=request.device_id,
            modelVersionId=None,
            modelName=MODEL_NAME,
            modelVersion=MODEL_VERSION,
            modelType=MODEL_TYPE,
            modelStatus=MODEL_STATUS,
            predictionStatus="BASELINE_HEURISTIC",
            anomalyScore=round(anomaly_score, 4),
            degradationScore=round(degradation_score, 4),
            riskLevel=current_risk_level,
            confidence=round(confidence, 4),
            signals=signals,
            explanations=explanations,
            recommendedActions=recommended_actions(current_risk_level, signals),
            inputHash=self.response_factory.input_hash(request),
        )

    def _signals(
        self, values: dict[str, float], request: InferenceRequest
    ) -> list[PredictionSignal]:
        signals: list[PredictionSignal] = []
        for metric, value in values.items():
            score, explanation = self._absolute_signal(metric, value)
            baseline_score, baseline_explanation = self._baseline_signal(metric, value, request)
            if baseline_score > score:
                score = baseline_score
                explanation = baseline_explanation
            if score >= 0.15:
                signals.append(
                    PredictionSignal(
                        metric=metric,
                        score=round(score, 4),
                        severity=risk_level(score),
                        explanation=explanation,
                    )
                )
        if not signals:
            metric_names = ", ".join(sorted(values))
            signals.append(
                PredictionSignal(
                    metric="telemetry",
                    score=0.05,
                    severity="LOW",
                    explanation=f"Metrics {metric_names} are within expected heuristic ranges.",
                )
            )
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

    def _baseline_signal(
        self, metric: str, value: float, request: InferenceRequest
    ) -> tuple[float, str]:
        baseline = request.baseline
        if baseline is None:
            return 0.0, ""
        if (
            baseline.metric
            and baseline.metric != metric
            and request.metrics.get("metric") != metric
        ):
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

    def _degradation_score(
        self, values: dict[str, float], request: InferenceRequest, anomaly_score: float
    ) -> float:
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
