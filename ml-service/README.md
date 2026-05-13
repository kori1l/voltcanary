# ML Service

FastAPI service for VoltCanary data preparation, model selection, training,
evaluation, inference, and model registry workflows.

## Run locally

```bash
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8090
```

## Endpoints

- `GET /health`
- `GET /ml/models`
- `POST /ml/inference`
- `POST /ml/train`
- `GET /ml/runs/{id}`
