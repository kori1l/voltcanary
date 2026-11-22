import os

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(populate_by_name=True)

    service_name: str = "ml-service"
    model_registry_path: str = "./models"
    ml_runtime_profile: str = "local"
    ml_production_like_profiles: str = "production,prod,docker,kubernetes,k8s"
    ml_artifact_store: str = "local"
    ml_artifact_bucket: str = "model-artifacts"
    minio_endpoint: str | None = None
    minio_access_key: str | None = None
    minio_secret_key: str | None = None
    aws_region: str = "us-east-1"
    s3_force_path_style: bool = True
    ml_internal_auth_required: bool = True
    ml_internal_service_token: str = ""
    ml_allowed_source_services: str = "analytics-service"
    ml_max_request_bytes: int = 1_048_576
    ml_training_history_path: str | None = None
    ml_training_postgres_host: str = Field(
        default="localhost",
        validation_alias=AliasChoices("ML_TRAINING_POSTGRES_HOST", "POSTGRES_HOST"),
    )
    ml_training_postgres_port: int = Field(
        default=5432,
        ge=1,
        le=65535,
        validation_alias=AliasChoices("ML_TRAINING_POSTGRES_PORT", "POSTGRES_PORT"),
    )
    ml_training_postgres_database: str = "voltcanary_ml"
    ml_training_postgres_user: str = Field(
        default="voltcanary",
        validation_alias=AliasChoices("ML_TRAINING_POSTGRES_USER", "POSTGRES_USER"),
    )
    ml_training_postgres_password: str = Field(
        default="",
        repr=False,
        validation_alias=AliasChoices("ML_TRAINING_POSTGRES_PASSWORD", "POSTGRES_PASSWORD"),
    )
    ml_training_history_capacity: int = Field(default=1000, ge=1)
    ml_training_history_retention_seconds: float = Field(default=604800, gt=0)
    ml_training_fit_timeout_seconds: float = Field(default=600, gt=0)
    ml_serving_cache_max_entries: int = 32
    ml_serving_cache_ttl_seconds: float = 900.0

    @property
    def allowed_source_service_names(self) -> set[str]:
        return {item.strip() for item in self.ml_allowed_source_services.split(",") if item.strip()}

    @property
    def internal_service_token(self) -> str:
        return self.ml_internal_service_token or os.getenv("INTERNAL_SERVICE_TOKEN", "")

    @property
    def production_like_profile_names(self) -> set[str]:
        return {
            item.strip().lower()
            for item in self.ml_production_like_profiles.split(",")
            if item.strip()
        }

    @property
    def is_production_like_runtime(self) -> bool:
        return self.ml_runtime_profile.strip().lower() in self.production_like_profile_names


settings = Settings()
