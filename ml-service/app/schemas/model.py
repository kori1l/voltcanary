from pydantic import BaseModel, Field


class ModelSummary(BaseModel):
    name: str
    version: str
    status: str
    model_type: str = Field(alias="modelType")
