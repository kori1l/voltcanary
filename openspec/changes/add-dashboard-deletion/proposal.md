# Dashboard deletion

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


Current status (2026-10-28): Separate non-AI follow-up (0/5 tasks checked). Implementation is written; runtime/browser verification was explicitly deferred. It is not an AI prerequisite and is not authorized to run deletion checks by this status audit.

## Why
Users can archive dashboards but cannot remove unwanted dashboards from their catalogue.

## What Changes
- Add owner-only DELETE with a saved-draft precondition and confirmation in shared dashboard management.
- Hide deleted dashboards from all catalogues and direct access, including shared access and restore.
- Retain immutable history/audit internally using a deletion marker; do not delete devices or telemetry.

## Impact
Dashboard service storage/API, shared frontend management controls and API documentation.
Runtime testing is deferred at the user's explicit request.
