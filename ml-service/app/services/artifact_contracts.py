from typing import Any, TypedDict


class SavedModelBundle(TypedDict):
    artifactUri: str
    manifestUri: str
    artifactHash: str
    artifactSizeBytes: int


class RuntimeManifest(TypedDict):
    python: str
    service: str
    dependencies: dict[str, str]


class InferenceInputContract(TypedDict):
    modelVersionId: str
    requiredFields: list[str]
    featureSchemaHash: str


class ModelManifest(TypedDict):
    artifactContractVersion: str
    modelVersionId: str
    modelFamily: str
    algorithm: str
    featureSchemaHash: str
    featureConfigVersion: str
    trainingRunId: str
    datasetVersionId: str
    runtime: RuntimeManifest
    supportedMetrics: list[str]
    supportedDeviceTypes: list[str]
    metrics: dict[str, Any]
    evaluation: dict[str, Any]
    approvalGates: dict[str, Any]
    inferenceInputContract: InferenceInputContract
