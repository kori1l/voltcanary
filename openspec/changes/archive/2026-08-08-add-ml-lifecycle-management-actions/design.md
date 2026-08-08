## Context

ML lifecycle entities are strongly linked:

- dataset versions feed labels and feature snapshots;
- feature snapshots feed training runs;
- training runs register model versions;
- model versions are selected by policies;
- policies affect live predictions.

Deleting an entity without checking references can break traceability. At the
same time, local experimentation and operator correction require a way to remove
wrong or stale objects from the default UI.

## Goals

- Let operators clean up experiments from the ML console.
- Protect active serving paths and training lineage.
- Preserve audit and traceability for any entity used by a model, policy, or
  prediction.
- Use explicit confirmation for destructive actions.
- Make current state clear: active, approved, candidate, archived, deleted,
  disabled, failed, canceled.

## Non-Goals

- Do not physically delete model artifacts from object storage when they are
  referenced by audit or predictions.
- Do not allow deleting active models while an active policy references them.
- Do not bypass authorization or account ownership checks.
- Do not remove immutable lineage hashes from historical records.

## Design

### Action Semantics

Use four action categories:

- `disable`: stop runtime use without deleting the record;
- `archive`: hide from default active lists but retain lineage;
- `delete`: remove an unreferenced draft/failed/candidate object where safe;
- `restore`: optional future action for archived entities if needed.

The API should return an impact preview or structured conflict response when an
action cannot proceed.

### Policy Lifecycle

Deployment policies are runtime configuration. Operators should be able to:

- delete an inactive policy;
- disable an active policy, causing resolution to fall back to the next policy
  layer or baseline;
- see which devices or scopes are affected before action confirmation.

### Model Lifecycle

Model versions should support:

- reject candidate;
- archive approved but inactive model;
- delete candidate/failed/unreferenced model metadata where safe;
- block deleting active or policy-referenced models.

Model artifacts should remain available while any prediction, training run, or
audit record references them.

### Dataset, Label, Snapshot, And Training Lifecycle

Dataset versions and feature snapshots are immutable training assets. Deleting
or archiving must check references:

- delete only unreferenced failed/draft assets;
- archive successful assets to hide from default selectors;
- block deleting datasets referenced by labels, snapshots, training runs, or
  model versions unless the operation is explicitly archival.

Manual labels can be deleted with audit. Generated labels should be superseded
by regenerating labels for the same config and lineage rather than hard-deleted
without record.

### UI

Each lifecycle list should expose row actions with:

- action label;
- destructive confirmation;
- required reason for destructive or archival action;
- dependency/impact summary;
- success state and refresh.

Default lists should hide archived/deleted records but provide filters to show
them.

## Risks

- Reference checks can be incomplete if historical predictions are not included.
  Prefer blocking deletion and offering archive when in doubt.
- Hard delete can conflict with immutable ML lineage. Treat hard delete as a
  narrow cleanup operation for unreferenced records.
