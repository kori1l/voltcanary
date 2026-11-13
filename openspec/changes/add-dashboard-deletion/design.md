# Design

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


Use nullable dashboards.deleted_at rather than removing immutable published revisions.
DELETE /dashboards/{id} resolves the current actor, locks actor/dashboard, requires exact
owner/account and If-Match for that owner's saved draft, marks the row deleted and archived,
and appends DELETED audit. Existing preference resolution filters unavailable entries;
clear the owner's personal default/favorites during deletion. All storage lookups and lists
exclude deletion markers, so shared reads, execution receipts and restore cannot reopen it.
Expose delete permission only to the exact owner/account; existing delegated capabilities
never confer deletion. No DELETE grant is introduced.

The shared management component covers catalogue, viewer settings and editor settings.
Confirmation fetches the current saved draft token and retains it until submission. A stale
token requires reopening the confirmation. Single-flight guards prevent duplicate requests;
errors keep the confirmation open. Successful deletion updates catalogue subscribers and
navigates to /dashboards. No tests or live deletion are run in this implementation pass.
The existing Docker services are restarted to load the change; the dashboard service
startup compiles with tests skipped and applies the additive Flyway migration.

## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
