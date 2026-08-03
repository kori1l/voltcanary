import hashlib
import json
import math
from typing import Any

from app.models.failure_prediction_model import FailurePredictionModel
from app.models.tabular_anomaly_model import TabularAnomalyModel
from app.schemas.inference import InferenceRequest, InferenceResponse, PredictionSignal
from app.services.artifact_store import ArtifactStore
from app.services.model_registry_service import MODEL_NAME, MODEL_STATUS, MODEL_TYPE, MODEL_VERSION, model_registry_service


class InferenceService:
    def __init__(self, artifact_store: ArtifactStore | None = None) -> None:
        self.artifact_store = artifact_store or ArtifactStore()

    def predict(self, request: InferenceRequest) -> InferenceResponse:
        feature_error_response = self._feature_error_response(request)
        if feature_error_response is not None:
            return feature_error_response

        routed_response = self._predict_with_trained_model(request)
        if routed_response is not None:
            return routed_response

        metric_values = self._numeric_metrics(request.metrics)
        input_hash = self._input_hash(request)
        if not metric_values:
            return InferenceResponse(
                deviceId=request.device_id,
                modelVersionId=None,
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
            modelVersionId=None,
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

    def _feature_error_response(self, request: InferenceRequest) -> InferenceResponse | None:
        preference = request.model_preference
        if preference is None or preference.allow_baseline_heuristic:
            return None

        input_hash = self._input_hash(request)
        if request.feature_generation_status and request.feature_generation_status != "READY":
            details = request.feature_generation_errors or ["Online features are unavailable for the active prediction window."]
            return InferenceResponse(
                deviceId=request.device_id,
                modelVersionId=None,
                modelName=MODEL_NAME,
                modelVersion=MODEL_VERSION,
                modelType=MODEL_TYPE,
                modelStatus=MODEL_STATUS,
                predictionStatus="FEATURES_UNAVAILABLE",
                anomalyScore=0.0,
                degradationScore=0.0,
                riskLevel="LOW",
                confidence=0.1,
                signals=[],
                explanations=details,
                recommendedActions=["Regenerate the active feature window or enable baseline fallback."],
                inputHash=input_hash,
            )

        required_hash = preference.required_feature_schema_hash
        generated_hash = str(request.feature_contract.get("featureSchemaHash") or "")
        if required_hash and generated_hash and required_hash != generated_hash:
            return InferenceResponse(
                deviceId=request.device_id,
                modelVersionId=None,
                modelName=MODEL_NAME,
                modelVersion=MODEL_VERSION,
                modelType=MODEL_TYPE,
                modelStatus=MODEL_STATUS,
                predictionStatus="FEATURE_SCHEMA_MISMATCH",
                anomalyScore=0.0,
                degradationScore=0.0,
                riskLevel="LOW",
                confidence=0.1,
                signals=[],
                explanations=["Online feature schema does not match the selected model feature schema."],
                recommendedActions=["Select a compatible model version or regenerate features with the model contract."],
                inputHash=input_hash,
            )
        return None

    def _input_hash(self, request: InferenceRequest) -> str:
        payload = request.model_dump(mode="json", by_alias=True)
        serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(serialized.encode("utf-8")).hexdigest()

    def _predict_with_trained_model(self, request: InferenceRequest) -> InferenceResponse | None:
        preference = request.model_preference
        model = self._resolve_model(preference)
        if model is None:
            return None

        metric_values = self._trained_model_features(request)
        if not metric_values:
            return None

        try:
            bundle = self.artifact_store.load_model_bundle(model["manifestUri"], model.get("artifactHash"))
        except (OSError, ValueError) as exc:
            return self._model_unavailable_response(request, model, str(exc))
        manifest_model_version_id = str(bundle["manifest"].get("modelVersionId") or "")
        if model.get("modelVersionId") and manifest_model_version_id != model.get("modelVersionId"):
            return self._model_unavailable_response(
                request,
                model,
                "Selected model manifest does not match the requested modelVersionId.",
            )
        artifact = bundle["files"]["model.json"]
        if model["name"].startswith("model-") and artifact.get("name"):
            model["name"] = artifact["name"]
        if model["version"] == model.get("modelVersionId") and artifact.get("version"):
            model["version"] = artifact["version"]
        if model["modelType"] == "trained_tabular_anomaly" and artifact.get("modelType"):
            model["modelType"] = artifact["modelType"]
        if model["modelType"] == "trained_failure_prediction" or artifact.get("modelType") == "trained_failure_prediction":
            model["modelType"] = "trained_failure_prediction"
        pipeline_payload = bundle["files"].get("model.joblib")
        if not isinstance(pipeline_payload, bytes):
            return self._model_unavailable_response(request, model, "Selected model bundle does not contain a serialized runtime model.")

        if model["modelType"] == "trained_failure_prediction":
            label_response = self._failure_label_gate_response(request, model, artifact)
            if label_response is not None:
                return label_response
            feature_response = self._failure_feature_response(request, model, artifact, metric_values)
            if feature_response is not None:
                return feature_response
            return self._predict_with_failure_model(request, model, artifact, pipeline_payload, metric_values)

        try:
            pipeline = TabularAnomalyModel.load_pipeline(pipeline_payload)
        except (OSError, ValueError, ImportError) as exc:
            return self._model_unavailable_response(request, model, str(exc))
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
                        severity=self._risk_level(score),
                        explanation=f"{metric}={value:g} was scored against trained model {model['version']}.",
                    )
                )

        if not scores:
            if preference and not preference.allow_baseline_heuristic:
                return InferenceResponse(
                    deviceId=request.device_id,
                    modelVersionId=model.get("modelVersionId"),
                    modelName=model["name"],
                    modelVersion=model["version"],
                    modelType=model["modelType"],
                    modelStatus=model["status"],
                    predictionStatus="MODEL_INCOMPATIBLE",
                    anomalyScore=0.0,
                    degradationScore=0.0,
                    riskLevel="LOW",
                    confidence=0.2,
                    signals=[],
                    explanations=["Selected trained model has no compatible metric statistics for this request."],
                    recommendedActions=["Enable baseline fallback or select a compatible trained model."],
                    inputHash=self._input_hash(request),
                )
            return None

        anomaly_score = min(1.0, max(scores))
        risk_level = self._risk_level(anomaly_score)
        if not signals:
            signals.append(
                PredictionSignal(
                    metric="telemetry",
                    score=round(anomaly_score, 4),
                    severity=risk_level,
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
            riskLevel=risk_level,
            confidence=round(min(0.95, 0.7 + 0.04 * len(scores)), 4),
            signals=signals,
            explanations=[signal.explanation for signal in signals],
            recommendedActions=self._recommended_actions(risk_level, signals),
            inputHash=self._input_hash(request),
        )

    def _predict_with_failure_model(
        self,
        request: InferenceRequest,
        model: dict[str, Any],
        artifact: dict[str, Any],
        pipeline_payload: bytes,
        metric_values: dict[str, float],
    ) -> InferenceResponse:
        try:
            pipeline = FailurePredictionModel.load_pipeline(pipeline_payload)
        except (OSError, ValueError, ImportError) as exc:
            return self._model_unavailable_response(request, model, str(exc))
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
            recommendedActions=self._recommended_actions(failure_risk_band, []),
            inputHash=self._input_hash(request),
        )

    def _failure_confidence(self, artifact: dict[str, Any], metric_values: dict[str, float]) -> float:
        metrics = artifact.get("metrics") or {}
        confidence = 0.55 + 0.03 * len(metric_values)
        confidence += min(0.2, float(metrics.get("labelCoverage") or 0.0) * 0.2)
        if (artifact.get("evaluation") or {}).get("status") == "EVALUATED":
            confidence += 0.08
        if (artifact.get("approvalGates") or {}).get("promotionEligible"):
            confidence += 0.05
        return round(min(0.95, max(0.35, confidence)), 4)

    def _failure_label_gate_response(
        self,
        request: InferenceRequest,
        model: dict[str, Any],
        artifact: dict[str, Any],
    ) -> InferenceResponse | None:
        approval = artifact.get("approvalGates") or {}
        failed_label_gates = [
            gate
            for gate in approval.get("criteria", [])
            if gate.get("name") in {"minimum_positive_labels", "minimum_label_coverage"} and not gate.get("passed", False)
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
            recommendedActions=["Retrain with sufficient outcome labels before serving this failure model."],
            inputHash=self._input_hash(request),
        )

    def _failure_feature_response(
        self,
        request: InferenceRequest,
        model: dict[str, Any],
        artifact: dict[str, Any],
        metric_values: dict[str, float],
    ) -> InferenceResponse | None:
        feature_columns = [str(column) for column in artifact.get("featureColumns", [])]
        missing_features = sorted(column for column in feature_columns if column not in metric_values)
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
            explanations=[f"Missing required failure model features: {', '.join(missing_features)}."],
            recommendedActions=["Regenerate online features using the selected model feature contract."],
            inputHash=self._input_hash(request),
        )

    def _resolve_model(self, preference: Any) -> dict[str, Any] | None:
        if preference is None:
            return None
        if preference.model_version_id and preference.manifest_uri:
            return {
                "name": preference.model_name or f"model-{preference.model_version_id}",
                "version": str(preference.model_version or preference.model_version_id),
                "modelType": preference.model_type or "trained_tabular_anomaly",
                "status": preference.model_status or "READY",
                "modelVersionId": preference.model_version_id,
                "artifactUri": preference.artifact_uri,
                "manifestUri": preference.manifest_uri,
                "artifactHash": preference.artifact_hash,
            }
        return model_registry_service.get_model(
            name=preference.model_name,
            model_version_id=preference.model_version_id,
        )

    def _trained_model_features(self, request: InferenceRequest) -> dict[str, float]:
        feature_values = self._numeric_metrics(request.feature_vector)
        return feature_values or self._numeric_metrics(request.metrics)

    def _model_unavailable_response(self, request: InferenceRequest, model: dict[str, Any], reason: str) -> InferenceResponse:
        return InferenceResponse(
            deviceId=request.device_id,
            modelVersionId=model.get("modelVersionId"),
            modelName=model["name"],
            modelVersion=model["version"],
            modelType=model["modelType"],
            modelStatus=model["status"],
            predictionStatus="MODEL_UNAVAILABLE",
            anomalyScore=0.0,
            degradationScore=0.0,
            riskLevel="LOW",
            confidence=0.1,
            signals=[],
            explanations=[reason],
            recommendedActions=["Verify model artifact storage and select another model version if the artifact is unavailable."],
            inputHash=self._input_hash(request),
        )
