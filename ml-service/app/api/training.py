from fastapi import APIRouter, HTTPException

from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.artifact_providers import ArtifactConflictError
from app.services.training_coordinator import IdempotencyConflictError
from app.services.training_execution_lock import TrainingBusyError
from app.services.training_service import TrainingService

router = APIRouter(tags=["training"])
training_service = TrainingService()


@router.post("/train")
def train_model(request: TrainingRequest) -> TrainingRunResponse:
    try:
        return training_service.start_training(request)
    except (IdempotencyConflictError, ArtifactConflictError) as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except TrainingBusyError as exc:
        raise HTTPException(status_code=503, detail=str(exc), headers={"Retry-After": "5"}) from exc


@router.get("/runs/{run_id}")
def get_training_run(run_id: str) -> TrainingRunResponse:
    return training_service.get_run(run_id)
