from app.schemas.model import ModelSummary
from app.services.model_registry_service import model_registry_service
from fastapi import APIRouter

router = APIRouter(tags=["models"])


@router.get("/models")
def list_models() -> list[ModelSummary]:
    return model_registry_service.list_models()
