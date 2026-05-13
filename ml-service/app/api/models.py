from app.schemas.model import ModelSummary
from app.services.model_registry_service import ModelRegistryService
from fastapi import APIRouter

router = APIRouter(tags=["models"])
model_registry_service = ModelRegistryService()


@router.get("/models")
def list_models() -> list[ModelSummary]:
    return model_registry_service.list_models()
