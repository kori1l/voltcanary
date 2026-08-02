import hashlib
import json
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from app.config.settings import settings

BundleFilePayload = dict[str, Any] | bytes


class ArtifactStore:
    def __init__(self, root: str | None = None) -> None:
        self.root = Path(root or settings.model_registry_path)
        self.store = settings.ml_artifact_store.lower()
        self.bucket = settings.ml_artifact_bucket
        self._s3_client = None

    def save_json(self, artifact_id: str, payload: dict[str, Any]) -> str:
        if self.store == "s3":
            key = f"{artifact_id}.json"
            self._put_object(key, self._json_bytes(payload), "application/json")
            return f"s3://{self.bucket}/{key}"
        target = self.root / f"{artifact_id}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(payload, sort_keys=True), encoding="utf-8")
        return f"artifact://local/{artifact_id}.json"

    def load_json(self, artifact_uri: str) -> dict[str, Any]:
        if artifact_uri.startswith("s3://"):
            return json.loads(self.load_bytes(artifact_uri).decode("utf-8"))
        artifact_id = artifact_uri.removeprefix("artifact://local/")
        target = self.root / artifact_id
        return json.loads(target.read_text(encoding="utf-8"))

    def load_bytes(self, artifact_uri: str) -> bytes:
        if artifact_uri.startswith("s3://"):
            bucket, key = self._parse_s3_uri(artifact_uri)
            response = self._client().get_object(Bucket=bucket, Key=key)
            return response["Body"].read()
        artifact_id = artifact_uri.removeprefix("artifact://local/")
        target = self.root / artifact_id
        return target.read_bytes()

    def save_model_bundle(
        self,
        artifact_id: str,
        model_payload: dict[str, Any],
        manifest_payload: dict[str, Any],
        supporting_files: dict[str, BundleFilePayload] | None = None,
    ) -> dict[str, Any]:
        if self.store == "s3":
            return self._save_model_bundle_s3(artifact_id, model_payload, manifest_payload, supporting_files)
        bundle_dir = self.root / artifact_id
        bundle_dir.mkdir(parents=True, exist_ok=True)
        files = {"model.json": model_payload, **(supporting_files or {})}
        file_entries = []
        file_bytes = []
        for relative_path, payload in sorted(files.items()):
            target = bundle_dir / relative_path
            target.parent.mkdir(parents=True, exist_ok=True)
            payload_bytes = self._payload_bytes(payload)
            target.write_bytes(payload_bytes)
            file_bytes.append(payload_bytes)
            file_entries.append(
                {
                    "path": relative_path,
                    "contentType": self._content_type(relative_path, payload),
                    "sizeBytes": len(payload_bytes),
                    "sha256": self._sha256_bytes(payload_bytes),
                }
            )
        manifest = {
            **manifest_payload,
            "artifactFormat": "voltcanary-model-bundle-v1",
            "files": file_entries,
        }
        manifest_bytes = self._json_bytes(manifest)
        manifest_path = bundle_dir / "manifest.json"
        manifest_path.write_bytes(manifest_bytes)
        bundle_hash = self._sha256_bytes(b"".join(file_bytes) + manifest_bytes)
        manifest["artifactHash"] = bundle_hash
        manifest["artifactSizeBytes"] = sum(len(item) for item in file_bytes) + len(manifest_bytes)
        manifest_bytes = self._json_bytes(manifest)
        manifest_path.write_bytes(manifest_bytes)
        artifact_file = "model.joblib" if "model.joblib" in files else "model.json"
        return {
            "artifactUri": f"artifact://local/{artifact_id}/{artifact_file}",
            "manifestUri": f"artifact://local/{artifact_id}/manifest.json",
            "artifactHash": bundle_hash,
            "artifactSizeBytes": sum(len(item) for item in file_bytes) + len(manifest_bytes),
            "manifest": manifest,
        }

    def load_model_bundle(self, manifest_uri: str, expected_hash: str | None = None) -> dict[str, Any]:
        manifest = self.load_json(manifest_uri)
        if expected_hash and manifest.get("artifactHash") != expected_hash:
            raise ValueError("model artifact hash verification failed")
        artifact_dir = self._artifact_dir(manifest_uri)
        loaded_files = {}
        for entry in manifest.get("files", []):
            uri = self._child_uri(artifact_dir, entry["path"])
            payload = self.load_json(uri) if entry["path"].endswith(".json") else self.load_bytes(uri)
            serialized = self._json_bytes(payload) if isinstance(payload, dict) else payload
            if entry.get("sha256") != self._sha256_bytes(serialized):
                raise ValueError(f"model artifact file hash verification failed: {entry['path']}")
            loaded_files[entry["path"]] = payload
        return {"manifest": manifest, "files": loaded_files}

    def verify_model_bundle(self, manifest_uri: str, expected_hash: str | None = None) -> bool:
        self.load_model_bundle(manifest_uri, expected_hash)
        return True

    def health(self) -> dict[str, str]:
        if self.store == "s3":
            self._client().head_bucket(Bucket=self.bucket)
            return {"status": "ok", "provider": "s3", "bucket": self.bucket}
        self.root.mkdir(parents=True, exist_ok=True)
        probe = self.root / ".healthcheck"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink(missing_ok=True)
        return {"status": "ok", "provider": "local", "root": str(self.root)}

    def _save_model_bundle_s3(
        self,
        artifact_id: str,
        model_payload: dict[str, Any],
        manifest_payload: dict[str, Any],
        supporting_files: dict[str, BundleFilePayload] | None,
    ) -> dict[str, Any]:
        files = {"model.json": model_payload, **(supporting_files or {})}
        file_entries = []
        file_bytes = []
        for relative_path, payload in sorted(files.items()):
            payload_bytes = self._payload_bytes(payload)
            file_bytes.append(payload_bytes)
            file_entries.append(
                {
                    "path": relative_path,
                    "contentType": self._content_type(relative_path, payload),
                    "sizeBytes": len(payload_bytes),
                    "sha256": self._sha256_bytes(payload_bytes),
                }
            )
        manifest = {
            **manifest_payload,
            "artifactFormat": "voltcanary-model-bundle-v1",
            "files": file_entries,
        }
        manifest_bytes = self._json_bytes(manifest)
        bundle_hash = self._sha256_bytes(b"".join(file_bytes) + manifest_bytes)
        manifest["artifactHash"] = bundle_hash
        manifest["artifactSizeBytes"] = sum(len(item) for item in file_bytes) + len(manifest_bytes)
        manifest_bytes = self._json_bytes(manifest)
        for relative_path, payload in sorted(files.items()):
            self._put_object(f"{artifact_id}/{relative_path}", self._payload_bytes(payload), self._content_type(relative_path, payload))
        self._put_object(f"{artifact_id}/manifest.json", manifest_bytes, "application/json")
        artifact_file = "model.joblib" if "model.joblib" in files else "model.json"
        return {
            "artifactUri": f"s3://{self.bucket}/{artifact_id}/{artifact_file}",
            "manifestUri": f"s3://{self.bucket}/{artifact_id}/manifest.json",
            "artifactHash": bundle_hash,
            "artifactSizeBytes": sum(len(item) for item in file_bytes) + len(manifest_bytes),
            "manifest": manifest,
        }

    def _put_object(self, key: str, payload: bytes, content_type: str) -> None:
        self._client().put_object(Bucket=self.bucket, Key=key, Body=payload, ContentType=content_type)

    def _client(self):
        if self._s3_client is None:
            import boto3
            from botocore.client import Config

            config = Config(s3={"addressing_style": "path" if settings.s3_force_path_style else "auto"})
            self._s3_client = boto3.client(
                "s3",
                endpoint_url=settings.minio_endpoint,
                aws_access_key_id=settings.minio_access_key,
                aws_secret_access_key=settings.minio_secret_key,
                region_name=settings.aws_region,
                config=config,
            )
            self._ensure_bucket()
        return self._s3_client

    def _ensure_bucket(self) -> None:
        try:
            self._s3_client.head_bucket(Bucket=self.bucket)
        except Exception:
            self._s3_client.create_bucket(Bucket=self.bucket)

    def _parse_s3_uri(self, artifact_uri: str) -> tuple[str, str]:
        parsed = urlparse(artifact_uri)
        if parsed.scheme != "s3" or not parsed.netloc or not parsed.path:
            raise ValueError("invalid s3 artifact uri")
        return parsed.netloc, parsed.path.lstrip("/")

    def _artifact_dir(self, manifest_uri: str) -> str:
        if manifest_uri.startswith("s3://"):
            bucket, key = self._parse_s3_uri(manifest_uri)
            key_dir = str(Path(key).parent).replace("\\", "/")
            return f"s3://{bucket}/{key_dir}"
        return Path(manifest_uri.removeprefix("artifact://local/")).parent.as_posix()

    def _child_uri(self, artifact_dir: str, relative_path: str) -> str:
        if artifact_dir.startswith("s3://"):
            return f"{artifact_dir.rstrip('/')}/{relative_path}"
        path = Path(artifact_dir) / relative_path
        return f"artifact://local/{path.as_posix()}"

    def _json_bytes(self, payload: dict[str, Any]) -> bytes:
        return json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")

    def _payload_bytes(self, payload: BundleFilePayload) -> bytes:
        return payload if isinstance(payload, bytes) else self._json_bytes(payload)

    def _content_type(self, relative_path: str, payload: BundleFilePayload) -> str:
        if isinstance(payload, bytes):
            return "application/octet-stream"
        if relative_path.endswith(".json"):
            return "application/json"
        return "application/octet-stream"

    def _sha256_bytes(self, payload: bytes) -> str:
        return "sha256:" + hashlib.sha256(payload).hexdigest()
