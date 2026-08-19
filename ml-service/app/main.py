from app.api import health, inference, models, training
from app.internal_boundary import InternalBoundaryMiddleware
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
import logging

app = FastAPI(title="VoltCanary ML Service")
app.add_middleware(InternalBoundaryMiddleware)
logger = logging.getLogger("voltcanary.ml.requests")


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    logger.warning(
        "mlRequestValidationRejected path=%s errors=%s",
        request.url.path,
        len(exc.errors()),
    )
    return JSONResponse(
        status_code=400,
        content={
            "detail": [
                {
                    "loc": error.get("loc", []),
                    "msg": error.get("msg", "Invalid request"),
                    "type": error.get("type", "value_error"),
                }
                for error in exc.errors()
            ]
        },
    )

app.include_router(health.router)
app.include_router(inference.router)
app.include_router(models.router)
app.include_router(training.router)
app.include_router(inference.router, prefix="/ml")
app.include_router(models.router, prefix="/ml")
app.include_router(training.router, prefix="/ml")
