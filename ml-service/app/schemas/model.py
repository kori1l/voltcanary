from pydantic import BaseModel, Field


class ModelSummary(BaseModel):
    name: str
    version: str
    status: str
    model_type: str = Field(alias="modelType")
    model_version_id: str | None = Field(default=None, alias="modelVersionId")
    artifact_uri: str | None = Field(default=None, alias="artifactUri")
    manifest_uri: str | None = Field(default=None, alias="manifestUri")
    artifact_hash: str | None = Field(default=None, alias="artifactHash")
