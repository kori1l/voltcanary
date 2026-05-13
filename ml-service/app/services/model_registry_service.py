from app.schemas.model import ModelSummary


class ModelRegistryService:
    def list_models(self) -> list[ModelSummary]:
        return [
            ModelSummary(
                name="mock-anomaly-detector",
                version="mock-model-v1",
                status="active",
                modelType="anomaly",
            )
        ]
