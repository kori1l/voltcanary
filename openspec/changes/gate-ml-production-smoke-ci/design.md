# Gate ML Production Smoke In CI Design

## Scope

The gate should cover the complete lifecycle:

1. start Docker application profile and dependencies;
2. create or import training data;
3. generate horizon labels;
4. generate a feature snapshot;
5. queue and complete training;
6. verify artifact storage and manifest hash;
7. approve or inspect candidate gates;
8. create shadow/canary/active deployment policy as appropriate;
9. trigger live recalculation;
10. verify prediction traceability and monitoring signals.

The smoke should prove that live inference used the selected `modelVersionId`
or explicitly rejected it with a controlled status and fallback trace.

## CI Trigger

The first implementation can be opt-in or path-filtered. It should run when
changes touch:

- `ml-service/`;
- analytics-service ML lifecycle code;
- `docs/api/ml-api.md` or ML specs;
- deployment config for ML-service, MinIO, observability, or analytics-service;
- scripts used by ML production verification.

## Runtime Budget

The smoke should use a small deterministic dataset and bounded polling. If it
cannot complete within the CI budget, it should fail with the current lifecycle
step and service diagnostics rather than timing out without context.

## Artifacts

The workflow should publish:

- command log summary;
- created dataset/training/model/policy ids when available;
- relevant prediction trace fields;
- metrics scrape snippets or assertions;
- failure diagnostics.

Avoid publishing secrets, raw artifact payloads, or full generated model files.

## Local Use

Docs should provide one PowerShell command for local Windows use and one
portable command for CI/Linux when possible. The command should clean up only
resources it created or clearly document persistent resources.
