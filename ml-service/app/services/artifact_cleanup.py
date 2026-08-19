from dataclasses import dataclass, field
from typing import Any, Iterable


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


class ArtifactCleanupPlanner:
    def preview(
        self,
        artifacts: list[dict[str, Any]],
        references: ArtifactReferenceSet | None = None,
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        references = references or ArtifactReferenceSet()
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
        return candidates, blocked
