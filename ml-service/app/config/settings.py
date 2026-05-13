from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    service_name: str = "ml-service"
    model_registry_path: str = "./models"


settings = Settings()
