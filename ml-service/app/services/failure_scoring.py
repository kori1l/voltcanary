from typing import Any

from app.models.failure_prediction_model import FailurePredictionModel
from app.schemas.inference import InferenceRequest, InferenceResponse, PredictionSignal
from app.services.inference_responses import InferenceResponseFactory
from app.services.inference_values import recommended_actions


class FailureModelScorer:
    def __init__(self, response_factory: InferenceResponseFactory | None = None) -> None:
        self.response_factory = response_factory or InferenceResponseFactory()

    def predict(
        self,
        request: InferenceRequest,
        model: dict[str, Any],
        artifact: dict[str, Any],
        pipeline: Any,
        metric_values: dict[str, float],
    ) -> InferenceResponse:
        label_response = self.label_gate_response(request, model, artifact)
        if label_response is not None:
            return label_response
        feature_response = self.feature_response(request, model, artifact, metric_values)
        if feature_response is not None:
            return feature_response
        return self._predict_with_model(request, model, artifact, pipeline, metric_values)

    def _predict_with_model(
        self,
        request: InferenceRequest,
        model: dict[str, Any],
        artifact: dict[str, Any],
        pipeline: Any,
        metric_values: dict[str, float],
    ) -> InferenceResponse:
        score = FailurePredictionModel.score_with_pipeline(pipeline, metric_values)
        failure_risk_score = round(float(score["failureRiskScore"]), 4)
        failure_risk_band = str(score["failureRiskBand"])
        risk_drivers = score["riskDrivers"]
        metrics = artifact.get("metrics") or {}
        algorithm = str(artifact.get("algorithm") or "supervised failure classifier")
        return InferenceResponse(
            deviceId=request.device_id,
            modelVersionId=model.get("modelVersionId"),
            modelFamily="FAILURE_PREDICTION",
            modelName=model["name"],
            modelVersion=model["version"],
            modelType=model["modelType"],
            modelStatus=model["status"],
            predictionStatus="TRAINED_MODEL",
            anomalyScore=0.0,
            degradationScore=0.0,
            failureRiskScore=failure_risk_score,
            failureHorizonDays=artifact.get("failureHorizonDays"),
            failureRiskBand=failure_risk_band,
            riskLevel=failure_risk_band,
            confidence=self._failure_confidence(artifact, metric_values),
            riskDrivers=risk_drivers,
            labelCoverage=metrics.get("labelCoverage"),
            trainingDataWindow={
                "datasetVersionId": artifact.get("datasetVersionId"),
                "trainingSamples": artifact.get("trainingSamples"),
                "positiveLabels": artifact.get("positiveLabels"),
                "negativeLabels": artifact.get("negativeLabels"),
                "evaluationStatus": (artifact.get("evaluation") or {}).get("status"),
                "approvalStatus": (artifact.get("approvalGates") or {}).get("status"),
            },
            signals=[
                PredictionSignal(
                    metric=driver["feature"],
                    score=min(1.0, abs(float(driver["contribution"]))),
                    severity=failure_risk_band,
                    explanation=f"{driver['feature']} contributed to {failure_risk_band} failure risk.",
                )
                for driver in risk_drivers[:3]
            ],
            explanations=[
                f"Failure risk is estimated for the next {artifact.get('failureHorizonDays')} days using {algorithm}."
            ],
            recommendedActions=recommended_actions(failure_risk_band, []),
            inputHash=self.response_factory.input_hash(request),
        )

    def label_gate_response(
        self,
        request: InferenceRequest,
        model: dict[str, Any],
        artifact: dict[str, Any],
    ) -> InferenceResponse | None:
        approval = artifact.get("approvalGates") or {}
        failed_label_gates = [
            gate
            for gate in approval.get("criteria", [])
            if gate.get("name") in {"minimum_positive_labels", "minimum_label_coverage"}
            and not gate.get("passed", False)
        ]
        if not failed_label_gates:
            return None
        reasons = [str(gate.get("reason") or gate["name"]) for gate in failed_label_gates]
        return InferenceResponse(
            deviceId=request.device_id,
            modelVersionId=model.get("modelVersionId"),
            modelFamily="FAILURE_PREDICTION",
            modelName=model["name"],
            modelVersion=model["version"],
            modelType=model["modelType"],
            modelStatus=model["status"],
            predictionStatus="INSUFFICIENT_LABELS",
            anomalyScore=0.0,
            degradationScore=0.0,
            riskLevel="LOW",
            confidence=0.1,
            labelCoverage=(artifact.get("metrics") or {}).get("labelCoverage"),
            trainingDataWindow={
                "datasetVersionId": artifact.get("datasetVersionId"),
                "trainingSamples": artifact.get("trainingSamples"),
                "positiveLabels": artifact.get("positiveLabels"),
                "negativeLabels": artifact.get("negativeLabels"),
                "approvalStatus": approval.get("status"),
            },
            signals=[],
            explanations=reasons,
            recommendedActions=[
                "Retrain with sufficient outcome labels before serving this failure model."
            ],
            inputHash=self.response_factory.input_hash(request),
        )

    def feature_response(
        self,
        request: InferenceRequest,
        model: dict[str, Any],
        artifact: dict[str, Any],
        metric_values: dict[str, float],
    ) -> InferenceResponse | None:
        feature_columns = [str(column) for column in artifact.get("featureColumns", [])]
        missing_features = sorted(
            column for column in feature_columns if column not in metric_values
        )
        if not missing_features:
            return None
        return InferenceResponse(
            deviceId=request.device_id,
            modelVersionId=model.get("modelVersionId"),
            modelFamily="FAILURE_PREDICTION",
            modelName=model["name"],
            modelVersion=model["version"],
            modelType=model["modelType"],
            modelStatus=model["status"],
            predictionStatus="FEATURES_UNAVAILABLE",
            anomalyScore=0.0,
            degradationScore=0.0,
            riskLevel="LOW",
            confidence=0.1,
            signals=[],
            explanations=[
                f"Missing required failure model features: {', '.join(missing_features)}."
            ],
            recommendedActions=[
                "Regenerate online features using the selected model feature contract."
            ],
            inputHash=self.response_factory.input_hash(request),
        )

    def _failure_confidence(
        self, artifact: dict[str, Any], metric_values: dict[str, float]
    ) -> float:
        metrics = artifact.get("metrics") or {}
        confidence = 0.55 + 0.03 * len(metric_values)
        confidence += min(0.2, float(metrics.get("labelCoverage") or 0.0) * 0.2)
        if (artifact.get("evaluation") or {}).get("status") == "EVALUATED":
            confidence += 0.08
        if (artifact.get("approvalGates") or {}).get("promotionEligible"):
            confidence += 0.05
        return round(min(0.95, max(0.35, confidence)), 4)
