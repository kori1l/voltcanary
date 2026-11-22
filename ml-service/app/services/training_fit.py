import json
import multiprocessing
from pathlib import Path
import tempfile
import time
from typing import Any

import joblib

from app.services.training_coordinator import TrainingTimeoutError


def _fit_child(directory: str, model: Any, arguments: tuple) -> None:
    root = Path(directory)
    try:
        model.fit(*arguments)
        joblib.dump(model, root / "fitted.joblib")
        (root / "status.json").write_text(json.dumps({"success": True}), encoding="utf-8")
    except BaseException as failure:
        (root / "status.json").write_text(json.dumps({"success": False, "kind": type(failure).__name__}), encoding="utf-8")


def fit_with_deadline(model: Any, arguments: tuple, timeout: float) -> Any:
    """Run CPU fitting in a killable process; returning/closing HTTP is not cancellation."""
    if timeout <= 0:
        raise TrainingTimeoutError()
    deadline = time.monotonic() + timeout
    with tempfile.TemporaryDirectory(prefix="voltcanary-fit-") as directory:
        process = multiprocessing.get_context("spawn").Process(target=_fit_child, args=(directory, model, arguments), daemon=True)
        try:
            process.start()
            process.join(max(0, deadline - time.monotonic()))
            if process.is_alive():
                raise TrainingTimeoutError()
            status_path = Path(directory) / "status.json"
            if process.exitcode != 0 or not status_path.exists():
                raise RuntimeError("Model fitting process failed")
            status = json.loads(status_path.read_text(encoding="utf-8"))
            if not status["success"]:
                if status["kind"] == "ValueError":
                    raise ValueError("Model fitting rejected training samples")
                raise RuntimeError("Model fitting failed")
            return joblib.load(Path(directory) / "fitted.joblib")
        finally:
            if process.pid is not None:
                if process.is_alive():
                    process.terminate()
                    process.join(2)
                if process.is_alive():
                    process.kill()
                    process.join()
                process.close()
