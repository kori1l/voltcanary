from typing import Any

from app.schemas.model import ModelSummary


MODEL_NAME = "voltcanary-baseline-heuristic"
MODEL_VERSION = "baseline-heuristic-v1"
MODEL_TYPE = "baseline_heuristic"
MODEL_STATUS = "READY_HEURISTIC"


class ModelRegistryService:
    def __init__(self) -> None:
        self._models: dict[str, dict[str, Any]] = {}
        self._models_by_id: dict[str, dict[str, Any]] = {}

    def register_model(self, model: dict[str, Any]) -> None:
        self._models[model["name"]] = model
        if model.get("modelVersionId"):
            self._models_by_id[model["modelVersionId"]] = model

    def get_model(self, name: str | None = None, model_version_id: str | None = None) -> dict[str, Any] | None:
        if model_version_id:
            return self._models_by_id.get(model_version_id)
        if name:
            return self._models.get(name)
        return None

    def clear(self) -> None:
        self._models.clear()
        self._models_by_id.clear()

    def list_models(self) -> list[ModelSummary]:
        models = [
            ModelSummary(
                name=MODEL_NAME,
                version=MODEL_VERSION,
                status=MODEL_STATUS,
                modelType=MODEL_TYPE,
                modelVersionId=None,
                artifactUri=None,
                manifestUri=None,
                artifactHash=None,
            )
        ]
        for model in sorted(self._models.values(), key=lambda item: item["name"]):
            models.append(
                ModelSummary(
                    name=model["name"],
                    version=model["version"],
                    status=model["status"],
                    modelType=model["modelType"],
                    modelVersionId=model.get("modelVersionId"),
                    artifactUri=model.get("artifactUri"),
                    manifestUri=model.get("manifestUri"),
                    artifactHash=model.get("artifactHash"),
                )
            )
        return models


model_registry_service = ModelRegistryService()
