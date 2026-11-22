from typing import Any

from app.models.tabular_anomaly_model import TabularAnomalyModel
from app.schemas.inference import InferenceRequest, InferenceResponse, PredictionSignal
from app.services.inference_responses import InferenceResponseFactory
from app.services.inference_values import recommended_actions, risk_level


class AnomalyModelScorer:
    def __init__(self, response_factory: InferenceResponseFactory | None = None) -> None:
        self.response_factory = response_factory or InferenceResponseFactory()

    def predict(
        self,
        request: InferenceRequest,
        model: dict[str, Any],
        artifact: dict[str, Any],
        pipeline: Any,
        metric_values: dict[str, float],
    ) -> InferenceResponse | None:
        model_scores = TabularAnomalyModel.score_with_pipeline(pipeline, metric_values)
        metric_scores = model_scores["metricScores"]
        scores = list(metric_scores.values())
        signals: list[PredictionSignal] = []
        for metric, score in metric_scores.items():
            value = metric_values[metric]
            stats = artifact.get("metricStats", {}).get(metric)
            if stats is None:
                continue
            if score >= 0.15:
                signals.append(
                    PredictionSignal(
                        metric=metric,
                        score=round(score, 4),
                        severity=risk_level(score),
                        explanation=f"{metric}={value:g} was scored against trained model {model['version']}.",
                    )
                )

        if not scores:
            if request.model_preference and not request.model_preference.allow_baseline_heuristic:
                return self.response_factory.model_incompatible_response(request, model)
            return None

        anomaly_score = min(1.0, max(scores))
        current_risk_level = risk_level(anomaly_score)
        if not signals:
            signals.append(
                PredictionSignal(
                    metric="telemetry",
                    score=round(anomaly_score, 4),
                    severity=current_risk_level,
                    explanation=f"Telemetry matched trained model {model['version']} within expected range.",
                )
            )
        return InferenceResponse(
            deviceId=request.device_id,
            modelVersionId=model.get("modelVersionId"),
            modelName=model["name"],
            modelVersion=model["version"],
            modelType=model["modelType"],
            modelStatus=model["status"],
            predictionStatus="TRAINED_MODEL",
            anomalyScore=round(anomaly_score, 4),
            degradationScore=round(anomaly_score * 0.75, 4),
            riskLevel=current_risk_level,
            confidence=round(min(0.95, 0.7 + 0.04 * len(scores)), 4),
            signals=signals,
            explanations=[signal.explanation for signal in signals],
            recommendedActions=recommended_actions(current_risk_level, signals),
            inputHash=self.response_factory.input_hash(request),
        )
