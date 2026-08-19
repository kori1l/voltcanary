import hashlib
import json
from dataclasses import dataclass
from typing import Any, Protocol

BundleFilePayload = dict[str, Any] | bytes


class ArtifactBundleProvider(Protocol):
    def load_json(self, artifact_uri: str) -> dict[str, Any]:
        ...

    def load_bytes(self, artifact_uri: str) -> bytes:
        ...

    def artifact_dir(self, manifest_uri: str) -> str:
        ...

    def child_uri(self, artifact_dir: str, relative_path: str) -> str:
        ...


@dataclass(frozen=True)
class PreparedModelBundle:
    files: dict[str, bytes]
    file_entries: list[dict[str, Any]]
    manifest: dict[str, Any]
    manifest_bytes: bytes
    artifact_hash: str
    artifact_size_bytes: int
    artifact_file: str


def json_bytes(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256_bytes(payload: bytes) -> str:
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def payload_bytes(payload: BundleFilePayload) -> bytes:
    return payload if isinstance(payload, bytes) else json_bytes(payload)


def content_type(relative_path: str, payload: BundleFilePayload) -> str:
    if isinstance(payload, bytes):
        return "application/octet-stream"
    if relative_path.endswith(".json"):
        return "application/json"
    return "application/octet-stream"


class ModelBundleBuilder:
    def prepare(
        self,
        model_payload: dict[str, Any],
        manifest_payload: dict[str, Any],
        supporting_files: dict[str, BundleFilePayload] | None = None,
    ) -> PreparedModelBundle:
        source_files = {"model.json": model_payload, **(supporting_files or {})}
        files: dict[str, bytes] = {}
        file_entries: list[dict[str, Any]] = []
        file_bytes: list[bytes] = []
        for relative_path, payload in sorted(source_files.items()):
            serialized = payload_bytes(payload)
            files[relative_path] = serialized
            file_bytes.append(serialized)
            file_entries.append(
                {
                    "path": relative_path,
                    "contentType": content_type(relative_path, payload),
                    "sizeBytes": len(serialized),
                    "sha256": sha256_bytes(serialized),
                }
            )

        manifest = {
            **manifest_payload,
            "artifactFormat": "voltcanary-model-bundle-v1",
            "files": file_entries,
        }
        initial_manifest_bytes = json_bytes(manifest)
        bundle_hash = sha256_bytes(b"".join(file_bytes) + initial_manifest_bytes)
        artifact_size_bytes = sum(len(item) for item in file_bytes) + len(initial_manifest_bytes)
        manifest = {
            **manifest,
            "artifactHash": bundle_hash,
            "artifactSizeBytes": artifact_size_bytes,
        }
        manifest_bytes = json_bytes(manifest)
        artifact_size_bytes = sum(len(item) for item in file_bytes) + len(manifest_bytes)
        artifact_file = "model.joblib" if "model.joblib" in source_files else "model.json"
        return PreparedModelBundle(
            files=files,
            file_entries=file_entries,
            manifest=manifest,
            manifest_bytes=manifest_bytes,
            artifact_hash=bundle_hash,
            artifact_size_bytes=artifact_size_bytes,
            artifact_file=artifact_file,
        )


class ArtifactBundleVerifier:
    def __init__(self, provider: ArtifactBundleProvider) -> None:
        self.provider = provider

    def load_model_bundle(self, manifest_uri: str, expected_hash: str | None = None) -> dict[str, Any]:
        manifest = self.provider.load_json(manifest_uri)
        if expected_hash and manifest.get("artifactHash") != expected_hash:
            raise ValueError("model artifact hash verification failed")
        artifact_dir = self.provider.artifact_dir(manifest_uri)
        loaded_files: dict[str, Any] = {}
        for entry in manifest.get("files", []):
            relative_path = entry["path"]
            uri = self.provider.child_uri(artifact_dir, relative_path)
            payload = self.provider.load_json(uri) if relative_path.endswith(".json") else self.provider.load_bytes(uri)
            serialized = json_bytes(payload) if isinstance(payload, dict) else payload
            if entry.get("sha256") != sha256_bytes(serialized):
                raise ValueError(f"model artifact file hash verification failed: {relative_path}")
            loaded_files[relative_path] = payload
        return {"manifest": manifest, "files": loaded_files}

    def verify_model_bundle(self, manifest_uri: str, expected_hash: str | None = None) -> bool:
        self.load_model_bundle(manifest_uri, expected_hash)
        return True
