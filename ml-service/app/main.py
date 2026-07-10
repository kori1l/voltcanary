from app.api import health, inference, models, training
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

app = FastAPI(title="VoltCanary ML Service")


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
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
