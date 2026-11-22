from uuid import uuid4

from app.models.failure_evaluation import FailurePredictionEvaluator
from app.models.failure_prediction_model import FailurePredictionModel
from app.models.failure_target import FailureTargetBuilder
from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.approval_gates import ApprovalGateEvaluator
from app.services.model_artifact_publisher import ModelArtifactPublisher
from app.services.model_cards import ModelCardBuilder
from app.services.model_registry_service import model_registry_service
from app.services.training_coordinator import TrainingRunCoordinator
from app.services.training_fit import fit_with_deadline


class FailurePredictionTrainingRunner:
    def __init__(
        self,
        publisher: ModelArtifactPublisher,
        coordinator: TrainingRunCoordinator,
        gate_evaluator: ApprovalGateEvaluator | None = None,
        model_card_builder: ModelCardBuilder | None = None,
    ) -> None:
        self.publisher = publisher
        self.coordinator = coordinator
        self.gate_evaluator = gate_evaluator or ApprovalGateEvaluator()
        self.model_card_builder = model_card_builder or ModelCardBuilder()

    def run(self, run_id: str, request: TrainingRequest, started_at: float) -> TrainingRunResponse:
        try:
            self.coordinator.checkpoint(request, started_at)
            builder = FailureTargetBuilder()
            samples = builder.build(request)
            metrics = builder.summary(samples)
        except ValueError as exc:
            return self._failed_response(
                run_id,
                request,
                failure_reason=str(exc),
                logs_summary=["Rejected failure-prediction training request before fitting."],
            )

        gate_failure = self.gate_evaluator.failure_reason(request, metrics)
        if gate_failure is not None:
            return self._failed_response(
                run_id,
                request,
                metrics=metrics,
                failure_reason=gate_failure,
                logs_summary=["Rejected failure-prediction training request before fitting."],
            )

        algorithm = self._failure_algorithm(request)
        self.coordinator.checkpoint(request, started_at)
        evaluation = FailurePredictionEvaluator().evaluate(
            samples, algorithm, lambda: self.coordinator.remaining_seconds(request, started_at)
        )
        evaluation_metrics = evaluation["metrics"]
        evaluation_metadata = evaluation["metadata"]

        try:
            model = FailurePredictionModel(algorithm)
            fit_samples = (
                [sample for sample in samples if sample.split_assignment == "TRAIN"]
                if any(sample.split_assignment for sample in samples)
                else samples
            )
            model = fit_with_deadline(
                model, (fit_samples,), self.coordinator.remaining_seconds(request, started_at)
            )
        except ValueError as exc:
            return self._failed_response(
                run_id,
                request,
                metrics=metrics,
                failure_reason=str(exc),
                logs_summary=["Rejected failure-prediction training request before fitting."],
            )

        model_version_id = str(uuid4())
        model_version = f"{algorithm}-{run_id[:8]}"
        model_name = f"failure-{request.dataset_version_id}"
        feature_schema_hash = self.publisher.feature_schema_hash(request)
        metrics = {
            **metrics,
            **evaluation_metrics,
            "failureHorizonDays": float(request.failure_horizon_days or samples[0].horizon_days),
        }
        approval_metadata = self.gate_evaluator.approval_metadata(
            request=request,
            metrics=metrics,
            feature_schema_hash=feature_schema_hash,
            evaluation_metadata=evaluation_metadata,
        )
        metrics = {
            **metrics,
            "approvalGateProfileId": approval_metadata["profileId"],
            "approvalGateProfileVersion": approval_metadata["profileVersion"],
            "approvalGateThresholdSource": approval_metadata["thresholdSource"],
            "approvalGates": approval_metadata,
        }
        model_payload = {
            "name": model_name,
            "version": model_version,
            "modelVersionId": model_version_id,
            **model.model_payload(),
            "algorithm": algorithm,
            "datasetVersionId": request.dataset_version_id,
            "featureSchemaHash": feature_schema_hash,
            "metrics": metrics,
            "evaluation": evaluation_metadata,
            "approvalGates": approval_metadata,
        }
        model_card_payload = self.model_card_builder.failure_card(
            model_payload=model_payload,
            request=request,
            metrics=metrics,
            evaluation_metadata=evaluation_metadata,
            approval_metadata=approval_metadata,
        )
        self.coordinator.checkpoint(request, started_at)
        artifact_bundle_id = self.coordinator.artifact_bundle_id(run_id, request)
        bundle = self.publisher.save_verified_bundle(
            artifact_bundle_id,
            model_payload,
            self.publisher.manifest(
                model_version_id=model_version_id,
                run_id=run_id,
                request=request,
                feature_schema_hash=feature_schema_hash,
                metrics=metrics,
                algorithm=algorithm,
                evaluation_metadata=evaluation_metadata,
                approval_metadata=approval_metadata,
            ),
            supporting_files={
                "model.joblib": model.serialized_pipeline(),
                "model-card.json": model_card_payload,
                "preprocessor.json": model.preprocessor_payload(
                    request.dataset_version_id, feature_schema_hash
                ),
            },
        )
        self.coordinator.checkpoint(request, started_at)
        model_registry_service.register_model(
            {
                "name": model_name,
                "version": model_version,
                "modelVersionId": model_version_id,
                "status": "READY",
                "modelType": model.model_type,
                "artifactUri": bundle["artifactUri"],
                "manifestUri": bundle["manifestUri"],
                "artifactHash": bundle["artifactHash"],
                "artifactSizeBytes": bundle["artifactSizeBytes"],
                "metrics": metrics,
            }
        )
        return TrainingRunResponse(
            runId=run_id,
            status="SUCCEEDED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=artifact_bundle_id,
            modelVersionId=model_version_id,
            modelVersion=model_version,
            artifactUri=bundle["artifactUri"],
            manifestUri=bundle["manifestUri"],
            artifactHash=bundle["artifactHash"],
            artifactSizeBytes=bundle["artifactSizeBytes"],
            metrics=metrics,
            logsSummary=[
                "Constructed horizon targets from generated outcome labels.",
                f"Registered failure-prediction model {model_name}.",
            ],
        )

    def _failed_response(
        self,
        run_id: str,
        request: TrainingRequest,
        *,
        failure_reason: str,
        logs_summary: list[str],
        metrics: dict[str, float] | None = None,
    ) -> TrainingRunResponse:
        return TrainingRunResponse(
            runId=run_id,
            status="FAILED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=self.coordinator.artifact_bundle_id(run_id, request),
            metrics=metrics or {},
            failureReason=failure_reason,
            logsSummary=logs_summary,
        )

    def _failure_algorithm(self, request: TrainingRequest) -> str:
        if request.algorithm == "baseline-statistical-anomaly-v1":
            return FailurePredictionModel.LOGISTIC_REGRESSION_ALGORITHM
        return request.algorithm or FailurePredictionModel.LOGISTIC_REGRESSION_ALGORITHM
