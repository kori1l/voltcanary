from datetime import datetime, timedelta, timezone
import tempfile
import unittest

from fastapi.testclient import TestClient

from app.main import app
from app.schemas.training import MetricRow, TrainingRequest
from app.services.artifact_store import ArtifactStore
from app.services.feature_service import FeatureService
from app.services.training_service import TrainingService


def sample_rows() -> list[dict]:
    start = datetime(2026, 7, 30, tzinfo=timezone.utc)
    return [
        {
            "timestamp": (start + timedelta(minutes=index)).isoformat(),
            "deviceId": "device-1",
            "metric": metric,
            "value": value,
            "unit": unit,
            "label": label,
        }
        for index, (metric, value, unit, label) in enumerate(
            [
                ("voltage", 229.0, "V", "NORMAL"),
                ("voltage", 231.0, "V", "NORMAL"),
                ("voltage", 230.0, "V", "NORMAL"),
                ("activePowerW", 820.0, "W", "NORMAL"),
                ("activePowerW", 860.0, "W", "NORMAL"),
                ("activePowerW", 840.0, "W", "NORMAL"),
                ("current", 5.0, "A", "NORMAL"),
                ("current", 5.4, "A", "NORMAL"),
            ]
        )
    ]


class MlLifecycleTest(unittest.TestCase):
    def test_feature_generation_is_deterministic_for_same_schema(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            service = FeatureService(ArtifactStore(directory))
            rows = [MetricRow.model_validate(row) for row in sample_rows()]

            first = service.generate_snapshot("dataset-v1", rows)
            second = service.generate_snapshot("dataset-v1", list(reversed(rows)))

            self.assertEqual(first.feature_schema_hash, second.feature_schema_hash)
            self.assertEqual(first.row_count, len(rows))
            self.assertTrue(first.artifact_uri.startswith("artifact://local/features-"))

    def test_training_persists_evaluation_metrics_and_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            service = TrainingService(artifact_store)
            request = TrainingRequest.model_validate(
                {
                    "datasetVersionId": "dataset-internal-v1",
                    "accountId": "account-1",
                    "sourceType": "INTERNAL_TELEMETRY",
                    "rows": sample_rows(),
                }
            )

            run = service.start_training(request)
            artifact = artifact_store.load_json(run.artifact_uri or "")

            self.assertEqual(run.status, "SUCCEEDED")
            self.assertEqual(run.metrics["trainingRows"], float(len(sample_rows())))
            self.assertIn("validationAnomalyScore", run.metrics)
            self.assertIn("metricStats", artifact)
            self.assertEqual(artifact["datasetVersionId"], "dataset-internal-v1")

    def test_training_rejects_empty_dataset(self) -> None:
        service = TrainingService()
        request = TrainingRequest.model_validate(
            {
                "datasetVersionId": "dataset-empty",
                "accountId": "account-1",
                "sourceType": "EXTERNAL_IMPORT",
                "rows": [],
            }
        )

        run = service.start_training(request)

        self.assertEqual(run.status, "FAILED")
        self.assertIn("at least one metric row", run.failure_reason or "")

    def test_api_trains_external_dataset_and_routes_inference_to_trained_model(self) -> None:
        client = TestClient(app)
        train = client.post(
            "/train",
            json={
                "datasetVersionId": "dataset-external-v1",
                "accountId": "account-1",
                "sourceType": "EXTERNAL_IMPORT",
                "rows": sample_rows(),
            },
        )

        self.assertEqual(train.status_code, 200)
        trained = train.json()
        self.assertEqual(trained["status"], "SUCCEEDED")
        self.assertEqual(trained["metrics"]["trainingRows"], float(len(sample_rows())))

        models = client.get("/models")
        self.assertEqual(models.status_code, 200)
        trained_model = next(model for model in models.json() if model["status"] == "READY")

        inference = client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {"voltage": 245.0, "activePowerW": 1400.0, "current": 8.0},
                "modelPreference": {
                    "modelName": trained_model["name"],
                    "allowBaselineHeuristic": False,
                },
            },
        )

        self.assertEqual(inference.status_code, 200)
        body = inference.json()
        self.assertEqual(body["predictionStatus"], "TRAINED_MODEL")
        self.assertEqual(body["modelName"], trained_model["name"])
        self.assertGreater(body["confidence"], 0.5)

    def test_api_training_run_lookup_returns_created_run(self) -> None:
        client = TestClient(app)
        train = client.post(
            "/train",
            json={
                "datasetVersionId": "dataset-lookup-v1",
                "accountId": "account-1",
                "sourceType": "INTERNAL_TELEMETRY",
                "rows": sample_rows(),
            },
        )
        run_id = train.json()["runId"]

        lookup = client.get(f"/runs/{run_id}")

        self.assertEqual(lookup.status_code, 200)
        self.assertEqual(lookup.json()["runId"], run_id)
        self.assertEqual(lookup.json()["status"], "SUCCEEDED")


if __name__ == "__main__":
    unittest.main()
