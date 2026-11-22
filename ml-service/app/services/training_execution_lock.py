"""Process-owned execution locks; the OS releases ownership when a worker exits."""

import hashlib
import os
import time
from contextlib import contextmanager
from pathlib import Path


class TrainingBusyError(Exception):
    pass


class FileTrainingLocks:
    # A fixed number of stripes bounds lock-file storage for arbitrary request keys.
    STRIPES = 256

    def __init__(self, directory: Path) -> None:
        self.directory = directory

    @contextmanager
    def acquire(self, key: str, timeout: float):
        self.directory.mkdir(parents=True, exist_ok=True)
        stripe = int.from_bytes(hashlib.sha256(key.encode()).digest()[:4], "big") % self.STRIPES
        path = self.directory / f"{stripe:03}.lock"
        deadline = time.monotonic() + timeout
        with path.open("a+b") as handle:
            if path.stat().st_size == 0:
                handle.write(b"\0")
                handle.flush()
            while True:
                try:
                    handle.seek(0)
                    if os.name == "nt":
                        import msvcrt

                        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                    else:
                        import fcntl

                        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except (BlockingIOError, PermissionError):
                    if time.monotonic() >= deadline:
                        raise TrainingBusyError(
                            "Training execution is already owned by another worker"
                        ) from None
                    time.sleep(0.05)
            try:
                yield
            finally:
                handle.seek(0)
                if os.name == "nt":
                    import msvcrt

                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl

                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
