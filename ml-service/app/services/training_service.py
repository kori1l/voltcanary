import time

from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.artifact_store import ArtifactStore
from app.services.feature_service import FeatureService
from app.services.training_runtime import (
    AnomalyTrainingRunner,
    FailurePredictionTrainingRunner,
    IdempotencyConflictError,
    ModelArtifactPublisher,
    TrainingCanceledError,
    TrainingRunCoordinator,
    TrainingTimeoutError,
)


class TrainingService:
    def __init__(
        self,
        artifact_store: ArtifactStore | None = None,
        coordinator: TrainingRunCoordinator | None = None,
    ) -> None:
        self.artifact_store = artifact_store or ArtifactStore()
        self.feature_service = FeatureService(self.artifact_store)
        self.coordinator = coordinator or TrainingRunCoordinator()
        self.publisher = ModelArtifactPublisher(self.artifact_store)
        self.anomaly_runner = AnomalyTrainingRunner(self.feature_service, self.publisher, self.coordinator)
        self.failure_runner = FailurePredictionTrainingRunner(self.publisher, self.coordinator)
        self.runs = self.coordinator.runs
        self.idempotency_records = self.coordinator.idempotency_records

    def start_training(self, request: TrainingRequest) -> TrainingRunResponse:
        run_id = self.coordinator.run_id(request)
        fingerprint = self.coordinator.fingerprint(request)
        existing = self.coordinator.idempotent_result(request, fingerprint)
        if existing is not None:
            return existing

        started_at = time.monotonic()
        try:
            self.coordinator.checkpoint(request, started_at)
            if not request.rows:
                return self.coordinator.record_result(
                    request,
                    self.coordinator.empty_dataset_response(run_id, request),
                    fingerprint,
                )

            if request.model_family.upper() == "FAILURE_PREDICTION":
                run = self.failure_runner.run(run_id, request, started_at)
            else:
                run = self.anomaly_runner.run(run_id, request, started_at)
            return self.coordinator.record_result(request, run, fingerprint)
        except TrainingCanceledError:
            return self.coordinator.record_result(
                request,
                self.coordinator.canceled_response(run_id, request),
                fingerprint,
            )
        except TrainingTimeoutError:
            return self.coordinator.record_result(
                request,
                self.coordinator.timeout_response(run_id, request),
                fingerprint,
            )

    def get_run(self, run_id: str) -> TrainingRunResponse:
        return self.coordinator.get_run(run_id)
