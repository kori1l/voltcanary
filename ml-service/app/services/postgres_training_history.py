"""Shared immutable outcomes and process-owned PostgreSQL training reservations."""

import hashlib
import time
from contextlib import contextmanager
from threading import Lock

from app.schemas.training import TrainingRunResponse
from app.services.training_execution_lock import TrainingBusyError
from app.services.training_history import HistoryConflictError


class PostgresTrainingHistory:
    SCHEMA_LOCK = 723_012_001
    HISTORY_LOCK = 723_012_002
    EXECUTION_NAMESPACE = 723_012_003

    def __init__(self, connection_options: dict, capacity: int, retention_seconds: float):
        self.options = connection_options
        self.capacity = capacity
        self.retention = retention_seconds
        self._initialization = Lock()
        self._initialized = False

    @contextmanager
    def _database(self):
        import psycopg

        with psycopg.connect(**self.options, autocommit=True, connect_timeout=5) as connection:
            connection.execute("set statement_timeout = '10s'")
            connection.execute("set lock_timeout = '5s'")
            with self._initialization:
                if not self._initialized:
                    with connection.transaction():
                        connection.execute("select pg_advisory_xact_lock(%s)", (self.SCHEMA_LOCK,))
                        connection.execute("""
                            create table if not exists ml_training_history (
                                run_id text primary key,
                                idempotency_key text unique,
                                fingerprint text not null,
                                payload text not null,
                                recorded_at double precision not null
                            )
                        """)
                        connection.execute("""
                            create index if not exists ml_training_history_expiry
                            on ml_training_history(recorded_at)
                        """)
                    self._initialized = True
            yield connection

    @contextmanager
    def execution_lock(self, key: str, timeout: float):
        lock_id = int.from_bytes(hashlib.sha256(key.encode()).digest()[:4], "big", signed=True)
        deadline = time.monotonic() + timeout
        with self._database() as connection:
            while not connection.execute(
                "select pg_try_advisory_lock(%s, %s)", (self.EXECUTION_NAMESPACE, lock_id)
            ).fetchone()[0]:
                if time.monotonic() >= deadline:
                    raise TrainingBusyError("Training execution is already owned by another worker")
                time.sleep(0.05)
            # Closing the owning session releases the lock, including after failure or process exit.
            yield lambda: connection.execute("select 1")

    def by_key(self, key: str, fingerprint: str) -> TrainingRunResponse | None:
        with self._database() as connection:
            row = connection.execute(
                """
                select fingerprint,payload from ml_training_history
                where idempotency_key=%s and recorded_at > extract(epoch from clock_timestamp()) - %s
                """,
                (key, self.retention),
            ).fetchone()
            return self._result(row, fingerprint)

    def by_run(self, run_id: str) -> TrainingRunResponse | None:
        with self._database() as connection:
            row = connection.execute(
                """
                select payload from ml_training_history
                where run_id=%s and recorded_at > extract(epoch from clock_timestamp()) - %s
                """,
                (run_id, self.retention),
            ).fetchone()
            return TrainingRunResponse.model_validate_json(row[0]) if row else None

    def put(
        self, run: TrainingRunResponse, key: str | None, fingerprint: str
    ) -> TrainingRunResponse:
        with self._database() as connection, connection.transaction():
            connection.execute("select pg_advisory_xact_lock(%s)", (self.HISTORY_LOCK,))
            connection.execute(
                """
                delete from ml_training_history
                where recorded_at <= extract(epoch from clock_timestamp()) - %s
                """,
                (self.retention,),
            )
            if key is not None:
                row = connection.execute(
                    "select fingerprint,payload from ml_training_history where idempotency_key=%s",
                    (key,),
                ).fetchone()
                if row:
                    return self._result(row, fingerprint)
            row = connection.execute(
                "select fingerprint,payload from ml_training_history where run_id=%s", (run.run_id,)
            ).fetchone()
            if row:
                return self._result(row, fingerprint)
            connection.execute(
                """
                insert into ml_training_history(run_id,idempotency_key,fingerprint,payload,recorded_at)
                values(%s,%s,%s,%s,extract(epoch from clock_timestamp()))
                """,
                (run.run_id, key, fingerprint, run.model_dump_json(by_alias=True)),
            )
            connection.execute(
                """
                delete from ml_training_history where run_id not in (
                    select run_id from ml_training_history order by recorded_at desc, run_id desc limit %s
                )
                """,
                (self.capacity,),
            )
            return run

    @staticmethod
    def _result(row, fingerprint: str) -> TrainingRunResponse | None:
        if row is None:
            return None
        if row[0] != fingerprint:
            raise HistoryConflictError("Training identity conflicts with retained request metadata")
        return TrainingRunResponse.model_validate_json(row[1])
