from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.training_service import TrainingService
from fastapi import APIRouter

router = APIRouter(tags=["training"])
training_service = TrainingService()


@router.post("/train")
def train_model(request: TrainingRequest) -> TrainingRunResponse:
    return training_service.start_training(request)


@router.get("/runs/{run_id}")
def get_training_run(run_id: str) -> TrainingRunResponse:
    return training_service.get_run(run_id)
