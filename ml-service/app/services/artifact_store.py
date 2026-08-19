from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.config.settings import Settings, settings
from app.services.artifact_bundle import ArtifactBundleVerifier, BundleFilePayload, json_bytes
from app.services.artifact_cleanup import ArtifactCleanupPlanner, ArtifactReferenceSet
from app.services.artifact_providers import ArtifactStoreHealthError, LocalArtifactStoreProvider, S3ArtifactStoreProvider


class ArtifactStore:
    def __init__(
        self,
        root: str | None = None,
        settings_provider: Callable[[], Settings] | None = None,
    ) -> None:
        self.settings_provider = settings_provider or (lambda: settings)
        current_settings = self.settings_provider()
        self.root = Path(root or current_settings.model_registry_path)
        self.store = current_settings.ml_artifact_store.lower()
        self.bucket = current_settings.ml_artifact_bucket
        self._s3_client = None
        self.cleanup_planner = ArtifactCleanupPlanner()

    def save_json(self, artifact_id: str, payload: dict[str, Any]) -> str:
        return self._provider().save_json(artifact_id, payload)

    def load_json(self, artifact_uri: str) -> dict[str, Any]:
        return self._provider_for_uri(artifact_uri).load_json(artifact_uri)

    def load_bytes(self, artifact_uri: str) -> bytes:
        return self._provider_for_uri(artifact_uri).load_bytes(artifact_uri)

    def save_model_bundle(
        self,
        artifact_id: str,
        model_payload: dict[str, Any],
        manifest_payload: dict[str, Any],
        supporting_files: dict[str, BundleFilePayload] | None = None,
    ) -> dict[str, Any]:
        return self._provider().save_model_bundle(
            artifact_id,
            model_payload,
            manifest_payload,
            supporting_files=supporting_files,
        )

    def load_model_bundle(self, manifest_uri: str, expected_hash: str | None = None) -> dict[str, Any]:
        provider = self._provider_for_uri(manifest_uri)
        return ArtifactBundleVerifier(provider).load_model_bundle(manifest_uri, expected_hash)

    def verify_model_bundle(self, manifest_uri: str, expected_hash: str | None = None) -> bool:
        provider = self._provider_for_uri(manifest_uri)
        return ArtifactBundleVerifier(provider).verify_model_bundle(manifest_uri, expected_hash)

    def health(self) -> dict[str, str]:
        provider = self._provider()
        if provider is None:
            raise ArtifactStoreHealthError(
                503,
                {
                    "status": "unhealthy",
                    "provider": self.store,
                    "code": "unsupported_artifact_store",
                    "message": "Configured ML artifact store is not supported.",
                },
            )
        return provider.health()

    def preview_cleanup(self, references: ArtifactReferenceSet | None = None) -> dict[str, Any]:
        provider = self._provider()
        artifacts = provider.list_artifacts()
        candidates, blocked = self.cleanup_planner.preview(artifacts, references)
        return {
            "dryRun": True,
            "provider": self.store,
            "bucket": self.bucket if self.store == "s3" else None,
            "root": str(self.root) if self.store == "local" else None,
            "candidates": candidates,
            "blocked": blocked,
        }

    def delete_artifact_bundle(self, artifact_id: str) -> None:
        self._provider().delete_artifact_bundle(artifact_id)

    def _provider(self):
        current_settings = self.settings_provider()
        if self.store == "local":
            return LocalArtifactStoreProvider(self.root, current_settings)
        if self.store == "s3":
            return S3ArtifactStoreProvider(self.bucket, current_settings, self._client)
        raise ArtifactStoreHealthError(
            503,
            {
                "status": "unhealthy",
                "provider": self.store,
                "code": "unsupported_artifact_store",
                "message": "Configured ML artifact store is not supported.",
            },
        )

    def _provider_for_uri(self, artifact_uri: str):
        if artifact_uri.startswith("s3://"):
            return S3ArtifactStoreProvider(self.bucket, self.settings_provider(), self._client)
        return LocalArtifactStoreProvider(self.root, self.settings_provider())

    def _client(self, ensure_bucket: bool = True):
        if self._s3_client is None:
            import boto3
            from botocore.client import Config

            current_settings = self.settings_provider()
            config = Config(s3={"addressing_style": "path" if current_settings.s3_force_path_style else "auto"})
            self._s3_client = boto3.client(
                "s3",
                endpoint_url=current_settings.minio_endpoint,
                aws_access_key_id=current_settings.minio_access_key,
                aws_secret_access_key=current_settings.minio_secret_key,
                region_name=current_settings.aws_region,
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

    def _json_bytes(self, payload: dict[str, Any]) -> bytes:
        return json_bytes(payload)
