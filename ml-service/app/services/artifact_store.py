import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urlparse

from app.config.settings import settings

BundleFilePayload = dict[str, Any] | bytes


class ArtifactStoreHealthError(Exception):
    def __init__(self, status_code: int, payload: dict[str, Any]) -> None:
        super().__init__(payload.get("message", "artifact store is unhealthy"))
        self.status_code = status_code
        self.payload = payload


@dataclass(frozen=True)
class ArtifactReferenceSet:
    training_runs: set[str] = field(default_factory=set)
    model_versions: set[str] = field(default_factory=set)
    deployment_policies: set[str] = field(default_factory=set)
    prediction_traces: set[str] = field(default_factory=set)
    audit_records: set[str] = field(default_factory=set)

    @classmethod
    def from_sources(
        cls,
        training_runs: Iterable[str] = (),
        model_versions: Iterable[str] = (),
        deployment_policies: Iterable[str] = (),
        prediction_traces: Iterable[str] = (),
        audit_records: Iterable[str] = (),
    ) -> "ArtifactReferenceSet":
        return cls(
            training_runs=set(training_runs),
            model_versions=set(model_versions),
            deployment_policies=set(deployment_policies),
            prediction_traces=set(prediction_traces),
            audit_records=set(audit_records),
        )

    def reference_types_for(self, artifact_uri: str, child_uris: Iterable[str] = ()) -> list[str]:
        keys = {artifact_uri, *child_uris}
        reference_sources = {
            "training_runs": self.training_runs,
            "model_versions": self.model_versions,
            "deployment_policies": self.deployment_policies,
            "prediction_traces": self.prediction_traces,
            "audit_records": self.audit_records,
        }
        return [
            reference_type
            for reference_type, references in reference_sources.items()
            if any(self._matches(reference, key) for reference in references for key in keys)
        ]

    def _matches(self, reference: str, artifact_uri: str) -> bool:
        if not reference:
            return False
        if reference == artifact_uri:
            return True
        return artifact_uri.endswith("/manifest.json") and reference.startswith(self._artifact_prefix(artifact_uri))

    def _artifact_prefix(self, artifact_uri: str) -> str:
        return artifact_uri.rsplit("/", 1)[0].rstrip("/") + "/"


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
        if settings.is_production_like_runtime and self.store == "local":
            raise ArtifactStoreHealthError(
                503,
                {
                    "status": "unhealthy",
                    "provider": "local",
                    "runtimeProfile": settings.ml_runtime_profile,
                    "code": "local_artifact_store_forbidden",
                    "message": "Local artifact storage is not allowed for production-like ML runtime profiles.",
                },
            )
        if self.store == "s3":
            if settings.minio_endpoint and (not settings.minio_access_key or not settings.minio_secret_key):
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
                self._client(ensure_bucket=False).head_bucket(Bucket=self.bucket)
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
        if self.store != "local":
            raise ArtifactStoreHealthError(
                503,
                {
                    "status": "unhealthy",
                    "provider": self.store,
                    "code": "unsupported_artifact_store",
                    "message": "Configured ML artifact store is not supported.",
                },
            )
        self.root.mkdir(parents=True, exist_ok=True)
        probe = self.root / ".healthcheck"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink(missing_ok=True)
        return {"status": "ok", "provider": "local", "root": str(self.root)}

    def preview_cleanup(self, references: ArtifactReferenceSet | None = None) -> dict[str, Any]:
        references = references or ArtifactReferenceSet()
        artifacts = self._list_artifacts()
        blocked = []
        candidates = []
        for artifact in artifacts:
            reference_types = references.reference_types_for(
                artifact["artifactUri"],
                artifact.get("childUris", []),
            )
            public_artifact = {key: value for key, value in artifact.items() if key != "childUris"}
            if reference_types:
                blocked.append(
                    {
                        **public_artifact,
                        "referenceTypes": reference_types,
                        "blocked": True,
                    }
                )
            else:
                candidates.append({**public_artifact, "blocked": False})
        return {
            "dryRun": True,
            "provider": self.store,
            "bucket": self.bucket if self.store == "s3" else None,
            "root": str(self.root) if self.store == "local" else None,
            "candidates": candidates,
            "blocked": blocked,
        }

    def delete_artifact_bundle(self, artifact_id: str) -> None:
        if self.store == "s3":
            self._delete_s3_prefix(f"{artifact_id}/")
            return
        target = self.root / artifact_id
        if not target.exists():
            return
        for path in sorted(target.rglob("*"), key=lambda item: len(item.parts), reverse=True):
            if path.is_file():
                path.unlink(missing_ok=True)
            elif path.is_dir():
                path.rmdir()
        target.rmdir()

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

    def _client(self, ensure_bucket: bool = True):
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
        if ensure_bucket:
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

    def _list_artifacts(self) -> list[dict[str, Any]]:
        if self.store == "s3":
            return self._list_s3_artifacts()
        return self._list_local_artifacts()

    def _list_local_artifacts(self) -> list[dict[str, Any]]:
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

    def _list_s3_artifacts(self) -> list[dict[str, Any]]:
        objects = self._list_s3_objects()
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

    def _list_s3_objects(self) -> list[dict[str, Any]]:
        client = self._client(ensure_bucket=False)
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

    def _delete_s3_prefix(self, prefix: str) -> None:
        client = self._client(ensure_bucket=False)
        for item in self._list_s3_objects():
            key = item["Key"]
            if key.startswith(prefix):
                client.delete_object(Bucket=self.bucket, Key=key)
