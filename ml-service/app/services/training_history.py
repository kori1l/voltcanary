from contextlib import contextmanager
from pathlib import Path
import sqlite3
from threading import RLock
import time

from app.schemas.training import TrainingRunResponse


class HistoryConflictError(Exception):
    pass


class TrainingHistory:
    """Bounded retained outcomes; file-backed instances survive service restarts."""

    def __init__(self, path: Path | None = None, capacity: int = 1000, retention_seconds: float = 604800):
        if capacity < 1 or retention_seconds <= 0:
            raise ValueError("Training history capacity and retention must be positive")
        self.path, self.capacity, self.retention = path, capacity, retention_seconds
        self._lock = RLock()
        self._memory = sqlite3.connect(":memory:", check_same_thread=False) if path is None else None
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)

    @contextmanager
    def _database(self):
        with self._lock:
            connection = self._memory if self._memory is not None else sqlite3.connect(str(self.path), timeout=10)
            try:
                connection.execute("create table if not exists training_history (run_id text primary key, idempotency_key text unique, fingerprint text not null, payload text not null, recorded_at real not null)")
                connection.commit()
                yield connection
            finally:
                if self._memory is None:
                    connection.close()

    def by_key(self, key: str, fingerprint: str) -> TrainingRunResponse | None:
        with self._database() as connection:
            row = connection.execute("select fingerprint,payload from training_history where idempotency_key=? and recorded_at>?",
                                     (key, time.time() - self.retention)).fetchone()
            if row is None:
                return None
            if row[0] != fingerprint:
                raise HistoryConflictError("Training idempotency key conflicts with retained request metadata")
            return TrainingRunResponse.model_validate_json(row[1])

    def by_run(self, run_id: str) -> TrainingRunResponse | None:
        with self._database() as connection:
            row = connection.execute("select payload from training_history where run_id=? and recorded_at>?",
                                     (run_id, time.time() - self.retention)).fetchone()
            return TrainingRunResponse.model_validate_json(row[0]) if row else None

    def put(self, run: TrainingRunResponse, key: str | None, fingerprint: str) -> TrainingRunResponse:
        with self._database() as connection:
            try:
                connection.execute("begin immediate")
                now = time.time()
                connection.execute("delete from training_history where recorded_at<=?", (now - self.retention,))
                existing = connection.execute("select fingerprint,payload from training_history where idempotency_key=?", (key,)).fetchone() if key else None
                if existing:
                    if existing[0] != fingerprint:
                        raise HistoryConflictError("Training idempotency key conflicts with retained request metadata")
                    connection.commit()
                    return TrainingRunResponse.model_validate_json(existing[1])
                connection.execute("insert into training_history(run_id,idempotency_key,fingerprint,payload,recorded_at) values(?,?,?,?,?) on conflict(run_id) do update set payload=excluded.payload,recorded_at=excluded.recorded_at",
                                   (run.run_id, key, fingerprint, run.model_dump_json(by_alias=True), now))
                connection.execute("delete from training_history where run_id not in (select run_id from training_history order by recorded_at desc,run_id desc limit ?)", (self.capacity,))
                connection.commit()
                return run
            except Exception:
                connection.rollback()
                raise
