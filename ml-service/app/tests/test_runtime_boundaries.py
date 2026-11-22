import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

from app.config.settings import Settings
from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.artifact_providers import LocalArtifactStoreProvider, S3ArtifactStoreProvider
from app.services.serving_cache import ServingArtifactCache
from app.services.training_coordinator import TrainingRunCoordinator
from app.services.training_service import TrainingService


class RuntimeBoundariesTest(unittest.TestCase):
    def test_local_artifacts_cannot_read_write_or_delete_outside_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "models"
            root.mkdir()
            sentinel = Path(directory) / "sentinel.json"
            sentinel.write_text('{"private": true}', encoding="utf-8")
            provider = LocalArtifactStoreProvider(root, Settings())
            for invalid in ("../sentinel", "/sentinel", "C:\\sentinel", "nested/../sentinel", "."):
                with self.subTest(path=invalid):
                    with self.assertRaises(ValueError):
                        provider.save_json(invalid, {})
                    with self.assertRaises(ValueError):
                        provider.delete_artifact_bundle(invalid)
            with self.assertRaises(ValueError):
                provider.load_json("artifact://local/../sentinel.json")
            self.assertTrue(sentinel.exists())

    def test_s3_artifacts_cannot_use_another_bucket_or_relative_segments(self):
        client = Mock()
        provider = S3ArtifactStoreProvider("models", Settings(), client)
        for uri in (
            "s3://another/model.json",
            "s3://models/a/../model.json",
            "s3://models/file?secret=value",
        ):
            with self.subTest(uri=uri), self.assertRaises(ValueError):
                provider.load_bytes(uri)
        client.assert_not_called()

    def test_concurrent_cache_misses_load_a_model_only_once(self):
        cache = ServingArtifactCache(Mock(), max_entries=1)
        started, release = threading.Event(), threading.Event()
        runtime = SimpleNamespace(loaded_at=time.monotonic())

        def load(*_):
            started.set()
            self.assertTrue(release.wait(5))
            return runtime

        cache._load = Mock(side_effect=load)
        with ThreadPoolExecutor(max_workers=8) as executor:
            futures = [executor.submit(cache.get, {"modelVersionId": "model"}) for _ in range(8)]
            self.assertTrue(started.wait(5))
            release.set()
            self.assertTrue(all(future.result(timeout=5) is runtime for future in futures))
        self.assertEqual(cache._load.call_count, 1)
        self.assertEqual(cache.stats()["cacheSize"], 1)

    def test_clearing_cache_prevents_inflight_load_from_repopulating_it(self):
        cache = ServingArtifactCache(Mock())
        started, release = threading.Event(), threading.Event()

        def load(*_):
            started.set()
            self.assertTrue(release.wait(5))
            return SimpleNamespace(loaded_at=time.monotonic())

        cache._load = Mock(side_effect=load)
        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(cache.get, {"modelVersionId": "model"})
            self.assertTrue(started.wait(5))
            cache.clear()
            release.set()
            future.result(timeout=5)
        self.assertEqual(cache.stats()["cacheSize"], 0)

    def test_duplicate_training_requests_share_one_inflight_run(self):
        service = TrainingService(Mock(), TrainingRunCoordinator())
        request = TrainingRequest.model_validate(
            {
                "accountId": "account",
                "sourceType": "EXTERNAL_IMPORT",
                "datasetVersionId": "dataset",
                "idempotencyKey": "same",
                "rows": [],
            }
        )
        started, release = threading.Event(), threading.Event()

        def train(_):
            started.set()
            self.assertTrue(release.wait(5))
            run = TrainingRunResponse(runId="run", status="SUCCEEDED", datasetVersionId="dataset")
            return service.coordinator.record_result(
                request, run, service.coordinator.fingerprint(request)
            )

        service._start_training = Mock(side_effect=train)
        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(service.start_training, request) for _ in range(4)]
            self.assertTrue(started.wait(5))
            release.set()
            self.assertTrue(all(future.result(timeout=5).run_id == "run" for future in futures))
        self.assertEqual(service._start_training.call_count, 1)
