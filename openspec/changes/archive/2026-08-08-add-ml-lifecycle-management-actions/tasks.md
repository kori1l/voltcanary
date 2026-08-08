## 1. Lifecycle API Contracts

- [x] 1.1 Define lifecycle statuses and actions for deployment policies.
- [x] 1.2 Define lifecycle statuses and actions for model versions.
- [x] 1.3 Define lifecycle statuses and actions for training runs.
- [x] 1.4 Define lifecycle statuses and actions for dataset versions.
- [x] 1.5 Define lifecycle statuses and actions for feature snapshots.
- [x] 1.6 Define delete/correct audit behavior for manual labels.

## 2. Backend Reference Protection

- [x] 2.1 Block deleting active or policy-referenced model versions.
- [x] 2.2 Block deleting datasets referenced by labels, snapshots, training runs, or models.
- [x] 2.3 Block deleting feature snapshots referenced by training runs or models.
- [x] 2.4 Preserve artifact and lineage references for historical predictions.
- [x] 2.5 Return structured conflict responses with dependency summaries.

## 3. Frontend Lifecycle UI

- [x] 3.1 Add action menus for policies, models, training runs, datasets, snapshots, and labels.
- [x] 3.2 Add destructive confirmation dialogs with reason fields.
- [x] 3.3 Add filters for active/current, failed, archived, deleted, and all records.
- [x] 3.4 Show dependency summaries before destructive actions.
- [x] 3.5 Refresh lists and selectors after lifecycle actions.

## 4. Tests And Docs

- [x] 4.1 Add backend tests for authorization and reference checks.
- [x] 4.2 Add frontend tests for confirmation flows and list refresh.
- [x] 4.3 Update API docs with lifecycle endpoints and conflict payloads.
- [x] 4.4 Update ML operations docs with cleanup and rollback guidance.

## 5. Verification

- [x] 5.1 Verify an operator can delete a stale policy without breaking active predictions.
- [x] 5.2 Verify an active policy prevents model deletion until disabled.
- [x] 5.3 Verify archived datasets and snapshots are hidden from default selectors.
- [x] 5.4 Run `npm run openspec:validate`.
