"""Optional native PostgreSQL qualification; no container runtime is used."""

import multiprocessing
import os
import tempfile
import time
import unittest
from pathlib import Path
from uuid import uuid4

import psycopg
from psycopg.conninfo import conninfo_to_dict

from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.postgres_training_history import PostgresTrainingHistory
from app.services.training_coordinator import TrainingRunCoordinator
from app.services.training_execution_lock import TrainingBusyError
from app.services.training_history import HistoryConflictError


def _execute_shared(options, key, marker, ready, gate, results):
    coordinator = TrainingRunCoordinator(PostgresTrainingHistory(options, 1000, 3600), 10)
    request = TrainingRequest(
        datasetVersionId=key,
        accountId="quality",
        sourceType="EXTERNAL_IMPORT",
        idempotencyKey=key,
        rows=[],
    )
    ready.put(True)
    if not gate.wait(20):
        raise RuntimeError("Training test start gate timed out")

    def execute():
        with Path(marker).open("a", encoding="utf-8") as stream:
            stream.write("fit\n")
        time.sleep(0.5)
        return TrainingRunResponse(runId=key, status="SUCCEEDED", datasetVersionId=key)

    response = coordinator.singleflight(request, coordinator.fingerprint(request), execute)
    results.put(response.run_id)


def _hold_reservation(options, key, ready):
    with PostgresTrainingHistory(options, 1000, 3600).execution_lock(key, 5):
        ready.set()
        time.sleep(60)


@unittest.skipUnless(
    os.getenv("ML_TEST_POSTGRES_DSN"),
    "Set ML_TEST_POSTGRES_DSN for native PostgreSQL qualification",
)
class PostgresTrainingHistoryTest(unittest.TestCase):
    def setUp(self):
        self.options = conninfo_to_dict(os.environ["ML_TEST_POSTGRES_DSN"])
        self.history = PostgresTrainingHistory(self.options, 1000, 3600)
        self.key = "quality-" + str(uuid4())

    def test_independent_processes_execute_one_fit_and_share_durable_result(self):
        context = multiprocessing.get_context("spawn")
        with tempfile.TemporaryDirectory() as directory:
            marker = str(Path(directory) / "fits.txt")
            gate, ready, results = context.Event(), context.Queue(), context.Queue()
            workers = [
                context.Process(
                    target=_execute_shared,
                    args=(self.options, self.key, marker, ready, gate, results),
                )
                for _ in range(2)
            ]
            try:
                for worker in workers:
                    worker.start()
                for _ in workers:
                    self.assertTrue(ready.get(timeout=30))
                gate.set()
                self.assertEqual([results.get(timeout=30) for _ in workers], [self.key, self.key])
                for worker in workers:
                    worker.join(10)
                    self.assertEqual(worker.exitcode, 0)
                self.assertEqual(Path(marker).read_text(encoding="utf-8").splitlines(), ["fit"])
                retained = PostgresTrainingHistory(self.options, 1000, 3600).by_run(self.key)
                self.assertEqual(retained.run_id, self.key)
            finally:
                for worker in workers:
                    if worker.is_alive():
                        worker.terminate()
                    worker.join(10)
                ready.close()
                results.close()

    def test_process_exit_releases_reservation(self):
        context = multiprocessing.get_context("spawn")
        ready = context.Event()
        worker = context.Process(target=_hold_reservation, args=(self.options, self.key, ready))
        try:
            worker.start()
            self.assertTrue(ready.wait(30))
            with self.assertRaises(TrainingBusyError), self.history.execution_lock(self.key, 0.1):
                pass
            worker.terminate()
            worker.join(10)
            with self.history.execution_lock(self.key, 5) as check:
                check()
        finally:
            if worker.is_alive():
                worker.terminate()
            worker.join(10)

    def test_same_run_or_key_cannot_replace_an_immutable_outcome(self):
        first = TrainingRunResponse(runId=self.key, status="SUCCEEDED", datasetVersionId="first")
        self.history.put(first, self.key, "first-fingerprint")
        second = TrainingRunResponse(runId=self.key, status="FAILED", datasetVersionId="second")
        with self.assertRaises(HistoryConflictError):
            self.history.put(second, self.key, "different-fingerprint")
        self.assertEqual(self.history.by_run(self.key).status, "SUCCEEDED")

    def test_lost_owning_session_rejects_ownership_check(self):
        with self.history.execution_lock(self.key, 5) as check:
            with psycopg.connect(**self.options, autocommit=True) as connection:
                rows = connection.execute(
                    """
                    select pid from pg_locks
                    where locktype='advisory' and granted and objsubid=2 and classid=%s and pid<>pg_backend_pid()
                """,
                    (self.history.EXECUTION_NAMESPACE,),
                ).fetchall()
                self.assertEqual(len(rows), 1)
                connection.execute("select pg_terminate_backend(%s)", (rows[0][0],))
            with self.assertRaises(psycopg.Error):
                check()
