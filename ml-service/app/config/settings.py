import os

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
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

    @property
    def allowed_source_service_names(self) -> set[str]:
        return {
            item.strip()
            for item in self.ml_allowed_source_services.split(",")
            if item.strip()
        }

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
