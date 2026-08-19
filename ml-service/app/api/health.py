from typing import Any

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.services.artifact_store import ArtifactStore, ArtifactStoreHealthError

router = APIRouter()


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "ml-service"}


@router.get("/health/artifacts", response_model=None)
def artifact_health() -> Any:
    try:
        return {"service": "ml-service", **ArtifactStore().health()}
    except ArtifactStoreHealthError as exception:
        return JSONResponse(
            status_code=exception.status_code,
            content={"service": "ml-service", **exception.payload},
        )
