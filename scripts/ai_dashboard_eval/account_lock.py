"""Prevent planning inventory checks and execution/cleanup jobs sharing an account."""
import json
import os
from pathlib import Path
import uuid

from .api import ApiError, ROOT


class AccountLease:
    def __init__(self, account_id, job, directory=None):
        key = str(uuid.UUID(account_id))
        self.directory = Path(directory or ROOT / "artifacts/evaluation-account-locks")
        self.path = self.directory / (key + ".lock")
        self.record = {"accountId": key, "pid": os.getpid(), "job": job, "nonce": uuid.uuid4().hex}

    def __enter__(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        try:
            with self.path.open("x", encoding="utf-8") as stream:
                json.dump(self.record, stream)
        except FileExistsError:
            raise ApiError(0, "EVALUATION_ACCOUNT_BUSY") from None
        return self

    def __exit__(self, *_):
        if self.path.exists():
            saved = json.loads(self.path.read_text(encoding="utf-8"))
            if saved.get("nonce") == self.record["nonce"]:
                self.path.unlink()
