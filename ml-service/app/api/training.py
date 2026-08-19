from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.training_service import IdempotencyConflictError, TrainingService
from fastapi import APIRouter, HTTPException

router = APIRouter(tags=["training"])
training_service = TrainingService()


@router.post("/train")
def train_model(request: TrainingRequest) -> TrainingRunResponse:
    try:
        return training_service.start_training(request)
    except IdempotencyConflictError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.get("/runs/{run_id}")
def get_training_run(run_id: str) -> TrainingRunResponse:
    return training_service.get_run(run_id)
