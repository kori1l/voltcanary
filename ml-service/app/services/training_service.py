import time
from pathlib import Path

from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.anomaly_training import AnomalyTrainingRunner
from app.services.artifact_store import ArtifactStore
from app.services.failure_training import FailurePredictionTrainingRunner
from app.services.feature_service import FeatureService
from app.services.model_artifact_publisher import ModelArtifactPublisher
from app.services.postgres_training_history import PostgresTrainingHistory
from app.services.training_coordinator import (
    TrainingCanceledError,
    TrainingRunCoordinator,
    TrainingTimeoutError,
)
from app.services.training_history import TrainingHistory


class TrainingService:
    def __init__(
        self,
        artifact_store: ArtifactStore | None = None,
        coordinator: TrainingRunCoordinator | None = None,
    ) -> None:
        self.artifact_store = artifact_store or ArtifactStore()
        self.feature_service = FeatureService(self.artifact_store)
        if coordinator is None:
            current = self.artifact_store.settings_provider()
            if current.is_production_like_runtime:
                history = PostgresTrainingHistory(
                    {
                        "host": current.ml_training_postgres_host,
                        "port": current.ml_training_postgres_port,
                        "dbname": current.ml_training_postgres_database,
                        "user": current.ml_training_postgres_user,
                        "password": current.ml_training_postgres_password,
                    },
                    current.ml_training_history_capacity,
                    current.ml_training_history_retention_seconds,
                )
            else:
                history_path = (
                    Path(current.ml_training_history_path)
                    if current.ml_training_history_path
                    else self.artifact_store.root / ".training-history.sqlite3"
                )
                history = TrainingHistory(
                    history_path,
                    current.ml_training_history_capacity,
                    current.ml_training_history_retention_seconds,
                )
            coordinator = TrainingRunCoordinator(history, current.ml_training_fit_timeout_seconds)
        self.coordinator = coordinator
        self.publisher = ModelArtifactPublisher(self.artifact_store)
        self.anomaly_runner = AnomalyTrainingRunner(
            self.feature_service, self.publisher, self.coordinator
        )
        self.failure_runner = FailurePredictionTrainingRunner(self.publisher, self.coordinator)

    def start_training(self, request: TrainingRequest) -> TrainingRunResponse:
        fingerprint = self.coordinator.fingerprint(request)
        return self.coordinator.singleflight(
            request, fingerprint, lambda: self._start_training(request)
        )

    def _start_training(self, request: TrainingRequest) -> TrainingRunResponse:
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

        except ValueError:
            return self.coordinator.record_result(
                request,
                TrainingRunResponse(
                    runId=run_id,
                    status="FAILED",
                    datasetVersionId=request.dataset_version_id,
                    trainingRunId=request.training_run_id,
                    idempotencyKey=request.idempotency_key,
                    failureReason="Training samples or split assignments are invalid.",
                ),
                fingerprint,
            )

    def get_run(self, run_id: str) -> TrainingRunResponse:
        return self.coordinator.get_run(run_id)
