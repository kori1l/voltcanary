# ML Service

FastAPI service for VoltCanary data preparation, model selection, training,
evaluation, inference, and model registry workflows.

## Run locally

```bash
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8090
```

Use Python 3.12. Dependency versions are pinned in `requirements.txt` and
mirrored in `pyproject.toml`.

## Endpoints

- `GET /health`
- `GET /models`
- `POST /inference`
- `POST /train`
- `GET /runs/{id}`

The same ML endpoints are also exposed under `/ml` for compatibility, for
example `GET /ml/models` and `POST /ml/inference`.
