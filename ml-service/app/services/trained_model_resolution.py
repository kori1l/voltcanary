from typing import Any

from app.schemas.inference import InferenceRequest
from app.services.inference_values import MetricValueExtractor
from app.services.model_registry_service import model_registry_service


class TrainedModelResolver:
    def __init__(self, extractor: MetricValueExtractor | None = None) -> None:
        self.extractor = extractor or MetricValueExtractor()

    def resolve(self, preference: Any) -> dict[str, Any] | None:
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

    def trained_model_features(self, request: InferenceRequest) -> dict[str, float]:
        feature_values = self.extractor.numeric_metrics(request.feature_vector)
        return feature_values or self.extractor.numeric_metrics(request.metrics)

    def apply_runtime_metadata(
        self, model: dict[str, Any], artifact: dict[str, Any], model_type: str
    ) -> None:
        if model["name"].startswith("model-") and artifact.get("name"):
            model["name"] = artifact["name"]
        if model["version"] == model.get("modelVersionId") and artifact.get("version"):
            model["version"] = artifact["version"]
        if model["modelType"] == "trained_tabular_anomaly" and artifact.get("modelType"):
            model["modelType"] = artifact["modelType"]
        model["modelType"] = model_type
