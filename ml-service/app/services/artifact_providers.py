from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse

from app.config.settings import Settings
from app.services.artifact_bundle import BundleFilePayload, ModelBundleBuilder, json_bytes


class ArtifactStoreHealthError(Exception):
    def __init__(self, status_code: int, payload: dict[str, Any]) -> None:
        super().__init__(payload.get("message", "artifact store is unhealthy"))
        self.status_code = status_code
        self.payload = payload


class LocalArtifactStoreProvider:
    def __init__(self, root: Path, current_settings: Settings) -> None:
        self.root = root
        self.current_settings = current_settings
        self.bundle_builder = ModelBundleBuilder()

    def save_json(self, artifact_id: str, payload: dict[str, Any]) -> str:
        target = self.root / f"{artifact_id}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json_bytes(payload).decode("utf-8"), encoding="utf-8")
        return f"artifact://local/{artifact_id}.json"

    def load_json(self, artifact_uri: str) -> dict[str, Any]:
        import json

        return json.loads(self._local_path(artifact_uri).read_text(encoding="utf-8"))

    def load_bytes(self, artifact_uri: str) -> bytes:
        return self._local_path(artifact_uri).read_bytes()

    def save_model_bundle(
        self,
        artifact_id: str,
        model_payload: dict[str, Any],
        manifest_payload: dict[str, Any],
        supporting_files: dict[str, BundleFilePayload] | None = None,
    ) -> dict[str, Any]:
        bundle = self.bundle_builder.prepare(model_payload, manifest_payload, supporting_files)
        bundle_dir = self.root / artifact_id
        bundle_dir.mkdir(parents=True, exist_ok=True)
        for relative_path, payload in bundle.files.items():
            target = bundle_dir / relative_path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)
        (bundle_dir / "manifest.json").write_bytes(bundle.manifest_bytes)
        return {
            "artifactUri": f"artifact://local/{artifact_id}/{bundle.artifact_file}",
            "manifestUri": f"artifact://local/{artifact_id}/manifest.json",
            "artifactHash": bundle.artifact_hash,
            "artifactSizeBytes": bundle.artifact_size_bytes,
            "manifest": bundle.manifest,
        }

    def health(self) -> dict[str, str]:
        if self.current_settings.is_production_like_runtime:
            raise ArtifactStoreHealthError(
                503,
                {
                    "status": "unhealthy",
                    "provider": "local",
                    "runtimeProfile": self.current_settings.ml_runtime_profile,
                    "code": "local_artifact_store_forbidden",
                    "message": "Local artifact storage is not allowed for production-like ML runtime profiles.",
                },
            )
        self.root.mkdir(parents=True, exist_ok=True)
        probe = self.root / ".healthcheck"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink(missing_ok=True)
        return {"status": "ok", "provider": "local", "root": str(self.root)}

    def list_artifacts(self) -> list[dict[str, Any]]:
        if not self.root.exists():
            return []
        artifacts: list[dict[str, Any]] = []
        for item in sorted(self.root.iterdir(), key=lambda path: path.name):
            if item.name.startswith("."):
                continue
            if item.is_dir():
                files = [path for path in item.rglob("*") if path.is_file()]
                child_uris = [f"artifact://local/{path.relative_to(self.root).as_posix()}" for path in files]
                manifest_uri = f"artifact://local/{item.name}/manifest.json"
                artifacts.append(
                    {
                        "artifactId": item.name,
                        "artifactUri": manifest_uri,
                        "kind": "model_bundle" if (item / "manifest.json").exists() else "directory",
                        "sizeBytes": sum(path.stat().st_size for path in files),
                        "fileCount": len(files),
                        "childUris": child_uris,
                    }
                )
            elif item.is_file():
                artifact_uri = f"artifact://local/{item.name}"
                artifacts.append(
                    {
                        "artifactId": item.stem,
                        "artifactUri": artifact_uri,
                        "kind": "feature_snapshot" if item.name.startswith("features-") else "file",
                        "sizeBytes": item.stat().st_size,
                        "fileCount": 1,
                        "childUris": [artifact_uri],
                    }
                )
        return artifacts

    def delete_artifact_bundle(self, artifact_id: str) -> None:
        target = self.root / artifact_id
        if not target.exists():
            return
        for path in sorted(target.rglob("*"), key=lambda item: len(item.parts), reverse=True):
            if path.is_file():
                path.unlink(missing_ok=True)
            elif path.is_dir():
                path.rmdir()
        target.rmdir()

    def artifact_dir(self, manifest_uri: str) -> str:
        return Path(manifest_uri.removeprefix("artifact://local/")).parent.as_posix()

    def child_uri(self, artifact_dir: str, relative_path: str) -> str:
        path = Path(artifact_dir) / relative_path
        return f"artifact://local/{path.as_posix()}"

    def _local_path(self, artifact_uri: str) -> Path:
        artifact_id = artifact_uri.removeprefix("artifact://local/")
        return self.root / artifact_id


class S3ArtifactStoreProvider:
    def __init__(
        self,
        bucket: str,
        current_settings: Settings,
        client_provider: Callable[[bool], Any],
    ) -> None:
        self.bucket = bucket
        self.current_settings = current_settings
        self.client_provider = client_provider
        self.bundle_builder = ModelBundleBuilder()

    def save_json(self, artifact_id: str, payload: dict[str, Any]) -> str:
        key = f"{artifact_id}.json"
        self._put_object(key, json_bytes(payload), "application/json")
        return f"s3://{self.bucket}/{key}"

    def load_json(self, artifact_uri: str) -> dict[str, Any]:
        import json

        return json.loads(self.load_bytes(artifact_uri).decode("utf-8"))

    def load_bytes(self, artifact_uri: str) -> bytes:
        bucket, key = self.parse_s3_uri(artifact_uri)
        response = self.client_provider(True).get_object(Bucket=bucket, Key=key)
        return response["Body"].read()

    def save_model_bundle(
        self,
        artifact_id: str,
        model_payload: dict[str, Any],
        manifest_payload: dict[str, Any],
        supporting_files: dict[str, BundleFilePayload] | None = None,
    ) -> dict[str, Any]:
        bundle = self.bundle_builder.prepare(model_payload, manifest_payload, supporting_files)
        for relative_path, payload in bundle.files.items():
            self._put_object(f"{artifact_id}/{relative_path}", payload, self._content_type(relative_path))
        self._put_object(f"{artifact_id}/manifest.json", bundle.manifest_bytes, "application/json")
        return {
            "artifactUri": f"s3://{self.bucket}/{artifact_id}/{bundle.artifact_file}",
            "manifestUri": f"s3://{self.bucket}/{artifact_id}/manifest.json",
            "artifactHash": bundle.artifact_hash,
            "artifactSizeBytes": bundle.artifact_size_bytes,
            "manifest": bundle.manifest,
        }

    def health(self) -> dict[str, str]:
        if self.current_settings.minio_endpoint and (
            not self.current_settings.minio_access_key or not self.current_settings.minio_secret_key
        ):
            raise ArtifactStoreHealthError(
                503,
                {
                    "status": "unhealthy",
                    "provider": "s3",
                    "bucket": self.bucket,
                    "code": "s3_credentials_missing",
                    "message": "S3 artifact credentials are not configured.",
                },
            )
        try:
            self.client_provider(False).head_bucket(Bucket=self.bucket)
        except Exception:
            raise ArtifactStoreHealthError(
                503,
                {
                    "status": "unhealthy",
                    "provider": "s3",
                    "bucket": self.bucket,
                    "code": "artifact_store_unavailable",
                    "message": "S3 artifact bucket is unavailable or credentials are invalid.",
                },
            ) from None
        return {"status": "ok", "provider": "s3", "bucket": self.bucket}

    def list_artifacts(self) -> list[dict[str, Any]]:
        objects = self._list_objects()
        grouped: dict[str, list[dict[str, Any]]] = {}
        root_objects: list[dict[str, Any]] = []
        for item in objects:
            key = item["Key"]
            if "/" in key:
                artifact_id = key.split("/", 1)[0]
                grouped.setdefault(artifact_id, []).append(item)
            else:
                root_objects.append(item)

        artifacts = [
            {
                "artifactId": artifact_id,
                "artifactUri": f"s3://{self.bucket}/{artifact_id}/manifest.json",
                "kind": "model_bundle"
                if any(item["Key"] == f"{artifact_id}/manifest.json" for item in children)
                else "prefix",
                "sizeBytes": sum(int(item.get("Size", 0)) for item in children),
                "fileCount": len(children),
                "childUris": [f"s3://{self.bucket}/{item['Key']}" for item in children],
            }
            for artifact_id, children in sorted(grouped.items())
        ]
        artifacts.extend(
            {
                "artifactId": Path(item["Key"]).stem,
                "artifactUri": f"s3://{self.bucket}/{item['Key']}",
                "kind": "feature_snapshot" if Path(item["Key"]).name.startswith("features-") else "file",
                "sizeBytes": int(item.get("Size", 0)),
                "fileCount": 1,
                "childUris": [f"s3://{self.bucket}/{item['Key']}"],
            }
            for item in sorted(root_objects, key=lambda entry: entry["Key"])
        )
        return artifacts

    def delete_artifact_bundle(self, artifact_id: str) -> None:
        client = self.client_provider(False)
        for item in self._list_objects():
            key = item["Key"]
            if key.startswith(f"{artifact_id}/"):
                client.delete_object(Bucket=self.bucket, Key=key)

    def artifact_dir(self, manifest_uri: str) -> str:
        bucket, key = self.parse_s3_uri(manifest_uri)
        key_dir = str(Path(key).parent).replace("\\", "/")
        return f"s3://{bucket}/{key_dir}"

    def child_uri(self, artifact_dir: str, relative_path: str) -> str:
        return f"{artifact_dir.rstrip('/')}/{relative_path}"

    def parse_s3_uri(self, artifact_uri: str) -> tuple[str, str]:
        parsed = urlparse(artifact_uri)
        if parsed.scheme != "s3" or not parsed.netloc or not parsed.path:
            raise ValueError("invalid s3 artifact uri")
        return parsed.netloc, parsed.path.lstrip("/")

    def _put_object(self, key: str, payload: bytes, content_type: str) -> None:
        self.client_provider(True).put_object(Bucket=self.bucket, Key=key, Body=payload, ContentType=content_type)

    def _list_objects(self) -> list[dict[str, Any]]:
        client = self.client_provider(False)
        token = None
        objects: list[dict[str, Any]] = []
        while True:
            kwargs = {"Bucket": self.bucket}
            if token:
                kwargs["ContinuationToken"] = token
            response = client.list_objects_v2(**kwargs)
            objects.extend(response.get("Contents", []))
            if not response.get("IsTruncated"):
                return objects
            token = response.get("NextContinuationToken")

    def _content_type(self, relative_path: str) -> str:
        if relative_path.endswith(".json"):
            return "application/json"
        return "application/octet-stream"
