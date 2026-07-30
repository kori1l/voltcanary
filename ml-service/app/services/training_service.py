from uuid import uuid4

from app.models.anomaly_model import AnomalyModel
from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.artifact_store import ArtifactStore
from app.services.feature_service import FeatureService
from app.services.model_registry_service import model_registry_service


class TrainingService:
    def __init__(self, artifact_store: ArtifactStore | None = None) -> None:
        self.artifact_store = artifact_store or ArtifactStore()
        self.feature_service = FeatureService(self.artifact_store)
        self.runs: dict[str, TrainingRunResponse] = {}

    def start_training(self, request: TrainingRequest) -> TrainingRunResponse:
        run_id = str(uuid4())
        if not request.rows:
            run = TrainingRunResponse(
                runId=run_id,
                status="FAILED",
                datasetVersionId=request.dataset_version_id,
                failureReason="training requires at least one metric row",
                logsSummary=["Rejected empty training dataset."],
            )
            self.runs[run_id] = run
            return run

        snapshot = self.feature_service.generate_snapshot(request.dataset_version_id, request.rows)
        model = AnomalyModel()
        model.fit(request.rows)
        metrics = self._evaluate(model, request.rows)
        model_version = f"{request.algorithm}-{run_id[:8]}"
        model_name = f"trained-{request.dataset_version_id}"
        artifact_uri = self.artifact_store.save_json(
            f"model-{run_id}",
            {
                "name": model_name,
                "version": model_version,
                "modelType": "trained_anomaly",
                "algorithm": request.algorithm,
                "datasetVersionId": request.dataset_version_id,
                "featureSchemaHash": snapshot.feature_schema_hash,
                "metricStats": model.baseline.metric_stats,
                "metrics": metrics,
            },
        )
        model_registry_service.register_model(
            {
                "name": model_name,
                "version": model_version,
                "status": "READY",
                "modelType": "trained_anomaly",
                "artifactUri": artifact_uri,
                "metrics": metrics,
            }
        )
        run = TrainingRunResponse(
            runId=run_id,
            status="SUCCEEDED",
            datasetVersionId=request.dataset_version_id,
            modelVersion=model_version,
            artifactUri=artifact_uri,
            metrics=metrics,
            featureSnapshot=snapshot,
            logsSummary=[
                f"Generated {snapshot.row_count} feature rows from {request.source_type}.",
                f"Registered model {model_name}.",
            ],
        )
        self.runs[run_id] = run
        return run

    def get_run(self, run_id: str) -> TrainingRunResponse:
        return self.runs.get(
            run_id,
            TrainingRunResponse(
                runId=run_id,
                status="NOT_FOUND",
                datasetVersionId="unknown",
                failureReason="training run was not found",
            ),
        )

    def _evaluate(self, model: AnomalyModel, rows: list) -> dict[str, float]:
        values = {row.metric: float(row.value) for row in rows[-min(3, len(rows)) :]}
        score = model.score(values)["anomalyScore"]
        labels = [row.label for row in rows if row.label]
        label_coverage = len(labels) / len(rows)
        return {
            "validationAnomalyScore": round(score, 6),
            "labelCoverage": round(label_coverage, 6),
            "trainingRows": float(len(rows)),
        }
