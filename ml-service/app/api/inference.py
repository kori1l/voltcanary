from fastapi import APIRouter

from app.schemas.inference import InferenceRequest, InferenceResponse
from app.services.inference_service import InferenceService

router = APIRouter(tags=["inference"])
inference_service = InferenceService()


@router.post("/inference")
def run_inference(request: InferenceRequest) -> InferenceResponse:
    return inference_service.predict(request)
