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
- `GET /models`
- `POST /inference`
- `POST /train`
- `GET /runs/{id}`

The same ML endpoints are also exposed under `/ml` for compatibility, for
example `GET /ml/models` and `POST /ml/inference`.
