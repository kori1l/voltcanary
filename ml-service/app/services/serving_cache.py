import logging
import time
from collections import OrderedDict
from dataclasses import dataclass
from typing import Any

from app.config.settings import settings
from app.models.failure_prediction_model import FailurePredictionModel
from app.models.tabular_anomaly_model import TabularAnomalyModel
from app.services.artifact_store import ArtifactStore


ARTIFACT_CONTRACT_VERSION = "voltcanary-model-bundle-v1"

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ServingCacheKey:
    model_version_id: str
    manifest_uri: str
    artifact_hash: str
    artifact_contract_version: str = ARTIFACT_CONTRACT_VERSION


@dataclass
class ServingRuntimeArtifact:
    key: ServingCacheKey
    manifest: dict[str, Any]
    artifact: dict[str, Any]
    pipeline: Any
    model_type: str
    feature_columns: list[str]
    feature_schema_hash: str
    loaded_at: float


class ServingArtifactCache:
    def __init__(
        self,
        artifact_store: ArtifactStore,
        *,
        max_entries: int | None = None,
        ttl_seconds: float | None = None,
    ) -> None:
        self.artifact_store = artifact_store
        self.max_entries = max(1, int(max_entries if max_entries is not None else settings.ml_serving_cache_max_entries))
        self.ttl_seconds = max(0.0, float(ttl_seconds if ttl_seconds is not None else settings.ml_serving_cache_ttl_seconds))
        self._entries: OrderedDict[ServingCacheKey, ServingRuntimeArtifact] = OrderedDict()
        self.hits = 0
        self.misses = 0
        self.load_failures = 0
        self.evictions = 0

    def get(self, model: dict[str, Any]) -> ServingRuntimeArtifact:
        key = self._key(model)
        now = time.monotonic()
        cached = self._entries.get(key)
        if cached is not None and not self._expired(cached, now):
            self._entries.move_to_end(key)
            self.hits += 1
            self._log("hit", key, cache_size=len(self._entries))
            return cached
        if cached is not None:
            self._entries.pop(key, None)
            self.evictions += 1
            self._log("eviction", key, reason="ttl", cache_size=len(self._entries))

        self.misses += 1
        self._log("miss", key, cache_size=len(self._entries))
        started = time.monotonic()
        try:
            runtime = self._load(key, model, now)
        except Exception:
            self.load_failures += 1
            self._log("load_failure", key, load_duration_ms=round((time.monotonic() - started) * 1000, 3))
            raise

        self._entries[key] = runtime
        self._entries.move_to_end(key)
        self._evict_capacity()
        self._log(
            "load_success",
            key,
            load_duration_ms=round((time.monotonic() - started) * 1000, 3),
            cache_size=len(self._entries),
        )
        return runtime

    def clear(self) -> None:
        self._entries.clear()

    def stats(self) -> dict[str, int]:
        return {
            "hits": self.hits,
            "misses": self.misses,
            "loadFailures": self.load_failures,
            "evictions": self.evictions,
            "cacheSize": len(self._entries),
        }

    def _load(self, key: ServingCacheKey, model: dict[str, Any], loaded_at: float) -> ServingRuntimeArtifact:
        bundle = self.artifact_store.load_model_bundle(key.manifest_uri, key.artifact_hash or None)
        manifest = bundle["manifest"]
        manifest_model_version_id = str(manifest.get("modelVersionId") or "")
        if key.model_version_id and manifest_model_version_id != key.model_version_id:
            raise ValueError("Selected model manifest does not match the requested modelVersionId.")
        artifact = bundle["files"]["model.json"]
        pipeline_payload = bundle["files"].get("model.joblib")
        if not isinstance(pipeline_payload, bytes):
            raise ValueError("Selected model bundle does not contain a serialized runtime model.")
        model_type = self._model_type(model, artifact)
        pipeline = (
            FailurePredictionModel.load_pipeline(pipeline_payload)
            if model_type == "trained_failure_prediction"
            else TabularAnomalyModel.load_pipeline(pipeline_payload)
        )
        return ServingRuntimeArtifact(
            key=key,
            manifest=manifest,
            artifact=artifact,
            pipeline=pipeline,
            model_type=model_type,
            feature_columns=[str(column) for column in artifact.get("featureColumns", [])],
            feature_schema_hash=str(artifact.get("featureSchemaHash") or ""),
            loaded_at=loaded_at,
        )

    def _key(self, model: dict[str, Any]) -> ServingCacheKey:
        return ServingCacheKey(
            model_version_id=str(model.get("modelVersionId") or ""),
            manifest_uri=str(model.get("manifestUri") or ""),
            artifact_hash=str(model.get("artifactHash") or ""),
            artifact_contract_version=str(model.get("artifactContractVersion") or ARTIFACT_CONTRACT_VERSION),
        )

    def _model_type(self, model: dict[str, Any], artifact: dict[str, Any]) -> str:
        if model.get("modelType") == "trained_failure_prediction" or artifact.get("modelType") == "trained_failure_prediction":
            return "trained_failure_prediction"
        return str(artifact.get("modelType") or model.get("modelType") or "trained_tabular_anomaly")

    def _expired(self, cached: ServingRuntimeArtifact, now: float) -> bool:
        return self.ttl_seconds > 0 and now - cached.loaded_at > self.ttl_seconds

    def _evict_capacity(self) -> None:
        while len(self._entries) > self.max_entries:
            key, _ = self._entries.popitem(last=False)
            self.evictions += 1
            self._log("eviction", key, reason="capacity", cache_size=len(self._entries))

    def _log(self, event: str, key: ServingCacheKey, **extra: Any) -> None:
        log.info(
            "mlServingArtifactCache event=%s modelVersionId=%s artifactContractVersion=%s cacheSize=%s loadDurationMs=%s reason=%s",
            event,
            key.model_version_id or "unknown",
            key.artifact_contract_version,
            extra.get("cache_size", ""),
            extra.get("load_duration_ms", ""),
            extra.get("reason", ""),
        )
