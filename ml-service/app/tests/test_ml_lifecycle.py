from datetime import datetime, timedelta, timezone
from pathlib import Path
import tempfile
import unittest

from fastapi.testclient import TestClient

from app.api import inference as inference_api
from app.api import training as training_api
from app.main import app
from app.models.failure_target import FailureTargetBuilder
from app.schemas.inference import InferenceRequest
from app.schemas.training import MetricRow, TrainingRequest
from app.services.artifact_store import ArtifactStore
from app.services.feature_service import FeatureService
from app.services.inference_service import InferenceService
from app.services.model_registry_service import model_registry_service
from app.services.serving_cache import ServingArtifactCache
from app.services.training_service import TrainingService
from app.tests.failure_fixtures import (
    EXPECTED_FAILURE_FIXTURE_SUMMARY,
    FAILURE_FIXTURE_DATASET_VERSION_ID,
    known_failure_outcome_rows,
    known_failure_training_request,
)
from app.tests.settings_helpers import SettingsOverride


class _FakeBody:
    def __init__(self, payload: bytes) -> None:
        self.payload = payload

    def read(self) -> bytes:
        return self.payload


class _FakeS3Client:
    def __init__(self) -> None:
        self.objects: dict[tuple[str, str], bytes] = {}
        self.buckets: set[str] = set()

    def create_bucket(self, Bucket: str) -> None:
        self.buckets.add(Bucket)

    def head_bucket(self, Bucket: str) -> None:
        if Bucket not in self.buckets:
            raise RuntimeError("bucket missing")

    def put_object(self, Bucket: str, Key: str, Body: bytes, ContentType: str) -> None:
        self.buckets.add(Bucket)
        self.objects[(Bucket, Key)] = Body

    def get_object(self, Bucket: str, Key: str) -> dict:
        return {"Body": _FakeBody(self.objects[(Bucket, Key)])}

    def delete_object(self, Bucket: str, Key: str) -> None:
        self.objects.pop((Bucket, Key), None)


class _FailingVerifyArtifactStore(ArtifactStore):
    def verify_model_bundle(self, manifest_uri: str, expected_hash: str | None = None) -> bool:
        raise RuntimeError("forced verification failure")


class _CountingArtifactStore(ArtifactStore):
    def __init__(self, root: str | None = None) -> None:
        super().__init__(root)
        self.load_model_bundle_calls = 0

    def load_model_bundle(self, manifest_uri: str, expected_hash: str | None = None) -> dict:
        self.load_model_bundle_calls += 1
        return super().load_model_bundle(manifest_uri, expected_hash)


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


def failure_row(
    timestamp: datetime,
    feature_row_id: str,
    metric: str,
    value: float,
    failure_within_horizon: bool,
    outcome_type: str,
    extra_metadata: dict | None = None,
) -> dict:
    metadata = {
        "featureRowId": feature_row_id,
        "failureWithinHorizon": failure_within_horizon,
        "failureOutcomeType": outcome_type,
        "horizonDays": 14,
        "featureSchemaHash": "sha256:failure-fixture",
    }
    metadata.update(extra_metadata or {})
    return {
        "timestamp": timestamp.isoformat(),
        "deviceId": "device-1",
        "metric": metric,
        "value": value,
        "metadata": metadata,
    }


class MlLifecycleTest(unittest.TestCase):
    def setUp(self) -> None:
        self._inference_service = inference_api.inference_service
        self._training_service = training_api.training_service
        self._tempdir = tempfile.TemporaryDirectory()
        artifact_store = ArtifactStore(self._tempdir.name)
        self._settings_override = SettingsOverride()
        self._settings_override.set(
            ml_internal_auth_required=False,
            model_registry_path=self._tempdir.name,
        )
        inference_api.inference_service = InferenceService(artifact_store)
        training_api.training_service = TrainingService(artifact_store)
        model_registry_service.clear()

    def tearDown(self) -> None:
        inference_api.inference_service = self._inference_service
        training_api.training_service = self._training_service
        self._settings_override.restore()
        model_registry_service.clear()
        self._tempdir.cleanup()

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
            self.assertEqual(run.status, "SUCCEEDED")
            self.assertIsNotNone(run.model_version_id)
            self.assertTrue((run.artifact_hash or "").startswith("sha256:"))
            self.assertTrue((run.artifact_uri or "").endswith("/model.joblib"))
            self.assertTrue((run.manifest_uri or "").startswith("artifact://local/model-"))
            self.assertGreater(run.artifact_size_bytes or 0, 0)
            self.assertEqual(run.metrics["trainingRows"], float(len(sample_rows())))
            self.assertIn("validationAnomalyScore", run.metrics)
            bundle = artifact_store.load_model_bundle(run.manifest_uri or "", run.artifact_hash)
            artifact = bundle["files"]["model.json"]
            self.assertIn("metricStats", artifact)
            self.assertEqual(artifact["modelType"], "trained_tabular_anomaly")
            self.assertEqual(artifact["datasetVersionId"], "dataset-internal-v1")
            manifest = bundle["manifest"]
            self.assertEqual(manifest["artifactFormat"], "voltcanary-model-bundle-v1")
            self.assertEqual(manifest["modelVersionId"], run.model_version_id)
            self.assertEqual(manifest["datasetVersionId"], "dataset-internal-v1")
            self.assertEqual(manifest["featureSchemaHash"], run.feature_snapshot.feature_schema_hash)
            self.assertEqual(bundle["files"]["model.json"]["modelVersionId"], run.model_version_id)
            self.assertIsInstance(bundle["files"]["model.joblib"], bytes)
            self.assertEqual(bundle["files"]["preprocessor.json"]["preprocessorType"], "tabular-metric-robust-scaler")

    def test_feature_artifact_loading_rejects_schema_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            service = FeatureService(artifact_store)
            rows = [MetricRow.model_validate(row) for row in sample_rows()]
            snapshot = service.generate_snapshot("dataset-v1", rows)
            artifact = artifact_store.load_json(snapshot.artifact_uri)
            artifact["features"]["new_column"] = 1.0
            artifact_store.save_json(snapshot.artifact_uri.removeprefix("artifact://local/").removesuffix(".json"), artifact)

            with self.assertRaisesRegex(ValueError, "schema hash"):
                service.load_snapshot_artifact(snapshot.artifact_uri)

    def test_artifact_store_verifies_model_bundle_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            bundle = artifact_store.save_model_bundle(
                "model-round-trip",
                {"metricStats": {"voltage": {"mean": 230.0}}},
                {
                    "modelVersionId": "model-version-1",
                    "modelFamily": "ANOMALY_DETECTION",
                    "algorithm": "baseline-statistical-anomaly-v1",
                    "featureSchemaHash": "sha256:feature",
                    "trainingRunId": "run-1",
                    "datasetVersionId": "dataset-version-1",
                    "runtime": {"python": "test"},
                    "supportedMetrics": ["voltage"],
                    "supportedDeviceTypes": [],
                    "inferenceInputContract": {"requiredFields": ["deviceId", "metrics"]},
                },
            )

            self.assertTrue(artifact_store.verify_model_bundle(bundle["manifestUri"], bundle["artifactHash"]))
            loaded = artifact_store.load_model_bundle(bundle["manifestUri"], bundle["artifactHash"])
            self.assertEqual(loaded["manifest"]["modelVersionId"], "model-version-1")
            self.assertEqual(loaded["files"]["model.json"]["metricStats"]["voltage"]["mean"], 230.0)

    def test_s3_artifact_store_round_trips_model_bundle(self) -> None:
        artifact_store = ArtifactStore()
        artifact_store.store = "s3"
        artifact_store.bucket = "model-artifacts"
        artifact_store._s3_client = _FakeS3Client()
        artifact_store._s3_client.create_bucket("model-artifacts")

        bundle = artifact_store.save_model_bundle(
            "model-s3-round-trip",
            {"metricStats": {"current": {"mean": 5.0}}},
            {
                "modelVersionId": "model-version-s3",
                "modelFamily": "ANOMALY_DETECTION",
                "algorithm": "sklearn-isolation-forest-v1",
                "featureSchemaHash": "sha256:feature",
                "trainingRunId": "run-1",
                "datasetVersionId": "dataset-version-1",
                "runtime": {"python": "test"},
                "supportedMetrics": ["current"],
                "supportedDeviceTypes": [],
                "inferenceInputContract": {"requiredFields": ["deviceId", "metrics"]},
            },
            {"model.joblib": b"serialized-model"},
        )

        self.assertTrue(bundle["artifactUri"].startswith("s3://model-artifacts/model-s3-round-trip/"))
        loaded = artifact_store.load_model_bundle(bundle["manifestUri"], bundle["artifactHash"])
        self.assertEqual(loaded["manifest"]["modelVersionId"], "model-version-s3")
        self.assertEqual(loaded["files"]["model.json"]["metricStats"]["current"]["mean"], 5.0)
        self.assertEqual(loaded["files"]["model.joblib"], b"serialized-model")

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

    def test_training_retry_with_same_idempotency_key_returns_existing_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            service = TrainingService(ArtifactStore(directory))
            request = TrainingRequest.model_validate(
                {
                    "trainingRunId": "run-idempotent-1",
                    "idempotencyKey": "ml-training:run-idempotent-1",
                    "artifactBundleId": "model-run-idempotent-1",
                    "datasetVersionId": "dataset-idempotent-v1",
                    "accountId": "account-1",
                    "sourceType": "INTERNAL_TELEMETRY",
                    "rows": sample_rows(),
                }
            )

            first = service.start_training(request)
            second = service.start_training(request)

            self.assertEqual(first.status, "SUCCEEDED")
            self.assertEqual(second.model_version_id, first.model_version_id)
            self.assertEqual(second.artifact_uri, first.artifact_uri)
            self.assertEqual(len(list((Path(directory)).glob("model-run-idempotent-1"))), 1)

    def test_api_rejects_conflicting_retry_for_same_idempotency_key(self) -> None:
        client = TestClient(app)
        payload = {
            "trainingRunId": "run-conflict-1",
            "idempotencyKey": "ml-training:run-conflict-1",
            "artifactBundleId": "model-run-conflict-1",
            "datasetVersionId": "dataset-conflict-v1",
            "accountId": "account-1",
            "sourceType": "INTERNAL_TELEMETRY",
            "rows": sample_rows(),
        }
        first = client.post("/train", json=payload)
        changed = {**payload, "algorithm": "sklearn-isolation-forest-v1"}

        conflict = client.post("/train", json=changed)

        self.assertEqual(first.status_code, 200)
        self.assertEqual(conflict.status_code, 409)
        self.assertIn("idempotency", conflict.text)

    def test_training_cancellation_before_artifact_promotion_returns_canceled(self) -> None:
        service = TrainingService()
        request = TrainingRequest.model_validate(
            {
                "trainingRunId": "run-canceled-1",
                "idempotencyKey": "ml-training:run-canceled-1",
                "artifactBundleId": "model-run-canceled-1",
                "datasetVersionId": "dataset-canceled-v1",
                "accountId": "account-1",
                "sourceType": "INTERNAL_TELEMETRY",
                "cancellationRequested": True,
                "rows": sample_rows(),
            }
        )

        run = service.start_training(request)

        self.assertEqual(run.status, "CANCELED")
        self.assertEqual(run.training_run_id, "run-canceled-1")
        self.assertIsNone(run.artifact_uri)

    def test_training_timeout_before_artifact_promotion_returns_failed(self) -> None:
        service = TrainingService()
        request = TrainingRequest.model_validate(
            {
                "trainingRunId": "run-timeout-1",
                "idempotencyKey": "ml-training:run-timeout-1",
                "artifactBundleId": "model-run-timeout-1",
                "datasetVersionId": "dataset-timeout-v1",
                "accountId": "account-1",
                "sourceType": "INTERNAL_TELEMETRY",
                "trainingTimeoutSeconds": 0,
                "rows": sample_rows(),
            }
        )

        run = service.start_training(request)

        self.assertEqual(run.status, "FAILED")
        self.assertIn("timeout", run.failure_reason or "")
        self.assertIsNone(run.artifact_uri)

    def test_partial_artifact_bundle_is_removed_when_verification_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = _FailingVerifyArtifactStore(directory)
            service = TrainingService(artifact_store)
            request = TrainingRequest.model_validate(
                {
                    "trainingRunId": "run-partial-1",
                    "idempotencyKey": "ml-training:run-partial-1",
                    "artifactBundleId": "model-run-partial-1",
                    "datasetVersionId": "dataset-partial-v1",
                    "accountId": "account-1",
                    "sourceType": "INTERNAL_TELEMETRY",
                    "rows": sample_rows(),
                }
            )

            with self.assertRaisesRegex(RuntimeError, "forced verification failure"):
                service.start_training(request)

            self.assertFalse((Path(directory) / "model-run-partial-1").exists())

    def test_failure_target_builder_constructs_horizon_targets_from_generated_labels(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        rows = [
            MetricRow.model_validate(
                {
                    "timestamp": start.isoformat(),
                    "deviceId": "device-1",
                    "metric": "rollingMean",
                    "value": 120.0,
                    "metadata": {
                        "featureRowId": "row-positive",
                        "failureWithinHorizon": True,
                        "failureOutcomeType": "CONFIRMED_FAILURE",
                        "horizonDays": 14,
                    },
                }
            ),
            MetricRow.model_validate(
                {
                    "timestamp": start.isoformat(),
                    "deviceId": "device-1",
                    "metric": "baselineDeviationRatio",
                    "value": 1.8,
                    "metadata": {
                        "featureRowId": "row-positive",
                        "failureWithinHorizon": True,
                        "failureOutcomeType": "CONFIRMED_FAILURE",
                        "horizonDays": 14,
                    },
                }
            ),
            MetricRow.model_validate(
                {
                    "timestamp": (start + timedelta(hours=1)).isoformat(),
                    "deviceId": "device-1",
                    "metric": "rollingMean",
                    "value": 100.0,
                    "metadata": {
                        "featureRowId": "row-normal",
                        "failureWithinHorizon": False,
                        "failureOutcomeType": "NORMAL",
                        "horizonDays": 14,
                    },
                }
            ),
            MetricRow.model_validate(
                {
                    "timestamp": (start + timedelta(hours=2)).isoformat(),
                    "deviceId": "device-1",
                    "metric": "rollingMean",
                    "value": 90.0,
                    "metadata": {
                        "featureRowId": "row-maintenance",
                        "failureWithinHorizon": False,
                        "failureOutcomeType": "MAINTENANCE",
                        "horizonDays": 14,
                    },
                }
            ),
        ]
        request = TrainingRequest(
            datasetVersionId="dataset-failure-v1",
            accountId="account-1",
            sourceType="INTERNAL_TELEMETRY",
            modelFamily="FAILURE_PREDICTION",
            failureHorizonDays=14,
            rows=rows,
        )

        samples = FailureTargetBuilder().build(request)
        summary = FailureTargetBuilder().summary(samples)

        self.assertEqual(len(samples), 3)
        self.assertEqual([sample.target for sample in samples], [1, 0, None])
        self.assertEqual(samples[0].features["rollingMean"], 120.0)
        self.assertEqual(samples[0].features["baselineDeviationRatio"], 1.8)
        self.assertEqual(samples[2].exclusion_reason, "MAINTENANCE")
        self.assertEqual(summary["failurePositiveLabels"], 1.0)
        self.assertEqual(summary["failureNegativeLabels"], 1.0)
        self.assertEqual(summary["failureExcludedSamples"], 1.0)

    def test_known_failure_fixture_dataset_has_expected_outcomes(self) -> None:
        request = known_failure_training_request()

        samples = FailureTargetBuilder().build(request)
        summary = FailureTargetBuilder().summary(samples)

        self.assertEqual(request.dataset_version_id, FAILURE_FIXTURE_DATASET_VERSION_ID)
        self.assertEqual(len(known_failure_outcome_rows()), 12)
        self.assertEqual(summary, EXPECTED_FAILURE_FIXTURE_SUMMARY)
        self.assertEqual([sample.target for sample in samples], [1, 0, 1, 0, 1, 0])
        self.assertEqual({sample.horizon_days for sample in samples}, {14})
        self.assertEqual(samples[0].features["rollingMean"], 120.0)
        self.assertEqual(samples[0].features["baselineDeviationRatio"], 1.8)
        self.assertEqual(samples[0].lead_time_days, 5.0)

    def test_failure_training_fits_logistic_baseline_and_persists_artifact(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            service = TrainingService(artifact_store)
            request = TrainingRequest.model_validate(
                {
                    "datasetVersionId": "dataset-failure-v1",
                    "accountId": "account-1",
                    "sourceType": "INTERNAL_TELEMETRY",
                    "modelFamily": "FAILURE_PREDICTION",
                    "failureHorizonDays": 14,
                    "rows": [
                        failure_row(start, "row-positive", "rollingMean", 120.0, True, "CONFIRMED_FAILURE"),
                        failure_row(start, "row-positive", "baselineDeviationRatio", 1.8, True, "CONFIRMED_FAILURE"),
                        failure_row(start + timedelta(hours=1), "row-normal", "rollingMean", 90.0, False, "NORMAL"),
                        failure_row(start + timedelta(hours=1), "row-normal", "baselineDeviationRatio", 0.9, False, "NORMAL"),
                    ],
                }
            )

            run = service.start_training(request)

            self.assertEqual(run.status, "SUCCEEDED")
            self.assertEqual(run.metrics["failureTargetSamples"], 2.0)
            self.assertEqual(run.metrics["failurePositiveLabels"], 1.0)
            self.assertTrue((run.artifact_hash or "").startswith("sha256:"))
            bundle = artifact_store.load_model_bundle(run.manifest_uri or "", run.artifact_hash)
            manifest = bundle["manifest"]
            file_entries = {entry["path"]: entry for entry in manifest["files"]}
            self.assertEqual(manifest["artifactContractVersion"], "failure-prediction-artifact-v1")
            self.assertEqual(manifest["modelFamily"], "FAILURE_PREDICTION")
            self.assertEqual(manifest["featureSchemaHash"], "sha256:failure-fixture")
            self.assertEqual(set(file_entries), {"model.json", "model.joblib", "model-card.json", "preprocessor.json"})
            self.assertTrue(file_entries["model.joblib"]["sha256"].startswith("sha256:"))
            self.assertEqual(file_entries["model-card.json"]["contentType"], "application/json")
            self.assertEqual(file_entries["preprocessor.json"]["contentType"], "application/json")
            model_artifact = bundle["files"]["model.json"]
            self.assertEqual(model_artifact["artifactContractVersion"], "failure-prediction-artifact-v1")
            self.assertEqual(model_artifact["modelType"], "trained_failure_prediction")
            self.assertEqual(model_artifact["modelFamily"], "FAILURE_PREDICTION")
            self.assertEqual(model_artifact["failureHorizonDays"], 14)
            self.assertEqual(model_artifact["targetContract"]["targetColumn"], "failureWithinHorizon")
            self.assertIn("CONFIRMED_FAILURE", model_artifact["targetContract"]["positiveOutcomes"])
            self.assertEqual(model_artifact["approvalGates"]["approvalGateVersion"], "failure-approval-gates-v1")
            self.assertEqual(model_artifact["approvalGates"]["profileId"], "failure-production-default")
            self.assertEqual(model_artifact["approvalGates"]["profileVersion"], "2026-08-approval-v1")
            self.assertEqual(model_artifact["approvalGates"]["thresholdSource"], "ml-service-default-config")
            self.assertEqual(model_artifact["approvalGates"]["status"], "NEEDS_REVIEW")
            self.assertFalse(model_artifact["approvalGates"]["promotionEligible"])
            self.assertIn(
                "time_based_evaluation_available",
                {gate["name"] for gate in model_artifact["approvalGates"]["criteria"]},
            )
            preprocessor = bundle["files"]["preprocessor.json"]
            self.assertEqual(preprocessor["preprocessorType"], "dict-vectorizer-standard-scaler-v1")
            self.assertEqual(preprocessor["runtimeLoader"], "sklearn-joblib-dict-vectorizer-v1")
            self.assertEqual(preprocessor["algorithm"], "sklearn-logistic-regression-failure-v1")
            self.assertEqual(preprocessor["datasetVersionId"], "dataset-failure-v1")
            self.assertEqual(preprocessor["featureSchemaHash"], "sha256:failure-fixture")
            self.assertEqual(preprocessor["featureColumns"], model_artifact["featureColumns"])
            self.assertEqual(preprocessor["targetContract"], model_artifact["targetContract"])
            model_card = bundle["files"]["model-card.json"]
            self.assertEqual(model_card["modelCardVersion"], "failure-prediction-model-card-v1")
            self.assertEqual(model_card["modelVersionId"], run.model_version_id)
            self.assertEqual(model_card["modelFamily"], "FAILURE_PREDICTION")
            self.assertEqual(model_card["intendedUse"]["predictionHorizonDays"], 14)
            self.assertEqual(model_card["trainingData"]["datasetVersionId"], "dataset-failure-v1")
            self.assertEqual(model_card["target"], model_artifact["targetContract"])
            self.assertEqual(model_card["approvalGates"], model_artifact["approvalGates"])
            self.assertIn("Rule-based risk remains separate from supervised failure risk.", model_card["limitations"])
            self.assertIsInstance(bundle["files"]["model.joblib"], bytes)

    def test_failure_training_fits_random_forest_candidate(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            service = TrainingService(artifact_store)
            request = TrainingRequest.model_validate(
                {
                    "datasetVersionId": "dataset-failure-rf-v1",
                    "accountId": "account-1",
                    "sourceType": "INTERNAL_TELEMETRY",
                    "modelFamily": "FAILURE_PREDICTION",
                    "algorithm": "sklearn-random-forest-failure-v1",
                    "failureHorizonDays": 14,
                    "rows": [
                        failure_row(start, "row-positive", "rollingMean", 120.0, True, "CONFIRMED_FAILURE"),
                        failure_row(start, "row-positive", "baselineDeviationRatio", 1.8, True, "CONFIRMED_FAILURE"),
                        failure_row(start + timedelta(hours=1), "row-normal", "rollingMean", 90.0, False, "NORMAL"),
                        failure_row(start + timedelta(hours=1), "row-normal", "baselineDeviationRatio", 0.9, False, "NORMAL"),
                    ],
                }
            )

            run = service.start_training(request)

            self.assertEqual(run.status, "SUCCEEDED")
            self.assertTrue((run.model_version or "").startswith("sklearn-random-forest-failure-v1-"))
            bundle = artifact_store.load_model_bundle(run.manifest_uri or "", run.artifact_hash)
            artifact = bundle["files"]["model.json"]
            self.assertEqual(artifact["algorithm"], "sklearn-random-forest-failure-v1")
            self.assertEqual(artifact["estimatorType"], "random_forest")
            self.assertEqual(artifact["modelType"], "trained_failure_prediction")

    def test_failure_training_stores_time_based_evaluation_metrics(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            service = TrainingService(artifact_store)
            request = known_failure_training_request(dataset_version_id="dataset-failure-evaluation-v1")

            run = service.start_training(request)

            self.assertEqual(run.status, "SUCCEEDED")
            self.assertEqual(run.metrics["failureTrainingSplitSamples"], 4.0)
            self.assertEqual(run.metrics["failureValidationSamples"], 2.0)
            self.assertIn("failurePrecision", run.metrics)
            self.assertIn("failureRecall", run.metrics)
            self.assertIn("failureF1", run.metrics)
            self.assertIn("failurePrAuc", run.metrics)
            self.assertIn("failureFalseAlertsPerDeviceMonth", run.metrics)
            self.assertIn("failureAverageLeadTimeDays", run.metrics)
            self.assertEqual(run.metrics["failureBaselinePositiveRate"], 0.5)
            self.assertIn("failureBaselineF1", run.metrics)
            self.assertIn("failureBaselinePrAuc", run.metrics)
            self.assertIn("failureBaselineFalseAlertsPerDeviceMonth", run.metrics)
            self.assertIn("failurePrAucLift", run.metrics)
            self.assertIn("failureF1Lift", run.metrics)
            self.assertEqual(run.metrics["approvalGateProfileId"], "failure-production-default")
            self.assertEqual(run.metrics["approvalGateProfileVersion"], "2026-08-approval-v1")
            self.assertIn("approvalGates", run.metrics)
            bundle = artifact_store.load_model_bundle(run.manifest_uri or "", run.artifact_hash)
            self.assertEqual(bundle["manifest"]["approvalGates"]["profileId"], "failure-production-default")
            self.assertEqual(bundle["manifest"]["evaluation"]["strategy"], "time_based_holdout")
            self.assertEqual(bundle["manifest"]["evaluation"]["status"], "EVALUATED")
            self.assertEqual(bundle["manifest"]["evaluation"]["baseline"]["name"], "training-positive-rate-baseline-v1")
            self.assertEqual(bundle["manifest"]["approvalGates"]["status"], "PASSED")
            self.assertTrue(bundle["manifest"]["approvalGates"]["promotionEligible"])
            self.assertEqual(bundle["files"]["model.json"]["evaluation"]["status"], "EVALUATED")
            self.assertEqual(bundle["files"]["model.json"]["evaluation"]["baseline"]["score"], 0.5)
            self.assertEqual(bundle["files"]["model.json"]["approvalGates"]["status"], "PASSED")
            self.assertEqual(bundle["files"]["model.json"]["metrics"]["failureValidationSamples"], 2.0)
            model_card = bundle["files"]["model-card.json"]
            self.assertEqual(model_card["evaluation"]["status"], "EVALUATED")
            self.assertEqual(model_card["evaluation"]["baseline"]["name"], "training-positive-rate-baseline-v1")
            self.assertEqual(model_card["evaluation"]["metrics"]["failureValidationSamples"], 2.0)
            gate_names = {gate["name"] for gate in model_card["approvalGates"]["criteria"]}
            self.assertIn("minimum_positive_labels", gate_names)
            self.assertIn("artifact_load_test", gate_names)
            self.assertEqual(model_card["approvalGates"]["status"], "PASSED")

    def test_failure_training_applies_custom_approval_gate_profile(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            service = TrainingService(artifact_store)
            request = TrainingRequest.model_validate(
                {
                    "datasetVersionId": "dataset-failure-strict-profile-v1",
                    "accountId": "account-1",
                    "sourceType": "INTERNAL_TELEMETRY",
                    "modelFamily": "FAILURE_PREDICTION",
                    "failureHorizonDays": 14,
                    "approvalGateProfile": {
                        "profileId": "strict-failure-canary",
                        "profileVersion": "v2",
                        "thresholdSource": "analytics-service-config",
                        "minimumPrecision": 1.1,
                        "minimumRecall": 0.6,
                        "minimumPrAuc": 0.6,
                        "maximumFalseAlertsPerDeviceMonth": 10.0,
                        "minimumLeadTimeDays": 1.0,
                    },
                    "rows": known_failure_outcome_rows(),
                }
            )

            run = service.start_training(request)

            self.assertEqual(run.status, "SUCCEEDED")
            gates = run.metrics["approvalGates"]
            self.assertEqual(gates["profileId"], "strict-failure-canary")
            self.assertEqual(gates["profileVersion"], "v2")
            self.assertEqual(gates["thresholdSource"], "analytics-service-config")
            self.assertEqual(gates["status"], "NEEDS_REVIEW")
            precision_gate = next(gate for gate in gates["criteria"] if gate["name"] == "minimum_precision")
            self.assertEqual(precision_gate["threshold"], 1.1)
            self.assertFalse(precision_gate["passed"])

    def test_failure_training_rejects_insufficient_positive_labels(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        service = TrainingService()
        request = TrainingRequest.model_validate(
            {
                "datasetVersionId": "dataset-failure-no-positive-v1",
                "accountId": "account-1",
                "sourceType": "INTERNAL_TELEMETRY",
                "modelFamily": "FAILURE_PREDICTION",
                "failureHorizonDays": 14,
                "minimumPositiveLabels": 1,
                "rows": [
                    failure_row(start, "row-normal", "rollingMean", 90.0, False, "NORMAL"),
                    failure_row(start + timedelta(hours=1), "row-normal-2", "rollingMean", 95.0, False, "NORMAL"),
                ],
            }
        )

        run = service.start_training(request)

        self.assertEqual(run.status, "FAILED")
        self.assertEqual(run.metrics["failurePositiveLabels"], 0.0)
        self.assertIn("Insufficient positive failure labels", run.failure_reason or "")

    def test_failure_training_rejects_missing_negative_class(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        service = TrainingService()
        request = TrainingRequest.model_validate(
            {
                "datasetVersionId": "dataset-failure-no-negative-v1",
                "accountId": "account-1",
                "sourceType": "INTERNAL_TELEMETRY",
                "modelFamily": "FAILURE_PREDICTION",
                "failureHorizonDays": 14,
                "rows": [
                    failure_row(start, "row-positive", "rollingMean", 120.0, True, "CONFIRMED_FAILURE"),
                    failure_row(start + timedelta(hours=1), "row-positive-2", "rollingMean", 130.0, True, "CONFIRMED_FAILURE"),
                ],
            }
        )

        run = service.start_training(request)

        self.assertEqual(run.status, "FAILED")
        self.assertEqual(run.metrics["failureNegativeLabels"], 0.0)
        self.assertIn("Insufficient negative non-failure labels", run.failure_reason or "")

    def test_failure_training_rejects_low_label_coverage_gate(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        service = TrainingService()
        request = TrainingRequest.model_validate(
            {
                "datasetVersionId": "dataset-failure-low-coverage-v1",
                "accountId": "account-1",
                "sourceType": "INTERNAL_TELEMETRY",
                "modelFamily": "FAILURE_PREDICTION",
                "failureHorizonDays": 14,
                "minimumLabelCoverage": 0.8,
                "rows": [
                    failure_row(start, "row-positive", "rollingMean", 120.0, True, "CONFIRMED_FAILURE"),
                    failure_row(start + timedelta(hours=1), "row-normal", "rollingMean", 90.0, False, "NORMAL"),
                    failure_row(start + timedelta(hours=2), "row-unlabeled", "rollingMean", 95.0, False, "MAINTENANCE"),
                ],
            }
        )

        run = service.start_training(request)

        self.assertEqual(run.status, "FAILED")
        self.assertEqual(run.metrics["labelCoverage"], 0.666667)
        self.assertIn("Insufficient failure label coverage", run.failure_reason or "")

    def test_failure_model_inference_returns_failure_risk_fields(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            training_service = TrainingService(artifact_store)
            run = training_service.start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-failure-inference-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "modelFamily": "FAILURE_PREDICTION",
                        "failureHorizonDays": 14,
                        "rows": [
                            failure_row(start, "row-positive", "rollingMean", 120.0, True, "CONFIRMED_FAILURE"),
                            failure_row(start, "row-positive", "baselineDeviationRatio", 1.8, True, "CONFIRMED_FAILURE"),
                            failure_row(start + timedelta(hours=1), "row-normal", "rollingMean", 90.0, False, "NORMAL"),
                            failure_row(start + timedelta(hours=1), "row-normal", "baselineDeviationRatio", 0.9, False, "NORMAL"),
                        ],
                    }
                )
            )
            model_registry_service.clear()

            response = InferenceService(artifact_store).predict(
                InferenceRequest.model_validate(
                    {
                        "deviceId": "device-1",
                        "metrics": {"rollingMean": 118.0, "baselineDeviationRatio": 1.7},
                        "modelPreference": {
                            "modelVersionId": run.model_version_id,
                            "manifestUri": run.manifest_uri,
                            "artifactUri": run.artifact_uri,
                            "artifactHash": run.artifact_hash,
                            "modelStatus": "APPROVED",
                            "allowBaselineHeuristic": False,
                        },
                    }
                )
            )

            self.assertEqual(response.prediction_status, "TRAINED_MODEL")
            self.assertEqual(response.model_family, "FAILURE_PREDICTION")
            self.assertEqual(response.anomaly_score, 0.0)
            self.assertEqual(response.degradation_score, 0.0)
            self.assertEqual(response.failure_horizon_days, 14)
            self.assertIsNotNone(response.failure_risk_score)
            self.assertIsNotNone(response.failure_risk_band)
            self.assertGreater(response.confidence, 0.35)
            self.assertTrue(response.risk_drivers)
            self.assertEqual(response.training_data_window["datasetVersionId"], "dataset-failure-inference-v1")
            self.assertIn("sklearn-logistic-regression-failure-v1", response.explanations[0])

    def test_failure_model_inference_scores_online_feature_vector(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            training_service = TrainingService(artifact_store)
            run = training_service.start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-failure-online-features-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "modelFamily": "FAILURE_PREDICTION",
                        "failureHorizonDays": 14,
                        "rows": [
                            failure_row(start, "row-positive", "rollingMean", 120.0, True, "CONFIRMED_FAILURE"),
                            failure_row(start, "row-positive", "baselineDeviationRatio", 1.8, True, "CONFIRMED_FAILURE"),
                            failure_row(start + timedelta(hours=1), "row-normal", "rollingMean", 90.0, False, "NORMAL"),
                            failure_row(start + timedelta(hours=1), "row-normal", "baselineDeviationRatio", 0.9, False, "NORMAL"),
                        ],
                    }
                )
            )

            response = InferenceService(artifact_store).predict(
                InferenceRequest.model_validate(
                    {
                        "deviceId": "device-1",
                        "metrics": {"metric": "activePowerW", "latestValue": 118.0},
                        "featureVector": {"rollingMean": 118.0, "baselineDeviationRatio": 1.7},
                        "featureGenerationStatus": "READY",
                        "modelPreference": {
                            "modelVersionId": run.model_version_id,
                            "manifestUri": run.manifest_uri,
                            "artifactUri": run.artifact_uri,
                            "artifactHash": run.artifact_hash,
                            "modelStatus": "APPROVED",
                            "allowBaselineHeuristic": False,
                        },
                    }
                )
            )

            self.assertEqual(response.prediction_status, "TRAINED_MODEL")
            self.assertEqual(response.model_family, "FAILURE_PREDICTION")
            self.assertEqual(response.model_version_id, run.model_version_id)
            self.assertIsNotNone(response.failure_risk_score)

    def test_serving_artifact_cache_reuses_loaded_failure_pipeline(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = _CountingArtifactStore(directory)
            run = TrainingService(artifact_store).start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-failure-cache-hit-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "modelFamily": "FAILURE_PREDICTION",
                        "failureHorizonDays": 14,
                        "rows": [
                            failure_row(start, "row-positive", "rollingMean", 120.0, True, "CONFIRMED_FAILURE"),
                            failure_row(start, "row-positive", "baselineDeviationRatio", 1.8, True, "CONFIRMED_FAILURE"),
                            failure_row(start + timedelta(hours=1), "row-normal", "rollingMean", 90.0, False, "NORMAL"),
                            failure_row(start + timedelta(hours=1), "row-normal", "baselineDeviationRatio", 0.9, False, "NORMAL"),
                        ],
                    }
                )
            )
            artifact_store.load_model_bundle_calls = 0
            service = InferenceService(artifact_store, ServingArtifactCache(artifact_store, max_entries=2, ttl_seconds=300))
            request = InferenceRequest.model_validate(
                {
                    "deviceId": "device-1",
                    "metrics": {"rollingMean": 118.0, "baselineDeviationRatio": 1.7},
                    "modelPreference": {
                        "modelVersionId": run.model_version_id,
                        "manifestUri": run.manifest_uri,
                        "artifactUri": run.artifact_uri,
                        "artifactHash": run.artifact_hash,
                        "modelStatus": "APPROVED",
                        "allowBaselineHeuristic": False,
                    },
                }
            )

            first = service.predict(request)
            second = service.predict(request)

            self.assertEqual(first.prediction_status, "TRAINED_MODEL")
            self.assertEqual(second.prediction_status, "TRAINED_MODEL")
            self.assertEqual(first.model_family, "FAILURE_PREDICTION")
            self.assertEqual(artifact_store.load_model_bundle_calls, 1)
            self.assertEqual(service.serving_cache.stats()["hits"], 1)

    def test_failure_model_inference_loads_by_model_version_id_from_registry(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            training_service = TrainingService(artifact_store)
            run = training_service.start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-failure-registry-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "modelFamily": "FAILURE_PREDICTION",
                        "failureHorizonDays": 14,
                        "rows": [
                            failure_row(start, "row-positive", "rollingMean", 120.0, True, "CONFIRMED_FAILURE"),
                            failure_row(start, "row-positive", "baselineDeviationRatio", 1.8, True, "CONFIRMED_FAILURE"),
                            failure_row(start + timedelta(hours=1), "row-normal", "rollingMean", 90.0, False, "NORMAL"),
                            failure_row(start + timedelta(hours=1), "row-normal", "baselineDeviationRatio", 0.9, False, "NORMAL"),
                        ],
                    }
                )
            )

            response = InferenceService(artifact_store).predict(
                InferenceRequest.model_validate(
                    {
                        "deviceId": "device-1",
                        "metrics": {"rollingMean": 118.0, "baselineDeviationRatio": 1.7},
                        "modelPreference": {
                            "modelVersionId": run.model_version_id,
                            "allowBaselineHeuristic": False,
                        },
                    }
                )
            )

            self.assertEqual(response.prediction_status, "TRAINED_MODEL")
            self.assertEqual(response.model_version_id, run.model_version_id)
            self.assertEqual(response.model_family, "FAILURE_PREDICTION")
            self.assertEqual(response.model_type, "trained_failure_prediction")
            self.assertEqual(response.anomaly_score, 0.0)
            self.assertEqual(response.degradation_score, 0.0)
            self.assertIsNotNone(response.failure_risk_score)
            self.assertTrue(response.risk_drivers)

    def test_failure_model_inference_returns_features_unavailable_for_missing_feature_columns(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            training_service = TrainingService(artifact_store)
            run = training_service.start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-failure-missing-feature-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "modelFamily": "FAILURE_PREDICTION",
                        "failureHorizonDays": 14,
                        "rows": [
                            failure_row(start, "row-positive", "rollingMean", 120.0, True, "CONFIRMED_FAILURE"),
                            failure_row(start, "row-positive", "baselineDeviationRatio", 1.8, True, "CONFIRMED_FAILURE"),
                            failure_row(start + timedelta(hours=1), "row-normal", "rollingMean", 90.0, False, "NORMAL"),
                            failure_row(start + timedelta(hours=1), "row-normal", "baselineDeviationRatio", 0.9, False, "NORMAL"),
                        ],
                    }
                )
            )

            response = InferenceService(artifact_store).predict(
                InferenceRequest.model_validate(
                    {
                        "deviceId": "device-1",
                        "metrics": {"rollingMean": 118.0},
                        "modelPreference": {
                            "modelVersionId": run.model_version_id,
                            "manifestUri": run.manifest_uri,
                            "artifactUri": run.artifact_uri,
                            "artifactHash": run.artifact_hash,
                            "allowBaselineHeuristic": False,
                        },
                    }
                )
            )

            self.assertEqual(response.prediction_status, "FEATURES_UNAVAILABLE")
            self.assertEqual(response.model_family, "FAILURE_PREDICTION")
            self.assertIn("baselineDeviationRatio", response.explanations[0])

    def test_failure_model_inference_returns_insufficient_labels_from_artifact_gates(self) -> None:
        start = datetime(2026, 7, 30, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            training_service = TrainingService(artifact_store)
            run = training_service.start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-failure-insufficient-label-runtime-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "modelFamily": "FAILURE_PREDICTION",
                        "failureHorizonDays": 14,
                        "rows": [
                            failure_row(start, "row-positive", "rollingMean", 120.0, True, "CONFIRMED_FAILURE"),
                            failure_row(start, "row-positive", "baselineDeviationRatio", 1.8, True, "CONFIRMED_FAILURE"),
                            failure_row(start + timedelta(hours=1), "row-normal", "rollingMean", 90.0, False, "NORMAL"),
                            failure_row(start + timedelta(hours=1), "row-normal", "baselineDeviationRatio", 0.9, False, "NORMAL"),
                        ],
                    }
                )
            )
            loaded = artifact_store.load_model_bundle(run.manifest_uri or "", run.artifact_hash)
            artifact = dict(loaded["files"]["model.json"])
            gates = dict(artifact["approvalGates"])
            gates["criteria"] = [
                {**gate, "passed": False}
                if gate["name"] == "minimum_positive_labels"
                else gate
                for gate in gates["criteria"]
            ]
            gates["status"] = "NEEDS_REVIEW"
            gates["promotionEligible"] = False
            artifact["approvalGates"] = gates
            manifest = dict(loaded["manifest"])
            bundle = artifact_store.save_model_bundle(
                "model-insufficient-label-runtime",
                artifact,
                manifest,
                {
                    "model.joblib": loaded["files"]["model.joblib"],
                    "preprocessor.json": loaded["files"]["preprocessor.json"],
                },
            )

            response = InferenceService(artifact_store).predict(
                InferenceRequest.model_validate(
                    {
                        "deviceId": "device-1",
                        "metrics": {"rollingMean": 118.0, "baselineDeviationRatio": 1.7},
                        "modelPreference": {
                            "modelVersionId": run.model_version_id,
                            "manifestUri": bundle["manifestUri"],
                            "artifactUri": bundle["artifactUri"],
                            "artifactHash": bundle["artifactHash"],
                            "allowBaselineHeuristic": False,
                        },
                    }
                )
            )

            self.assertEqual(response.prediction_status, "INSUFFICIENT_LABELS")
            self.assertEqual(response.model_family, "FAILURE_PREDICTION")
            self.assertEqual(response.training_data_window["approvalStatus"], "NEEDS_REVIEW")
            self.assertIn("confirmed failure", response.explanations[0])

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
        self.assertEqual(trained_model["modelVersionId"], trained["modelVersionId"])
        self.assertTrue(trained_model["artifactHash"].startswith("sha256:"))

        inference = client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {"voltage": 245.0, "activePowerW": 1400.0, "current": 8.0},
                "modelPreference": {
                    "modelVersionId": trained_model["modelVersionId"],
                    "manifestUri": trained["manifestUri"],
                    "artifactUri": trained["artifactUri"],
                    "artifactHash": trained["artifactHash"],
                    "allowBaselineHeuristic": False,
                },
            },
        )

        self.assertEqual(inference.status_code, 200)
        body = inference.json()
        self.assertEqual(body["predictionStatus"], "TRAINED_MODEL")
        self.assertEqual(body["modelVersionId"], trained_model["modelVersionId"])
        self.assertEqual(body["modelName"], trained_model["name"])
        self.assertGreater(body["confidence"], 0.5)

    def test_inference_loads_model_from_artifact_metadata_without_registry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            training_service = TrainingService(artifact_store)
            request = TrainingRequest.model_validate(
                {
                    "datasetVersionId": "dataset-approved-model-v1",
                    "accountId": "account-1",
                    "sourceType": "INTERNAL_TELEMETRY",
                    "rows": sample_rows(),
                }
            )
            run = training_service.start_training(request)
            model_registry_service.clear()

            response = InferenceService(artifact_store).predict(
                InferenceRequest.model_validate(
                    {
                        "deviceId": "device-1",
                        "metrics": {"voltage": 245.0, "activePowerW": 1400.0, "current": 8.0},
                        "modelPreference": {
                            "modelVersionId": run.model_version_id,
                            "manifestUri": run.manifest_uri,
                            "artifactUri": run.artifact_uri,
                            "artifactHash": run.artifact_hash,
                            "modelStatus": "APPROVED",
                            "allowBaselineHeuristic": False,
                        },
                    }
                )
            )

            self.assertEqual(response.prediction_status, "TRAINED_MODEL")
            self.assertEqual(response.model_version_id, run.model_version_id)
            self.assertEqual(response.model_status, "APPROVED")
            self.assertGreater(response.confidence, 0.5)

    def test_serving_artifact_cache_reuses_loaded_anomaly_pipeline(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = _CountingArtifactStore(directory)
            run = TrainingService(artifact_store).start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-cache-hit-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "rows": sample_rows(),
                    }
                )
            )
            artifact_store.load_model_bundle_calls = 0
            service = InferenceService(
                artifact_store,
                ServingArtifactCache(artifact_store, max_entries=2, ttl_seconds=300),
            )
            request = InferenceRequest.model_validate(
                {
                    "deviceId": "device-1",
                    "metrics": {"voltage": 245.0, "activePowerW": 1400.0, "current": 8.0},
                    "modelPreference": {
                        "modelVersionId": run.model_version_id,
                        "manifestUri": run.manifest_uri,
                        "artifactUri": run.artifact_uri,
                        "artifactHash": run.artifact_hash,
                        "allowBaselineHeuristic": False,
                    },
                }
            )

            first = service.predict(request)
            second = service.predict(request)

            self.assertEqual(first.prediction_status, "TRAINED_MODEL")
            self.assertEqual(second.prediction_status, "TRAINED_MODEL")
            self.assertEqual(artifact_store.load_model_bundle_calls, 1)
            self.assertEqual(service.serving_cache.stats()["hits"], 1)
            self.assertEqual(service.serving_cache.stats()["misses"], 1)

    def test_serving_artifact_cache_reloads_when_artifact_hash_changes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = _CountingArtifactStore(directory)
            run = TrainingService(artifact_store).start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-cache-reload-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "rows": sample_rows(),
                    }
                )
            )
            loaded = artifact_store.load_model_bundle(run.manifest_uri or "", run.artifact_hash)
            model_payload = dict(loaded["files"]["model.json"], version="cache-reload-version")
            manifest = dict(loaded["manifest"])
            manifest.pop("artifactHash", None)
            bundle = artifact_store.save_model_bundle(
                "model-cache-reload",
                model_payload,
                manifest,
                {
                    "model.joblib": loaded["files"]["model.joblib"],
                    "preprocessor.json": loaded["files"]["preprocessor.json"],
                },
            )
            artifact_store.load_model_bundle_calls = 0
            service = InferenceService(artifact_store, ServingArtifactCache(artifact_store, max_entries=2, ttl_seconds=300))

            first = service.predict(self._artifact_request(run.model_version_id, run.manifest_uri, run.artifact_uri, run.artifact_hash))
            second = service.predict(self._artifact_request(run.model_version_id, bundle["manifestUri"], bundle["artifactUri"], bundle["artifactHash"]))

            self.assertEqual(first.prediction_status, "TRAINED_MODEL")
            self.assertEqual(second.prediction_status, "TRAINED_MODEL")
            self.assertEqual(artifact_store.load_model_bundle_calls, 2)

    def test_serving_artifact_cache_does_not_cache_corrupted_artifact_loads(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = _CountingArtifactStore(directory)
            run = TrainingService(artifact_store).start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-cache-corrupt-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "rows": sample_rows(),
                    }
                )
            )
            artifact_path = Path(directory) / (run.artifact_uri or "").removeprefix("artifact://local/")
            artifact_path.write_bytes(b"corrupted")
            artifact_store.load_model_bundle_calls = 0
            service = InferenceService(artifact_store, ServingArtifactCache(artifact_store, max_entries=2, ttl_seconds=300))
            request = self._artifact_request(run.model_version_id, run.manifest_uri, run.artifact_uri, run.artifact_hash)

            first = service.predict(request)
            second = service.predict(request)

            self.assertEqual(first.prediction_status, "MODEL_UNAVAILABLE")
            self.assertEqual(second.prediction_status, "MODEL_UNAVAILABLE")
            self.assertEqual(artifact_store.load_model_bundle_calls, 2)
            self.assertEqual(service.serving_cache.stats()["loadFailures"], 2)
            self.assertEqual(service.serving_cache.stats()["cacheSize"], 0)

    def test_serving_artifact_cache_evicts_least_recent_entry_at_capacity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = _CountingArtifactStore(directory)
            first_run = TrainingService(artifact_store).start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-cache-evict-a-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "rows": sample_rows(),
                    }
                )
            )
            second_run = TrainingService(artifact_store).start_training(
                TrainingRequest.model_validate(
                    {
                        "datasetVersionId": "dataset-cache-evict-b-v1",
                        "accountId": "account-1",
                        "sourceType": "INTERNAL_TELEMETRY",
                        "rows": sample_rows(),
                    }
                )
            )
            artifact_store.load_model_bundle_calls = 0
            service = InferenceService(artifact_store, ServingArtifactCache(artifact_store, max_entries=1, ttl_seconds=300))

            service.predict(self._artifact_request(first_run.model_version_id, first_run.manifest_uri, first_run.artifact_uri, first_run.artifact_hash))
            service.predict(self._artifact_request(second_run.model_version_id, second_run.manifest_uri, second_run.artifact_uri, second_run.artifact_hash))
            service.predict(self._artifact_request(first_run.model_version_id, first_run.manifest_uri, first_run.artifact_uri, first_run.artifact_hash))

            self.assertEqual(artifact_store.load_model_bundle_calls, 3)
            self.assertEqual(service.serving_cache.stats()["evictions"], 2)
            self.assertEqual(service.serving_cache.stats()["cacheSize"], 1)

    def test_inference_returns_model_unavailable_when_runtime_artifact_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_store = ArtifactStore(directory)
            training_service = TrainingService(artifact_store)
            request = TrainingRequest.model_validate(
                {
                    "datasetVersionId": "dataset-missing-artifact-v1",
                    "accountId": "account-1",
                    "sourceType": "INTERNAL_TELEMETRY",
                    "rows": sample_rows(),
                }
            )
            run = training_service.start_training(request)
            artifact_path = Path(directory) / (run.artifact_uri or "").removeprefix("artifact://local/")
            artifact_path.unlink()

            response = InferenceService(artifact_store).predict(
                InferenceRequest.model_validate(
                    {
                        "deviceId": "device-1",
                        "metrics": {"voltage": 245.0, "activePowerW": 1400.0, "current": 8.0},
                        "modelPreference": {
                            "modelVersionId": run.model_version_id,
                            "manifestUri": run.manifest_uri,
                            "artifactUri": run.artifact_uri,
                            "artifactHash": run.artifact_hash,
                            "allowBaselineHeuristic": False,
                        },
                    }
                )
            )

            self.assertEqual(response.prediction_status, "MODEL_UNAVAILABLE")
            self.assertEqual(response.model_version_id, run.model_version_id)

    def _artifact_request(
        self,
        model_version_id: str | None,
        manifest_uri: str | None,
        artifact_uri: str | None,
        artifact_hash: str | None,
    ) -> InferenceRequest:
        return InferenceRequest.model_validate(
            {
                "deviceId": "device-1",
                "metrics": {"voltage": 245.0, "activePowerW": 1400.0, "current": 8.0},
                "modelPreference": {
                    "modelVersionId": model_version_id,
                    "manifestUri": manifest_uri,
                    "artifactUri": artifact_uri,
                    "artifactHash": artifact_hash,
                    "allowBaselineHeuristic": False,
                },
            }
        )

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


def load_tests(loader, tests, pattern):
    return unittest.TestSuite()


if __name__ == "__main__":
    unittest.main()
