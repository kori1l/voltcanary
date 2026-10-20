import json
import time
from pathlib import Path

try:
    status = json.loads(Path("/tmp/hsl-status.json").read_text())
    healthy = status["connected"] and time.time() - status["heartbeat"] < 90
except (OSError, ValueError, KeyError):
    healthy = False
raise SystemExit(0 if healthy else 1)
