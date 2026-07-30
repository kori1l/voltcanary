from typing import Any

from app.schemas.model import ModelSummary


MODEL_NAME = "voltcanary-baseline-heuristic"
MODEL_VERSION = "baseline-heuristic-v1"
MODEL_TYPE = "baseline_heuristic"
MODEL_STATUS = "READY_HEURISTIC"


class ModelRegistryService:
    def __init__(self) -> None:
        self._models: dict[str, dict[str, Any]] = {}

    def register_model(self, model: dict[str, Any]) -> None:
        self._models[model["name"]] = model

    def get_model(self, name: str | None) -> dict[str, Any] | None:
        if not name:
            return None
        return self._models.get(name)

    def list_models(self) -> list[ModelSummary]:
        models = [
            ModelSummary(
                name=MODEL_NAME,
                version=MODEL_VERSION,
                status=MODEL_STATUS,
                modelType=MODEL_TYPE,
            )
        ]
        for model in sorted(self._models.values(), key=lambda item: item["name"]):
            models.append(
                ModelSummary(
                    name=model["name"],
                    version=model["version"],
                    status=model["status"],
                    modelType=model["modelType"],
                )
            )
        return models


model_registry_service = ModelRegistryService()
