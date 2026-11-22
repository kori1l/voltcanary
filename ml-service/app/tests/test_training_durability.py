from concurrent.futures import ThreadPoolExecutor
import multiprocessing
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from app.schemas.training import TrainingRunResponse
from app.services.training_coordinator import TrainingTimeoutError
from app.services.training_fit import fit_with_deadline
from app.services.training_history import HistoryConflictError, TrainingHistory


class BlockingModel:
    def fit(self, started_file):
        Path(started_file).write_text("started", encoding="utf-8")
        time.sleep(60)


class SmallModel:
    def fit(self, value):
        self.value = value


class TrainingDurabilityTest(unittest.TestCase):
    def response(self, run_id):
        return TrainingRunResponse(runId=run_id, status="SUCCEEDED", datasetVersionId="dataset")

    def test_history_survives_restart_and_bounds_retention_and_capacity(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.sqlite3"
            history = TrainingHistory(path, capacity=2, retention_seconds=10)
            with patch("app.services.training_history.time.time", return_value=100):
                history.put(self.response("first"), "key-1", "fp-1")
            with patch("app.services.training_history.time.time", return_value=101):
                history.put(self.response("second"), "key-2", "fp-2")
            with patch("app.services.training_history.time.time", return_value=102):
                history.put(self.response("third"), "key-3", "fp-3")
                restarted = TrainingHistory(path, capacity=2, retention_seconds=10)
                self.assertIsNone(restarted.by_run("first"))
                self.assertEqual(restarted.by_key("key-2", "fp-2").run_id, "second")
                with self.assertRaises(HistoryConflictError):
                    restarted.by_key("key-2", "different")
            with patch("app.services.training_history.time.time", return_value=112):
                self.assertIsNone(restarted.by_run("third"))
                restarted.put(self.response("fourth"), "key-4", "fp-4")
                with restarted._database() as database:
                    self.assertEqual(database.execute("select count(*) from training_history").fetchone()[0], 1)

    def test_concurrent_history_writers_reuse_the_committed_outcome(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.sqlite3"
            def write(index):
                return TrainingHistory(path).put(self.response(f"run-{index}"), "same-key", "same-fingerprint")
            with ThreadPoolExecutor(max_workers=4) as executor:
                results = list(executor.map(write, range(8)))
            self.assertEqual(len({result.run_id for result in results}), 1)
            with self.assertRaises(HistoryConflictError):
                TrainingHistory(path).put(self.response("conflict"), "same-key", "changed")

    def test_fit_deadline_terminates_a_fit_that_has_actually_started(self):
        before = {child.pid for child in multiprocessing.active_children()}
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "started"
            started = time.monotonic()
            with self.assertRaises(TrainingTimeoutError):
                fit_with_deadline(BlockingModel(), (str(marker),), 8)
            self.assertTrue(marker.exists(), "The deadline must interrupt fit, rather than only process startup")
            self.assertLess(time.monotonic() - started, 15)
        self.assertEqual({child.pid for child in multiprocessing.active_children()}, before)

    def test_successful_fit_returns_the_fitted_state(self):
        model = fit_with_deadline(SmallModel(), (17,), 30)
        self.assertEqual(model.value, 17)
