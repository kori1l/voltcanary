from uuid import uuid4

from app.models.tabular_anomaly_model import TabularAnomalyModel
from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.anomaly_evaluation import AnomalyEvaluation
from app.services.feature_service import FeatureService
from app.services.model_artifact_publisher import ModelArtifactPublisher
from app.services.model_registry_service import model_registry_service
from app.services.training_coordinator import TrainingRunCoordinator
from app.services.training_fit import fit_with_deadline


class AnomalyTrainingRunner:
    def __init__(
        self,
        feature_service: FeatureService,
        publisher: ModelArtifactPublisher,
        coordinator: TrainingRunCoordinator,
    ) -> None:
        self.feature_service = feature_service
        self.publisher = publisher
        self.coordinator = coordinator

    def run(self, run_id: str, request: TrainingRequest, started_at: float) -> TrainingRunResponse:
        self.coordinator.checkpoint(request, started_at)
        evaluation = AnomalyEvaluation()
        training_rows, validation_rows = evaluation.split(request.rows)
        snapshot = self.feature_service.generate_snapshot(request.dataset_version_id, training_rows)
        self.coordinator.checkpoint(request, started_at)
        feature_artifact = self.feature_service.load_snapshot_artifact(snapshot.artifact_uri)
        model = TabularAnomalyModel()
        model = fit_with_deadline(
            model,
            (training_rows, feature_artifact),
            self.coordinator.remaining_seconds(request, started_at),
        )
        self.coordinator.checkpoint(request, started_at)
        metrics = evaluation.evaluate(model, training_rows, validation_rows)
        metrics["inputRows"] = float(len(request.rows))
        metrics["testRows"] = float(
            sum(row.metadata.get("splitAssignment") == "TEST" for row in request.rows)
        )
        model_version_id = str(uuid4())
        algorithm = self._algorithm(request, model.algorithm)
        model_version = f"{algorithm}-{run_id[:8]}"
        model_name = f"trained-{request.dataset_version_id}"
        model_payload = {
            "name": model_name,
            "version": model_version,
            "modelVersionId": model_version_id,
            **model.model_payload(),
            "algorithm": algorithm,
            "datasetVersionId": request.dataset_version_id,
            "featureSchemaHash": snapshot.feature_schema_hash,
            "metrics": metrics,
        }
        preprocessor_payload = model.preprocessor_payload(
            request.dataset_version_id, feature_artifact
        )
        artifact_bundle_id = self.coordinator.artifact_bundle_id(run_id, request)
        self.coordinator.checkpoint(request, started_at)
        bundle = self.publisher.save_verified_bundle(
            artifact_bundle_id,
            model_payload,
            self.publisher.manifest(
                model_version_id=model_version_id,
                run_id=run_id,
                request=request,
                snapshot=snapshot,
                metrics=metrics,
            ),
            supporting_files={
                "model.joblib": model.serialized_pipeline(),
                "preprocessor.json": preprocessor_payload,
            },
        )
        self.coordinator.checkpoint(request, started_at)
        model_registry_service.register_model(
            {
                "name": model_name,
                "version": model_version,
                "modelVersionId": model_version_id,
                "status": "READY",
                "modelType": model_payload["modelType"],
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
            featureSnapshot=snapshot,
            logsSummary=[
                f"Loaded feature artifact {snapshot.artifact_uri}.",
                f"Registered model {model_name}.",
            ],
        )

    def _algorithm(self, request: TrainingRequest, default_algorithm: str) -> str:
        if (
            request.model_family.upper() == "FAILURE_PREDICTION"
            and request.algorithm == "baseline-statistical-anomaly-v1"
        ):
            return default_algorithm
        return request.algorithm or default_algorithm
