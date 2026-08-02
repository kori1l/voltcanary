from fastapi import APIRouter

from app.services.artifact_store import ArtifactStore

router = APIRouter()


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "ml-service"}


@router.get("/health/artifacts")
def artifact_health() -> dict[str, str]:
    return {"service": "ml-service", **ArtifactStore().health()}
