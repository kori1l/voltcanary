import hashlib
import json
from statistics import mean, pstdev
from uuid import uuid4

from app.schemas.training import FeatureSnapshotResponse, MetricRow
from app.services.artifact_store import ArtifactStore


class FeatureService:
    def __init__(self, artifact_store: ArtifactStore | None = None) -> None:
        self.artifact_store = artifact_store or ArtifactStore()

    def generate_snapshot(self, dataset_version_id: str, rows: list[MetricRow]) -> FeatureSnapshotResponse:
        if not rows:
            raise ValueError("feature generation requires at least one metric row")

        features = self._features(rows)
        schema_hash = self.schema_hash(features)
        snapshot_id = str(uuid4())
        artifact_uri = self.artifact_store.save_json(
            f"features-{snapshot_id}",
            {
                "datasetVersionId": dataset_version_id,
                "featureSchemaHash": schema_hash,
                "features": features,
            },
        )
        return FeatureSnapshotResponse(
            featureSnapshotId=snapshot_id,
            datasetVersionId=dataset_version_id,
            rowCount=len(rows),
            featureSchemaHash=schema_hash,
            artifactUri=artifact_uri,
        )

    def _features(self, rows: list[MetricRow]) -> dict[str, float | int]:
        values_by_metric: dict[str, list[float]] = {}
        labeled_anomalies = 0
        for row in sorted(rows, key=lambda item: (item.metric, item.timestamp, item.device_id)):
            values_by_metric.setdefault(row.metric, []).append(float(row.value))
            if row.label and row.label.upper() in {"ANOMALY", "FAILURE", "DEGRADATION"}:
                labeled_anomalies += 1

        features: dict[str, float | int] = {
            "row_count": len(rows),
            "metric_count": len(values_by_metric),
            "labeled_anomaly_count": labeled_anomalies,
        }
        for metric, values in sorted(values_by_metric.items()):
            prefix = self._normalize_metric(metric)
            features[f"{prefix}_mean"] = round(mean(values), 6)
            features[f"{prefix}_min"] = round(min(values), 6)
            features[f"{prefix}_max"] = round(max(values), 6)
            features[f"{prefix}_stddev"] = round(pstdev(values), 6) if len(values) > 1 else 0.0
        return features

    def schema_hash(self, features: dict[str, float | int]) -> str:
        schema = sorted(features.keys())
        serialized = json.dumps(schema, separators=(",", ":"), sort_keys=True)
        return "sha256:" + hashlib.sha256(serialized.encode("utf-8")).hexdigest()

    def _normalize_metric(self, metric: str) -> str:
        return "".join(char.lower() if char.isalnum() else "_" for char in metric).strip("_")
