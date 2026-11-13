# Design

Keep legacy retirement separate. A new POST /devices/{id}/deletion accepts an
explicit deleteHistory boolean and creates an owner-scoped durable lifecycle job.
The registry records an irreversible deletion marker, revokes all source tokens
and emits ordinary runtime invalidation. Hibernate filters deletion markers from
ordinary device reads, listings and mutation targets. Minimal deletion receipts
and retained history metadata are separate from active registry representations.

Data-owning services close a permanent per-device write fence and optionally purge
their history in their own transactions. The device coordinator retries incomplete
steps with fixed internal endpoints, bounded timeouts and an internal service
credential. It records per-service progress and does not claim completion while
a participant is unavailable. Late event processing observes the local fence.
Shared ML materializations are explicit dependency blockers rather than silently
destroyed shared resources. Retained telemetry remains owner-readable through a
deletion history view; new dashboard bindings cannot target a deleted device.

The upper-right button opens a confirmation with retain/delete-history choices.
No user devices are deleted during implementation; only isolated test fixtures
are used for destructive acceptance. Source integration cleanup preserves every
other connector binding. Raw event-bus/backups follow their existing retention;
the purge promise covers the application's owned historical stores.

On purge completion the registry physically removes the marked device and its
source/dependent configuration rows; the receipt has no foreign key to that row.
With retain, an irreversible marker keeps historical registry dependencies
available without restoring the device. Cleanup uses local JDBC row locks shared
with transactional write entrypoints (including configured variables, maintenance
projections, analytics jobs and simulation ticks). HSL cleanup is available even
when AI provisioning is disabled. Source-level rule evidence is cleaned using
source UUIDs read from the authoritative internal receipt.
