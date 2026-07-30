import json
from pathlib import Path
from typing import Any

from app.config.settings import settings


class ArtifactStore:
    def __init__(self, root: str | None = None) -> None:
        self.root = Path(root or settings.model_registry_path)

    def save_json(self, artifact_id: str, payload: dict[str, Any]) -> str:
        target = self.root / f"{artifact_id}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(payload, sort_keys=True), encoding="utf-8")
        return f"artifact://local/{artifact_id}.json"

    def load_json(self, artifact_uri: str) -> dict[str, Any]:
        artifact_id = artifact_uri.removeprefix("artifact://local/")
        target = self.root / artifact_id
        return json.loads(target.read_text(encoding="utf-8"))
