from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    service_name: str = "ml-service"
    model_registry_path: str = "./models"
    ml_artifact_store: str = "local"
    ml_artifact_bucket: str = "model-artifacts"
    minio_endpoint: str | None = None
    minio_access_key: str | None = None
    minio_secret_key: str | None = None
    aws_region: str = "us-east-1"
    s3_force_path_style: bool = True


settings = Settings()
