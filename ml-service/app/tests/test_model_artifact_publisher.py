import unittest
from importlib.metadata import version
from unittest.mock import Mock

from app.schemas.training import TrainingRequest
from app.services.artifact_providers import ArtifactConflictError
from app.services.model_artifact_publisher import ModelArtifactPublisher


class ModelArtifactPublisherTest(unittest.TestCase):
    def test_a_conflicting_publication_never_deletes_the_original_bundle(self):
        store = Mock()
        store.save_model_bundle.side_effect = ArtifactConflictError("Already reserved")
        with self.assertRaises(ArtifactConflictError):
            ModelArtifactPublisher(store).save_verified_bundle("bundle-1", {}, {}, {})
        store.delete_artifact_bundle.assert_not_called()

    def test_manifest_records_installed_runtime_versions(self):
        publisher = ModelArtifactPublisher(Mock())
        request = TrainingRequest(
            datasetVersionId="dataset-1",
            accountId="account-1",
            sourceType="INTERNAL_TELEMETRY",
            rows=[],
        )
        manifest = publisher.manifest("model-1", "run-1", request, feature_schema_hash="schema-1")
        self.assertEqual(
            manifest["runtime"]["dependencies"],
            {name: version(name) for name in ("numpy", "scikit-learn", "joblib")},
        )
        self.assertEqual(manifest["inferenceInputContract"]["featureSchemaHash"], "schema-1")

    def test_manifest_rejects_missing_feature_lineage(self):
        publisher = ModelArtifactPublisher(Mock())
        request = TrainingRequest(
            datasetVersionId="dataset-1",
            accountId="account-1",
            sourceType="INTERNAL_TELEMETRY",
            rows=[],
        )
        with self.assertRaisesRegex(ValueError, "feature schema"):
            publisher.manifest("model-1", "run-1", request)

    def test_cleanup_failure_preserves_the_original_publication_failure(self):
        store = Mock()
        original = ValueError("verification failed")
        cleanup = OSError("cleanup unavailable")
        store.save_model_bundle.return_value = {"manifestUri": "manifest", "artifactHash": "hash"}
        store.verify_model_bundle.side_effect = original
        store.delete_artifact_bundle.side_effect = cleanup
        with self.assertRaises(ValueError) as failure:
            ModelArtifactPublisher(store).save_verified_bundle("bundle-1", {}, {}, {})
        self.assertIs(failure.exception, original)
        self.assertIs(failure.exception.__cause__, cleanup)
