## Why

The ML console can create datasets, labels, feature snapshots, training runs,
models, and deployment policies, but it lacks complete lifecycle management.
Operators currently cannot cleanly delete, retire, disable, or archive many
entities after experiments. That leaves stale policies, failed datasets, failed
training runs, wrong labels, candidate models, and obsolete feature snapshots in
the console.

The UI also needs safe destructive actions so operators can correct mistakes
without direct database access.

## What Changes

- Add lifecycle actions for ML deployment policies, model versions, training
  runs, dataset versions, feature snapshots, and labels.
- Prefer safe delete semantics: disable/archive/soft-delete when entities are
  referenced by lineage, and hard-delete only where safe and explicitly allowed.
- Add UI controls for delete/disable/archive/retry/cancel with confirmation,
  impact previews, and audit reasons.
- Prevent deletion of active or referenced entities unless dependent state is
  disabled or archived first.
- Keep lineage and audit records readable after an entity is retired.

## Capabilities

### Changed Capabilities

- `ml-deployment-operations`: deployment policies can be disabled or deleted
  safely from the console.
- `ml-artifact-runtime`: model versions and training runs have safe lifecycle
  actions.
- `ml-dataset-lifecycle`: datasets can be archived or deleted when safe.
- `ml-feature-platform`: feature snapshots can be archived or deleted when safe.
- `ml-failure-outcomes`: manual labels can be corrected or deleted with audit.

## Impact

- `backend/services/analytics-service/**`: lifecycle APIs, authorization,
  reference checks, audit records, status filters.
- `frontend/src/pages/MlSettingsPage.tsx`: action menus, confirmation dialogs,
  status filters, empty states, and current-policy/model lists.
- `docs/api/ml-api.md`: lifecycle endpoint contracts and safety rules.
- Tests for destructive action authorization and referenced-entity protection.

## Dependencies

Can be implemented after `improve-ml-console-guided-workflows`, but does not
require its selectors. UI quality improves when both changes are delivered.
