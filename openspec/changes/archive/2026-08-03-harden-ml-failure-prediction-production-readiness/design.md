# Design

## Audit Status Snapshot

The audit's original blockers should be reclassified before new implementation
work starts.

Implemented by archived OpenSpec changes:

- Dataset lifecycle: external imports create dataset versions, internal
  telemetry materializes immutable rows, dataset hashes and quality profiles are
  stored.
- Feature platform: offline feature matrices, feature hashes, templates, live
  feature generation, and parity tests exist.
- Artifact/runtime: MinIO/S3 artifacts, model manifests, worker execution,
  cancellation, and loading by `modelVersionId` exist.
- Outcomes and labels: structured maintenance outcomes, outcome projection,
  horizon labels, and label lineage exist.
- Supervised failure prediction: horizon targets, sklearn models, time-based
  evaluation, model cards, approval gates, risk drivers, and additive inference
  fields exist.
- Deployment runtime: device-type policy resolution, deterministic canary,
  shadow separation, fallback traceability, model-origin hygiene, and monitoring
  metrics exist.

Remaining production-readiness work:

- A single reproducible Docker verification scenario must prove the whole audit
  workflow end to end with real persisted data: dataset creation/import,
  feature generation, horizon labels, failure-model training, artifact loading,
  approval/deployment, live inference by `modelVersionId`, fallback, trace, and
  metrics checks.
- The audit document needs a status matrix so future work does not re-open
  already archived ML changes by accident.
- ML Settings UI needs production polish: form controls must use stable grids,
  no labels or inputs should collide, action buttons should have predictable
  alignment, and API failures should be shown as operator-readable messages.
- Verification should include frontend layout regression coverage for the ML
  workflow tabs shown in the screenshots.

## Frontend Layout Approach

Keep the existing ML Settings page and data flow. Add reusable layout classes
instead of redesigning the page:

- `ml-control-grid` for ordinary two-column field groups.
- `ml-control-grid-three` for compact numeric or deployment policy groups.
- `ml-field` and `ml-checkbox-field` for labels that stack text and controls
  consistently.
- `ml-action-row` or existing `action-row-start` for buttons.

The CSS should keep desktop density but allow each control to own its column.
At mobile widths, the existing responsive breakpoint should collapse ML grids to
one column.

## Error Message Approach

`safeMlError` should recognize JSON error bodies from gateway/backend responses
and return a short message based on `message`, `error`, `status`, and `path`.
The banner can still show useful context, but it must not render the entire JSON
object as a single raw string.

## Verification

At minimum:

- `npm run openspec:validate`
- `npm run typecheck` in `frontend`
- `npm test -- MlSettingsPage.test.tsx` in `frontend`

If implementation touches backend verification scripts or lifecycle behavior,
also run the relevant analytics-service and ML-service targeted tests.
