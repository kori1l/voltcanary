from app.api import health, inference, models, training
from fastapi import FastAPI

app = FastAPI(title="VoltCanary ML Service")

app.include_router(health.router)
app.include_router(inference.router)
app.include_router(models.router)
app.include_router(training.router)
