# AI device creation: design

## Contract

Within the existing plan API, normalize CREATE_DEVICE to name, deviceTypeRef,
optional locationRef, optional manufacturer/model. Types and locations come
from server-authorized catalogues; no accountId, role, owner, token or arbitrary
metadata in model schema. Page candidates are resolved server-side. Bounded
catalogue search/pagination does not leak other users or silently omit ambiguity.
Unknown type and duplicate location names produce NEEDS_INPUT with explicit
choices. Missing required name is asked, not replaced by AI Created Device.
Manufacturer/model remain unknown unless provided. Limit first version to one
device per task; plural requests ask user to narrow instead of partial creation.

## Execution

The compiler creates one create-device command, not arbitrary URLs or a model
supplied chain. Prepare is read-only and validates schema/references. Review
shows name, actual type/location and scope. Execute uses the current JWT and
POST /devices; DeviceService remains the owner of account/location checks.
No account creation side effect during preparation. Preserve existing behavior
of actual POST /devices for ordinary active-user provisioning.

Add optional Idempotency-Key to POST /devices, scoped by verified user/current
account/operation; bind to canonical payload digest. Existing callers without
key remain compatible, AI always supplies a stable action key. Store receipt
and result in the same transaction as device creation and existing events;
conflicting key/payload returns 409, parallel identical requests return one
resource. Replay performs current identity/account/resource checks. Default
receipt retention at least 30 days and never shorter than active task retry
window; expired task cannot create with an expired key. Tests cover DB rollback,
lost response, restart and replay after decommission/access-state change.

Show SUCCESS and a link only from actual service result. Existing same-name
device prompts a non-blocking duplicate review; name alone is not identity or
an idempotency key. Creation with no data source is honest: source not connected,
no fabricated telemetry/online state. Follow-up opens ordinary source setup;
secret-bearing fields stay in dedicated forms outside model/context/history.
Demo/simulator requests remain explicit existing workflows, never a hidden
fallback for real creation. No automatic deletion compensation.

## Small-model acceptance

Commit a sanitized versioned intent-evaluation fixture with at least 40 Russian
requests: 20 supported clear creates, 10 missing/ambiguous choices, 10 foreign
refs/unsupported/injected inputs. Freeze expected intent, allowed fields and
required questions before measuring. Deterministic adapter tests are required
in CI. A live evaluation uses the user's configured small model without a silent
upgrade to a stronger model: at least 18/20 clear requests prepare a correct
review within one clarification; all unsafe/ambiguous cases remain unexecuted
until explicitly resolved. Record model id/provider/prompt/schema version,
latency and actual outcomes without prompts containing private data or keys.
If no provider is configured, record BLOCKED for live quality, not PASS.

## Verification

Maven AI/device and real PostgreSQL idempotency/isolation tests; frontend
typecheck/test/build; browser create, clarify, cancel, expired plan, retry and
open-source-setup. Two-account foreign location/device probes must produce no
read/write leakage. Current docs updated only with implemented behavior.

