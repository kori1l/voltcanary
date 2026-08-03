---
name: voltcanary-ml-service
description: VoltCanary Python FastAPI ML-service workflow for training, inference, datasets, feature snapshots, hashes, artifact storage, model registry behavior, failure prediction, tests, and ML API compatibility. Use when Codex modifies or reviews files under ml-service/ or ML-facing backend/frontend contracts.
---

# VoltCanary ML Service

## Before Editing

Use `$voltcanary-openspec` first for ML semantics, API compatibility, dataset
lifecycle, feature platform, artifact runtime, failure labels, deployment, or
cross-service changes.

Read the relevant local files before editing:

- API routers: `ml-service/app/api/`
- Schemas: `ml-service/app/schemas/`
- Services: `ml-service/app/services/`
- Models: `ml-service/app/models/`
- Tests and fixtures: `ml-service/app/tests/`
- Contracts: `docs/api/ml-api.md`, `docs/operations/ml-operations.md`,
  `openspec/specs/ml-*.md`

## Compatibility Rules

Keep existing `/health`, `/models`, `/inference`, `/train`, and `/runs/{id}`
routes backward-compatible unless an active OpenSpec change explicitly replaces
them. Preserve `/ml/*` aliases.

Model outputs should remain explainable enough for UI, alerting, and demo flows:
include status, risk fields, confidence/quality signals, reasons, and stable
hashes where the current contract expects them.

Preserve these semantics:

- Dataset versions are immutable once materialized.
- Feature matrices use deterministic hashes and schema-drift checks.
- Runtime inference can load by `modelVersionId` when artifacts are available.
- Missing models or features should return explicit unavailable/status results,
  not crash the API.
- Maintenance windows excluded from ML arrive as `excludedIntervals`.

## Implementation Notes

Prefer deterministic unit tests around service/model behavior and API tests
through FastAPI `TestClient`. Use temporary directories or fakes for artifact
store tests instead of mutating checked-in generated files under
`ml-service/models/`.

When changing dependencies, keep `requirements.txt` and `pyproject.toml`
aligned.

## Validation

From `ml-service/`:

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m unittest discover -s app/tests
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8090
```

Start the server only when manual API verification is useful; otherwise prefer
the unit/API test suite.
