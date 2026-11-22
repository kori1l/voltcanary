import sys
from importlib.metadata import version
from typing import Any

from app.models.failure_prediction_model import FailurePredictionModel
from app.schemas.training import FeatureSnapshotResponse, TrainingRequest
from app.services.artifact_bundle import BundleFilePayload
from app.services.artifact_contracts import ModelManifest, SavedModelBundle
from app.services.artifact_store import ArtifactStore


class ModelArtifactPublisher:
    def __init__(self, artifact_store: ArtifactStore) -> None:
        self.artifact_store = artifact_store

    def save_verified_bundle(
        self,
        artifact_bundle_id: str,
        model_payload: dict[str, Any],
        manifest_payload: ModelManifest,
        supporting_files: dict[str, BundleFilePayload],
    ) -> SavedModelBundle:
        bundle = None
        try:
            bundle = self.artifact_store.save_model_bundle(
                artifact_bundle_id,
                model_payload,
                manifest_payload,
                supporting_files=supporting_files,
            )
            self.artifact_store.verify_model_bundle(bundle["manifestUri"], bundle["artifactHash"])
            return bundle
        except Exception as failure:
            if bundle is None:
                # Providers own rollback of their writes. Never delete another writer's reservation.
                raise
            try:
                self.artifact_store.delete_artifact_bundle(artifact_bundle_id)
            except Exception as cleanup_failure:
                raise failure from cleanup_failure
            raise

    def manifest(
        self,
        model_version_id: str,
        run_id: str,
        request: TrainingRequest,
        snapshot: FeatureSnapshotResponse | None = None,
        metrics: dict[str, Any] | None = None,
        feature_schema_hash: str | None = None,
        algorithm: str | None = None,
        evaluation_metadata: dict[str, Any] | None = None,
        approval_metadata: dict[str, Any] | None = None,
    ) -> ModelManifest:
        resolved_feature_schema_hash = feature_schema_hash or (
            snapshot.feature_schema_hash if snapshot else None
        )
        if not resolved_feature_schema_hash:
            raise ValueError(
                "Model manifest requires an explicit feature schema hash or feature snapshot"
            )
        return {
            "artifactContractVersion": (
                FailurePredictionModel.artifact_contract_version
                if request.model_family.upper() == "FAILURE_PREDICTION"
                else "tabular-anomaly-artifact-v1"
            ),
            "modelVersionId": model_version_id,
            "modelFamily": request.model_family,
            "algorithm": algorithm or request.algorithm,
            "featureSchemaHash": resolved_feature_schema_hash,
            "featureConfigVersion": "feature-vector-contract-v1",
            "trainingRunId": run_id,
            "datasetVersionId": request.dataset_version_id,
            "runtime": {
                "python": sys.version.split()[0],
                "service": "voltcanary-ml-service",
                "dependencies": {
                    "numpy": version("numpy"),
                    "scikit-learn": version("scikit-learn"),
                    "joblib": version("joblib"),
                },
            },
            "supportedMetrics": sorted({row.metric for row in request.rows}),
            "supportedDeviceTypes": sorted(
                {
                    str(row.metadata.get("deviceType"))
                    for row in request.rows
                    if row.metadata.get("deviceType")
                }
            ),
            "metrics": metrics or {},
            "evaluation": evaluation_metadata or {},
            "approvalGates": approval_metadata or {},
            "inferenceInputContract": {
                "modelVersionId": model_version_id,
                "requiredFields": ["deviceId", "metrics"],
                "featureSchemaHash": resolved_feature_schema_hash,
            },
        }

    def feature_schema_hash(self, request: TrainingRequest) -> str:
        for row in request.rows:
            value = row.metadata.get("featureSchemaHash")
            if value:
                return str(value)
        metrics = ",".join(sorted({row.metric for row in request.rows}))
        return f"inline:{request.model_family}:{request.failure_horizon_days}:{metrics}"
