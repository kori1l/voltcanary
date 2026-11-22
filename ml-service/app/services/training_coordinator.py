import hashlib
import json
import time
from concurrent.futures import Future
from contextvars import ContextVar
from threading import RLock
from typing import Callable
from uuid import uuid4

from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.postgres_training_history import PostgresTrainingHistory
from app.services.training_execution_lock import TrainingBusyError
from app.services.training_history import HistoryConflictError, TrainingHistory


class IdempotencyConflictError(Exception):
    pass


class TrainingCanceledError(Exception):
    pass


class TrainingTimeoutError(Exception):
    pass


class TrainingRunCoordinator:
    def __init__(
        self,
        history: TrainingHistory | PostgresTrainingHistory | None = None,
        maximum_seconds: float = 600,
    ) -> None:
        self._lock = RLock()
        self._pending: dict[str, tuple[str, Future[TrainingRunResponse]]] = {}
        self.history = history or TrainingHistory()
        self.maximum_seconds = maximum_seconds
        self._ownership_check = ContextVar("training_ownership_check", default=None)

    def singleflight(
        self,
        request: TrainingRequest,
        fingerprint: str,
        operation: Callable[[], TrainingRunResponse],
    ) -> TrainingRunResponse:
        key = request.idempotency_key or (
            "run:" + request.training_run_id if request.training_run_id else None
        )
        if key is None:
            return operation()
        with self._lock:
            existing = self.idempotent_result(request, fingerprint)
            if existing is not None:
                return existing
            active = self._pending.get(key)
            owner = active is None
            if owner:
                future = Future()
                self._pending[key] = (fingerprint, future)
            else:
                active_fingerprint, future = active
                if active_fingerprint != fingerprint:
                    raise IdempotencyConflictError(
                        "training idempotency key was reused with different request metadata"
                    )
        if not owner:
            try:
                return future.result(timeout=self.maximum_seconds + 30)
            except TimeoutError as failure:
                raise TrainingBusyError(
                    "Training execution is already owned by another worker"
                ) from failure
        try:
            with self.history.execution_lock(key, self.maximum_seconds + 30) as check:
                token = self._ownership_check.set(check)
                try:
                    result = self.idempotent_result(request, fingerprint)
                    if result is None:
                        result = self.record_result(request, operation(), fingerprint)
                finally:
                    self._ownership_check.reset(token)
            future.set_result(result)
            return result
        except Exception as failure:
            future.set_exception(failure)
            raise
        finally:
            with self._lock:
                self._pending.pop(key, None)

    def run_id(self, request: TrainingRequest) -> str:
        return request.training_run_id or str(uuid4())

    def fingerprint(self, request: TrainingRequest) -> str:
        payload = request.model_dump(mode="json", by_alias=True)
        payload.pop("cancellationRequested", None)
        payload.pop("trainingTimeoutSeconds", None)
        serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        return "sha256:" + hashlib.sha256(serialized.encode("utf-8")).hexdigest()

    def idempotent_result(
        self, request: TrainingRequest, fingerprint: str
    ) -> TrainingRunResponse | None:
        key = request.idempotency_key or (
            "run:" + request.training_run_id if request.training_run_id else None
        )
        if key is None:
            return None
        try:
            return self.history.by_key(key, fingerprint)
        except HistoryConflictError as failure:
            raise IdempotencyConflictError(str(failure)) from failure

    def record_result(
        self, request: TrainingRequest, run: TrainingRunResponse, fingerprint: str
    ) -> TrainingRunResponse:
        self._check_ownership()
        key = request.idempotency_key or (
            "run:" + request.training_run_id if request.training_run_id else None
        )
        try:
            return self.history.put(run, key, fingerprint)
        except HistoryConflictError as failure:
            raise IdempotencyConflictError(str(failure)) from failure

    def get_run(self, run_id: str) -> TrainingRunResponse:
        return self.history.by_run(run_id) or TrainingRunResponse(
            runId=run_id,
            status="NOT_FOUND",
            datasetVersionId="unknown",
            failureReason="training run was not found",
        )

    def artifact_bundle_id(self, run_id: str, request: TrainingRequest) -> str:
        return request.artifact_bundle_id or f"model-{run_id}"

    def remaining_seconds(self, request: TrainingRequest, started_at: float) -> float:
        self._check_ownership()
        if request.cancellation_requested:
            raise TrainingCanceledError()
        budget = (
            min(self.maximum_seconds, request.training_timeout_seconds)
            if request.training_timeout_seconds is not None
            else self.maximum_seconds
        )
        remaining = budget - (time.monotonic() - started_at)
        if remaining <= 0:
            raise TrainingTimeoutError()
        return remaining

    def checkpoint(self, request: TrainingRequest, started_at: float) -> None:
        self.remaining_seconds(request, started_at)

    def _check_ownership(self) -> None:
        check = self._ownership_check.get()
        if check is not None:
            try:
                check()
            except Exception as failure:
                raise TrainingBusyError("Training reservation is no longer available") from failure

    def empty_dataset_response(self, run_id: str, request: TrainingRequest) -> TrainingRunResponse:
        return TrainingRunResponse(
            runId=run_id,
            status="FAILED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=self.artifact_bundle_id(run_id, request),
            failureReason="training requires at least one metric row",
            logsSummary=["Rejected empty training dataset."],
        )

    def canceled_response(self, run_id: str, request: TrainingRequest) -> TrainingRunResponse:
        return TrainingRunResponse(
            runId=run_id,
            status="CANCELED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=self.artifact_bundle_id(run_id, request),
            failureReason="training run cancellation was requested",
            logsSummary=["Training canceled before artifact promotion."],
        )

    def timeout_response(self, run_id: str, request: TrainingRequest) -> TrainingRunResponse:
        return TrainingRunResponse(
            runId=run_id,
            status="FAILED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=self.artifact_bundle_id(run_id, request),
            failureReason="training timeout exceeded before artifact promotion",
            logsSummary=[
                "Training stopped before artifact promotion because the timeout budget was exceeded."
            ],
        )
