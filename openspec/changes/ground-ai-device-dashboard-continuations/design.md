# Design

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


Architecture amendment,2026-10-26: [target orchestration](../../../docs/target/ai-assistant-orchestration.md)
supersedes free-form phrase matching with model-selected facts/steps and server
receipt/access validation. The referent, capability, truthful-failure and regression
requirements below remain; use the program's delegated owners and shared budget.

## Durable referents
Derive a typed conversation resource context from confirmed execution receipts:
created deviceId, sourceId, resource role and receipt/review provenance. A proposed or
failed create is not a successfully created device. Revalidate owner access on each
use. Preserve separate device and dashboard task state; do not pass entire transcripts
as executable commands. Resolve “this bus” against the most recent unambiguous
successful relevant result; explicit user selection overrides it. If several results
are plausible, ask a question naming real devices. Deleted/inaccessible resources
produce a resource-unavailable state without silently switching devices.

Apply the context at the server boundary before preparing the dashboard, not only
through a special frontend handoff button. Persist the resolved selection and origin
in the new dashboard task and trace. Preserve task/review revision and owner checks.

## Data-first composition
Resolve the selected device and source, then request the existing dashboard-owned
capability snapshot. Only after that provide bounded actual dataRefs, value types,
units, freshness/history and supported widget/view settings to planning. Do not start
composition with a fleet catalogue when a concrete unresolved referent was requested.
For “all useful data”, propose a bounded nonduplicative selection: location as map,
numeric measurements as current values/history, boolean state as value/timeline,
text/journey context as supported values/details. Respect source/value compatibility;
avoid automatically graphing latitude/longitude when the map already represents them.

Missing history is an honest empty/history-growing view, not permission to fabricate
readings. A newly connected source may be waiting for first telemetry; expose that
state and refresh capabilities on explicit retry/readiness transition. Do not retry
the model to repair missing data. Stale data remains distinguishable from fresh data.

## Device contract recovery and user messages
Keep integration configuration under fixed server-owned HSL intent parsing. Record
unexpected model fields, distinguish supported integration hints from unsafe/unknown
commands, and normalize only uniquely equivalent fields under explicit tested rules.
Never execute arbitrary MQTT URLs, tokens or invented connector commands. Correctable
device-output faults receive bounded internal repair with preserved original request,
name/type and connector identity; count every attempt in the shared trace. Do not ask
users to edit intent/schema/dataRef fields. Preserve existing safety validation.

On genuine ambiguity ask one concrete question with actual choices. Technical failure
is a failed turn with a retry action, not NEEDS_INPUT. Only mention an older accepted
dashboard if one exists; preserve the device and failed requested work. This boundary
does not introduce an independent dashboard repair loop; general recovery remains stage6.

## Acceptance corpus
Use the reported detailed HSL prompt and free-form variants, then “for this bus” and
“all useful data”. Test reload between creation and continuation, two created buses,
explicit switch, failed/unknown creation, deleted device, no data, stale data, numeric/
boolean/text fields, incompatible view and unavailable capability service. Include
ordinary non-HSL devices and edits of existing dashboards. Real-model Docker trials
must trace actual choices and prove reviewed/created bindings match; isolated fixtures
must not alter user devices. Separate model misses from contract violations and report
comparison with existing evidence without rescoring old runs.

## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
