import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app
from app.services.artifact_store import ArtifactReferenceSet, ArtifactStore, ArtifactStoreHealthError
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
            raise RuntimeError("bucket unavailable")

    def put_object(self, Bucket: str, Key: str, Body: bytes, ContentType: str) -> None:
        self.buckets.add(Bucket)
        self.objects[(Bucket, Key)] = Body

    def get_object(self, Bucket: str, Key: str) -> dict:
        return {"Body": _FakeBody(self.objects[(Bucket, Key)])}

    def list_objects_v2(self, Bucket: str, ContinuationToken: str | None = None) -> dict:
        self.head_bucket(Bucket)
        return {
            "IsTruncated": False,
            "Contents": [
                {"Key": key, "Size": len(body)}
                for bucket, key in sorted(self.objects)
                if bucket == Bucket
                for body in [self.objects[(bucket, key)]]
            ],
        }


class ArtifactGovernanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tempdir = tempfile.TemporaryDirectory()
        self._settings_override = SettingsOverride()
        self._settings_override.set(
            model_registry_path=self._tempdir.name,
            minio_endpoint=None,
            minio_access_key=None,
            minio_secret_key=None,
        )

    def tearDown(self) -> None:
        self._settings_override.restore()
        self._tempdir.cleanup()

    def test_local_artifact_store_is_allowed_for_test_profile(self) -> None:
        self._settings_override.set(ml_runtime_profile="test", ml_artifact_store="local")

        health = ArtifactStore(self._tempdir.name).health()

        self.assertEqual(health["status"], "ok")
        self.assertEqual(health["provider"], "local")

    def test_production_like_profile_rejects_local_artifact_store_readiness(self) -> None:
        self._settings_override.set(ml_runtime_profile="production", ml_artifact_store="local")
        client = TestClient(app)

        response = client.get("/health/artifacts")

        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["status"], "unhealthy")
        self.assertEqual(response.json()["code"], "local_artifact_store_forbidden")
        self.assertNotIn("secret", response.text.lower())

    def test_s3_artifact_store_health_reports_available_bucket(self) -> None:
        self._settings_override.set(ml_runtime_profile="production", ml_artifact_store="s3")
        fake = _FakeS3Client()
        fake.create_bucket("model-artifacts")
        store = ArtifactStore(self._tempdir.name)
        store.store = "s3"
        store.bucket = "model-artifacts"
        store._s3_client = fake

        health = store.health()

        self.assertEqual(health["status"], "ok")
        self.assertEqual(health["provider"], "s3")
        self.assertEqual(health["bucket"], "model-artifacts")

    def test_s3_artifact_store_health_sanitizes_unavailable_bucket(self) -> None:
        self._settings_override.set(ml_runtime_profile="production", ml_artifact_store="s3")
        store = ArtifactStore(self._tempdir.name)
        store.store = "s3"
        store.bucket = "model-artifacts"
        store._s3_client = _FakeS3Client()

        with self.assertRaises(ArtifactStoreHealthError) as context:
            store.health()

        self.assertEqual(context.exception.status_code, 503)
        self.assertEqual(context.exception.payload["code"], "artifact_store_unavailable")
        self.assertNotIn("bucket unavailable", context.exception.payload["message"])

    def test_local_cleanup_preview_blocks_all_referenced_model_artifacts(self) -> None:
        store = ArtifactStore(self._tempdir.name)
        bundle = store.save_model_bundle(
            "model-referenced",
            {"metricStats": {"voltage": {"mean": 230.0}}},
            {"modelVersionId": "model-v1", "trainingRunId": "run-v1"},
            {"model.joblib": b"serialized-model"},
        )
        feature_uri = store.save_json("features-orphan", {"rows": []})
        references = ArtifactReferenceSet.from_sources(
            training_runs=[bundle["artifactUri"]],
            model_versions=[bundle["manifestUri"]],
            deployment_policies=[bundle["artifactUri"]],
            prediction_traces=[bundle["artifactUri"]],
            audit_records=[bundle["manifestUri"]],
        )

        report = store.preview_cleanup(references)

        self.assertTrue(report["dryRun"])
        self.assertEqual([item["artifactUri"] for item in report["candidates"]], [feature_uri])
        self.assertEqual(report["blocked"][0]["artifactId"], "model-referenced")
        self.assertEqual(
            report["blocked"][0]["referenceTypes"],
            [
                "training_runs",
                "model_versions",
                "deployment_policies",
                "prediction_traces",
                "audit_records",
            ],
        )

    def test_s3_cleanup_preview_groups_bundles_and_blocks_referenced_children(self) -> None:
        fake = _FakeS3Client()
        fake.create_bucket("model-artifacts")
        fake.put_object("model-artifacts", "model-referenced/manifest.json", b"{}", "application/json")
        fake.put_object("model-artifacts", "model-referenced/model.joblib", b"model", "application/octet-stream")
        fake.put_object("model-artifacts", "features-orphan.json", b"{}", "application/json")
        store = ArtifactStore(self._tempdir.name)
        store.store = "s3"
        store.bucket = "model-artifacts"
        store._s3_client = fake
        references = ArtifactReferenceSet.from_sources(
            prediction_traces=["s3://model-artifacts/model-referenced/model.joblib"]
        )

        report = store.preview_cleanup(references)

        self.assertEqual([item["artifactId"] for item in report["candidates"]], ["features-orphan"])
        self.assertEqual(report["blocked"][0]["artifactId"], "model-referenced")
        self.assertEqual(report["blocked"][0]["referenceTypes"], ["prediction_traces"])

    def test_source_tree_model_registry_path_is_ignored_by_git_policy(self) -> None:
        repository_root = Path(__file__).resolve().parents[3]
        gitignore = (repository_root / ".gitignore").read_text(encoding="utf-8")

        self.assertIn("ml-service/models/*", gitignore)
        self.assertIn("!ml-service/models/README.md", gitignore)


if __name__ == "__main__":
    unittest.main()
