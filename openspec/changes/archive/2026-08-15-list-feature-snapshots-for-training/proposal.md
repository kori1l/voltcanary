# List Feature Snapshots For Training

## Why

The ML console currently keeps only one generated feature snapshot in page
state. When an operator creates a second snapshot or switches to Training, older
snapshots are not visible and training can lose the intended snapshot selection.

Operators need to see the snapshot inventory and choose a compatible snapshot
explicitly before queueing training.

## What Changes

- Add a read endpoint for account-owned feature snapshots, optionally filtered
  by dataset version.
- Load and keep a frontend list of feature snapshots.
- Show feature snapshot inventory in the dataset workflow.
- Populate Training snapshot selection from the full list instead of only the
  latest generated snapshot.
- Preserve client-side training blocks for missing, mismatched family, or
  mismatched horizon snapshots.

## Impact

Affected specs:

- `ml-feature-platform`
- `ml-console-workflows`

Affected implementation:

- analytics-service feature snapshot controller/service/tests
- frontend ML module API client
- ML Settings dataset/training tabs and tests
