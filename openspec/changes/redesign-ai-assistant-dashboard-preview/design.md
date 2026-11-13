## Decisions
Use one persistent assistant host in AppLayout. Minimize and mode switches retain in-memory conversation. Persist presentation preferences through a typed helper; keep task recovery scoped to authenticated identity. Use a nonmodal labelled dialog surface with keyboard close, visible controls and bounded scroll. Keep administrative model settings separate.

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Runtime remains stopped for the configurable program; no paid/live authorization is inferred.


Legacy tasks use an ordered `panelLayout` intent of `{key,width,height}` with template panel keys, widths FULL/HALF/THIRD and integer row heights 2–12. Omission retains template defaults; an explicit list selects exactly those panels. Dashboard-service packs rows deterministically on desktop (12), tablet (6) and mobile (1). Models never emit coordinates, bindings or raw definitions. Preserve previous intent as an untrusted hint while resolving resources through fresh authorized catalogues. Freeze prepared intent and canonical digest in each plan. Reprepare at execution and reject drift. New requests use catalogue composition as described below.

Preview the stored action definition using shared DashboardCanvas and DashboardWidgetPanel. Add a read-only definition preview endpoint invoking the existing query pipeline after ordinary definition/binding validation. The response has no saved dashboard identity or snapshot-detail route; reuse genuine evidence, explicit no-data, denied and unavailable states. Use container width to choose the same responsive layout in ordinary viewing and preview; offer explicit breakpoint preview when necessary.

Clarification replaces the current review while retaining prior messages; all execution controls are disabled during clarification. Creating a reviewed private dashboard is the explicit confirmation, rather than a duplicate modal. Other mutating tools retain their confirmation boundary. Read-only execution can run immediately after planning. Do not embed the assistant inside device screens or transfer page-specific details without explicit context selection.

## Validation
Focused frontend tests verify mode/state retention, continuous composer, stale responses and definition-to-canvas identity. Backend tests cover strict bounded intent, packing, omission/removal, period updates, unauthorized bindings and exact reviewed creation. Run frontend typecheck/test/build, changed Maven modules, OpenSpec and structure checks. Record browser/runtime and live small-model evidence honestly; offline fixtures alone do not prove provider acceptance.

## Catalogue composition correction
New requests use `dashboard-intent-v3`: name, period, deviceRefs and a complete ordered widgets list with stable keys, catalogue types, readable titles, optional deviceRefs/metric/unit/sourceRef/visualization/reducer/text and bounded width/height. Templates remain a legacy path only. Models cannot emit definitions, URLs or raw queries. The owning service exposes read-only `/dashboards/composition/prepare`, resolves current capabilities and source identities, constructs typed queries, packs all three layouts and uses ordinary definition checks. Vehicle maps use the genuine `vehicle-map` widget with explicit recorded sources, never `location-matrix`. Map intervals are at most24h; longer intervals require a separate explicit per-widget period. Clarification preserves the compiled widget intent with renewed resource refs, including migration from an old template review to free composition. Execution re-prepares and compares the exact digest before submitting the immutable definition.

## Long model requests
Provider adapters use their existing timeoutMs only for connection establishment;
read timeout is zero (unbounded). The AI gateway route defaults to response-timeout
-1. Do not shorten reasoning or switch models to hide slow calls. The overlay shows
an honest pending indicator and elapsed time outside the preview scroll area,
with separate read-execution/recovery phases. It does not invent backend stages
or display internal reasoning. Minimize retains the in-flight request.

## Current vehicle speed
Only owner-reported FRESH position evidence can supply current speed. Stale fleet
rows show current speed unavailable and the full measurement date, retaining the
last known position for orientation. A failed fleet refresh or paused live section
also makes current speed unavailable. Map summaries and configured GPS details
hide the old speed when stale and explicitly date the retained measurements.
Recorded history playback keeps its historical evidence semantics.

## Timed clarification defaults
An unanswered NEEDS_INPUT question offers a visible default for60 seconds. Any
typing or composition start cancels the automatic answer for that question even
if the draft is later erased. A manual opt-out is also available. Questions carry
structured missingFields, suggestedValues, suggestedReply and suggestionLabel from the model.
The browser neither parses question wording nor invents names or default values.
The planner chooses concrete scalar suggestions within available capabilities.
The browser displays these values and serializes them as an ordinary reply;
it does not resend arbitrary model prose that could itself be another question.
all ordinary validation/authorization remains in force. Automatic replies are
labelled, never confirm mutations, never repeat a default for the same field, and
cannot retry themselves indefinitely after failures. A different missing field
can receive its own deadline. Provider failures do not
offer auto-replies. A new question receives its own deadline. Minimize retains
the in-flight timer; identity changes clear the conversation and timer.

Planner creation-v14 receives taskMemory independently of the message transcript.
The compiled working intent (name, period, equipment, widgets and layout) is
persisted in context_json._clarification.input as soon as a review is prepared,
and survives subsequent question-only turns and authorized recovery. Resource
references are renewed through current owner catalogues on each request; this
memory does not grant resource access. Known fields must be reused, not asked
again. A structured question about known fields receives one internal model repair
with memory; repeated failure does not produce another automatic continuation.
There is no phrase list for interpreting referential replies and no frontend copy
of the task name. The progress/default notices share the composer's centered width.

## Widget capabilities and presentation
Add vehicle-value and vehicle-timeline using the existing authorized position evidence pipeline, fixed vehicle/source binding and maximum24h interval. A selected vehicleField identifies a recorded numeric, boolean or string field; absent values stay unavailable, stale values retain explicit age, and history lists only observed states without inferring events across gaps. AI receives recorded field metadata, never secrets or fabricated values. Composition accepts bounded boolean showTitle/showMapDetails controls and vehicleField; preparation and task renewal preserve them in the immutable reviewed definition. Map details control hides normal follow/summary/GPS rows while retaining errors and stale/truncated warnings. Ordinary editing retains a drag header; viewing obeys showTitle. Charts measure their pixel viewport so text and markers do not stretch. Invalid composition field names trigger one same-model repair with the rejected draft and allowed fields, with normal validation retained.

## Stable refinements and reversible review
Initial composition remains a full widget list. When a previous composition exists, the model emits bounded widgetEdits, addWidgets and optional complete widgetOrder instead of rebuilding widgets. Omitted root/panel fields remain byte-for-byte equivalent in intent; keys identify existing panels. The server merges edits then runs the same authorized preparation and immutable-definition digest pipeline. Full recomposition during refinement receives one same-model repair and cannot silently replace the review. Models can request restoreSteps; only the clarification use case resolves those steps through owner-scoped stored ancestry, revalidates access and clones the exact frozen reviewed action into a new unexecuted plan. No arbitrary client definition or model-provided history identity is authoritative.
Responses expose owner-scoped previousReviewIds for eligible unexecuted dashboard ancestors. Frontend history navigation fetches an existing plan through GET (never replans or executes), maintains redo locally, branches subsequent edits from the selected plan and recovers ancestry after reload. Execution and in-flight navigation disable history controls. Compact preview charts use their actual dimensions rather than a fictitious90px minimum viewBox; insufficient graph space has a meaningful minimum canvas area.

## Compact value cards
Composition heights accept1–12 rows, matching ordinary manifest sizing. A boolean compact presentation is preserved across AI preparation/refinement and manual editing. The shared canvas selects compact rendering for value cards of at most two rows unless explicitly disabled. One-row cards use small title/value typography and spacing; stale source values show a short warning with measurement time in accessible tooltip metadata, and full evidence stays in the panel information dialog.

## Optional map footer notices
Optional boolean showMapNotices defaults true independently of showMapDetails. False removes the stale-position and truncated-history footer paragraphs from the dashboard map. Provider errors, waiting-for-GPS state and map attribution remain visible, and ordinary panel information retains evidence and measurement date. AI edits only the existing map presentation and retains unrelated widget settings.

## Successor design ownership
The next two target sections describe the original direction. Detailed contracts,
concurrency, failure budgets, outcome checks and rollout are now owned by the
[reliability program](../README.md#ai-dashboard-reliability-program), not a parallel
implementation. Some memory/repair/state-graph behavior already exists; unfinished
acceptance remains tracked in tasks11/12 until successor verification.

## Preparation repair and durable pending requests
Dashboard compilation reports typed technical rejections. Planning retries at most twice with the same configured model, actual preparation diagnostics, the rejected draft, original request, known task name and renewed authorized catalogues. Unresolved refinements retain their pending request alongside the last successful intent; an empty change cannot silently satisfy a pending correction. Explicit name commands are saved independently of a successful prepared dashboard. Vehicle timelines support list and line rendering of source field observations, using the ordinary evidence pipeline and broken lines for missing/long intervals.

Creation uses a dedicated dashboard-only prompt. Safe normalization of redundant RAW metric-shaped parameters retains the chosen vehicle scalar/history type when the recorded field and unit match. Partial moveWidgets operations preserve untouched widget relative order server-side.

## Semantic refinement compiler
The live tram replay showed that syntactically valid deltas still move unrelated charts. Refinements therefore use typed domain operations rather than asking the model to assemble widget edits and complete ordering. `place_values` identifies recorded fields and a placement; the compiler resolves/reuses current-value widgets, creates missing compatible cards and moves only those cards. `show_history` identifies a recorded field and list/line presentation; the compiler reuses a matching history, chooses a compatible renderer from field type and retains its identity. New source fields never become invented numeric metrics. Widget-specific presentation, size, title and removal operations identify existing keys and have bounded writable fields. Model operations cannot submit raw definitions, bindings or queries.

The compiler owns stable keys, default dimensions, data bindings and ordering. It lowers operations into the existing validated delta pipeline, checks postconditions (requested current fields exist, requested history presentation exists, moved group placement, untouched widget equality/order), and rejects the entire candidate on failure. Technical repair receives diagnostics and the same typed operation schema; the current review remains available until a candidate passes. Legacy deltas remain compatible for stored tasks, but the new model prompt emits semantic operations only. Names and exact review restoration remain independent of the model's layout output.

## Shared widget capabilities and structured settings
The dashboard domain owns a versioned capability descriptor per widget type. Ordinary manifests and the AI composition catalogue expose the same descriptor: data family and accepted value types, binding requirements, compatible visualizations and typed presentation settings with defaults and sections. The editor renders common header/details settings in General for every widget and type-specific settings in grouped Appearance sections. Data selection, calculation/time, navigation and layout remain separate concerns. Irrelevant presentation controls are not offered merely because another widget implements them.

Existing presentation keys and saved defaults remain compatible; there is no migration or reinterpretation of omitted settings. AI composition accepts a bounded settings object whose keys and values are checked against the same descriptor and lowers it to presentation. Legacy flattened composition visibility flags remain accepted. Incremental settings merge preserves unspecified keys. This catalogue work does not implement the separate semantic operation compiler.

## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
