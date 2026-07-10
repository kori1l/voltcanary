from app.schemas.model import ModelSummary
from app.services.inference_service import MODEL_NAME, MODEL_STATUS, MODEL_TYPE, MODEL_VERSION


class ModelRegistryService:
    def list_models(self) -> list[ModelSummary]:
        return [
            ModelSummary(
                name=MODEL_NAME,
                version=MODEL_VERSION,
                status=MODEL_STATUS,
                modelType=MODEL_TYPE,
            )
        ]
