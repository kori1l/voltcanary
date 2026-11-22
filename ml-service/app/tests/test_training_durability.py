import multiprocessing
import os
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.training_coordinator import TrainingRunCoordinator, TrainingTimeoutError
from app.services.training_fit import fit_with_deadline
from app.services.training_history import HistoryConflictError, TrainingHistory


class BlockingModel:
    def fit(self, started_file):
        Path(started_file).write_text("started", encoding="utf-8")
        time.sleep(60)


class SmallModel:
    def fit(self, value):
        self.value = value


def _run_shared_training(path, marker, gate, ready, results):
    history = TrainingHistory(Path(path))
    history.by_run("initialize")
    ready.put(os.getpid())
    if not gate.wait(10):
        raise RuntimeError("Training worker start gate timed out")
    request = TrainingRequest(
        datasetVersionId="dataset",
        accountId="account",
        sourceType="EXTERNAL_IMPORT",
        idempotencyKey="shared",
        rows=[],
    )
    coordinator = TrainingRunCoordinator(history, maximum_seconds=10)

    def operation():
        with Path(marker).open("a", encoding="utf-8") as file:
            file.write(f"{os.getpid()}\n")
        time.sleep(1)
        return TrainingRunResponse(
            runId=str(os.getpid()), status="SUCCEEDED", datasetVersionId="dataset"
        )

    try:
        result = coordinator.singleflight(request, coordinator.fingerprint(request), operation)
        results.put(("ok", result.run_id))
    except Exception as failure:
        results.put(("error", type(failure).__name__))


def _abandon_execution_lock(path):
    with TrainingHistory(Path(path)).execution_lock("shared", 2):
        os._exit(0)


class TrainingDurabilityTest(unittest.TestCase):
    def test_independent_processes_reserve_before_training_starts(self):
        context = multiprocessing.get_context("spawn")
        with tempfile.TemporaryDirectory() as directory:
            history = str(Path(directory) / "history.sqlite3")
            marker = str(Path(directory) / "executions.txt")
            gate, ready, results = context.Event(), context.Queue(), context.Queue()
            workers = [
                context.Process(
                    target=_run_shared_training, args=(history, marker, gate, ready, results)
                )
                for _ in range(2)
            ]
            try:
                for worker in workers:
                    worker.start()
                for _ in workers:
                    ready.get(timeout=15)
                gate.set()
                responses = [results.get(timeout=15) for _ in workers]
                self.assertTrue(all(status == "ok" for status, _ in responses), responses)
                self.assertEqual(len({run_id for _, run_id in responses}), 1)
                self.assertEqual(len(Path(marker).read_text().splitlines()), 1)
            finally:
                for worker in workers:
                    worker.join(5)
                    if worker.is_alive():
                        worker.terminate()
                        worker.join(5)
                    worker.close()
                ready.close()
                results.close()

    def test_process_exit_releases_an_abandoned_reservation(self):
        context = multiprocessing.get_context("spawn")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.sqlite3"
            worker = context.Process(target=_abandon_execution_lock, args=(str(path),))
            try:
                worker.start()
                worker.join(15)
                self.assertFalse(worker.is_alive())
                self.assertEqual(worker.exitcode, 0)
                with TrainingHistory(path).execution_lock("shared", 1):
                    pass
            finally:
                if worker.is_alive():
                    worker.terminate()
                    worker.join(5)
                worker.close()

    def test_run_identity_cannot_overwrite_another_fingerprint(self):
        history = TrainingHistory()
        original = history.put(self.response("fixed-run"), "first-key", "first-fingerprint")
        with self.assertRaises(HistoryConflictError):
            history.put(self.response("fixed-run"), "second-key", "different-fingerprint")
        self.assertEqual(history.by_key("first-key", "first-fingerprint"), original)

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
                    self.assertEqual(
                        database.execute("select count(*) from training_history").fetchone()[0], 1
                    )

    def test_concurrent_history_writers_reuse_the_committed_outcome(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.sqlite3"

            def write(index):
                return TrainingHistory(path).put(
                    self.response(f"run-{index}"), "same-key", "same-fingerprint"
                )

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
            self.assertTrue(
                marker.exists(), "The deadline must interrupt fit, rather than only process startup"
            )
            self.assertLess(time.monotonic() - started, 15)
        self.assertEqual({child.pid for child in multiprocessing.active_children()}, before)

    def test_successful_fit_returns_the_fitted_state(self):
        model = fit_with_deadline(SmallModel(), (17,), 30)
        self.assertEqual(model.value, 17)
