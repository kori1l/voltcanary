# Design: Versioned operations over a server-owned review

## Interfaces and bounded envelope
Define `dashboard-operations-v1` using sealed Java domain types and a generated or
parity-tested JSON Schema. The model sees a bounded current-review projection,
task facts/pending work and task-local data choices; it returns operations rather
than an editable definition. Limit each turn to24 operations and retain the final
1-12 widget,1-20 device and current context/reply limits. Reject unknown fields,
duplicate operation IDs and conflicting writes before preparation.

Support adapter strict structured output where available, with equivalent server
validation for other configured providers. Valid JSON alone does not prove intent
or outcome correctness. Version prompts/schema together and retain old parsers for
stored reviews; do not require provider-specific SDK adoption.

## Initial operation catalogue
| Operation | Model supplies | Application owns |
| --- | --- | --- |
| show_map | authorized dataRef, optional target widgetKey | genuine map type, source binding, new key/default size |
| show_current | dataRef, optional target widgetKey | compatible value widget, source/field/metric binding |
| show_history | dataRef, supported view, optional target widgetKey | compatible history query, reuse and gap semantics |
| add_note | explicit text/title | normal context-note payload and bounds |
| place_widgets | existing keys or earlier operation refs, TOP/BOTTOM/BEFORE/AFTER, optional anchor | partial ordering and responsive packing request |
| resize_widgets | keys, supported width and row height | manifest bounds, geometry change mask and quantization |
| set_presentation | keys or explicit ALL scope, typed setting patch | descriptor validation and per-key merge/reset |
| rename_widget | widgetKey and title | bounded title-only edit |
| set_data | widgetKey and authorized dataRef | explicit compatible binding replacement, no implicit type switch |
| remove_widgets | exact existing keys | selected removal and dependent-operation validation |
| rename_dashboard | explicit name | task fact precedence and prepared title |
| set_period | dashboard or selected-widget scope and supported period | query time overrides with explicit scope |
| restore_review | authorized restoreSteps | exact stored definition, never model reconstruction |

This table is the proposed v1 contract, not13 newly implemented runtime tools.
Return a single operation envelope through the existing planning adapter; do not
expose every REST endpoint as a tool. `ALL` applies only to a setting supported by
every target; for map-only settings select map keys explicitly. Reject contradictory
same-property commands rather than rely on object order or last-write-wins.

## Selection and reuse
`widgetKey` resolves only within the actual selected base review. `dataRef`
resolves only within the current authorized metadata snapshot. Stable resource
identity is resolved server-side and never guessed from a displayed label.
Commands that create or reuse a widget return an operation-local reference so a
later placement can target the selected result without asking the model to mint
IDs. Resolve references in a bounded acyclic sequence; forward/cyclic/missing
references fail with typed diagnostics.

For show_current/history/map, match by semantic role, device/source, exact field/
metric and relevant period/view semantics. Reuse only a unique compatible match;
honor an explicit target. Distinct periods or intentionally separate widgets are
not collapsed. Ambiguous matches require a meaningful target choice. Never turn
a history into a current value or a note into measured data as a convenience.
Create a missing widget with a server key stable within the persisted turn, so
repair/retry does not create random new identities. Repeated show operations do
not duplicate an already satisfied unique role.

## Compilation boundary
Add focused operation parser, domain compiler and application coordinator rather
than growing `ActionPlanner` or `CreationTaskPlanning` into another monolith.
Place HTTP calls behind explicit adapters/ports. Reuse
`DashboardCompositionCompiler`, `DashboardIntentEdits`, `DashboardCreationCompiler`
and dashboard-service `DashboardCompositionPreparation` as lower-level seams.
Consolidate duplicate role/default rules into one implementation.

Pipeline: validated operation envelope -> resolved semantic targets -> pure
candidate intent + compiler-derived change mask + expected outcomes -> ordinary
authorized dashboard preparation -> candidate definition/digest -> outcome verifier
from the next change -> immutable reviewed plan. Domain objects contain no model
SDK, RestClient, JDBC or controller dependencies. Prepare remains read-only.

The model cannot supply raw widget queries, definition JSON, full widgetOrder,
arbitrary coordinates, API paths, account IDs or an unbounded settings bag. Initial
creation uses show/add operations over an empty base. Exact restoration is mutually
exclusive with other operations in a turn; the next user turn may edit the restored
review. Legacy stored executable actions keep their original compiled payload.

## Preservation and responsive geometry
Placement removes only the selected group from order and reinserts it at the
requested anchor while preserving every other widget's relative order. A top
row can push the map/history down. Required responsive reflow is permitted and
documented; unrelated type, IDs, binding, queries and presentation remain equal.
Settings/title-only edits permit no geometry change. Explicit sizing uses existing
FULL/HALF/THIRD and1-12 rows; requested percentages resolve against the actual base
size and supported minimum, and the UI reports quantization honestly.

Packing stays in dashboard-service with existing breakpoint rules and minimum
sizes. Where a packed creation intent cannot preserve an arbitrary prior layout,
reject unsupported geometry or introduce a bounded owning-service layout extension
with tests; do not silently repack everything while claiming exact preservation.
Never route around the ordinary layout/definition validator to make a case pass.

## Rollout and compatibility
Implement parse/compile under an opt-in planning protocol; compare candidates in
isolated evaluations first. Enable defaults only after outcome verification and
repair gates pass. Keep stored `compositionVersion:1` and template reviews readable
and executable; translate old continuation intent through one adapter. New models
do not silently fall back to raw full recomposition after a failed operation.
Rollback disables new generation while retaining readers for accepted v1 reviews.

## Verification and basis
Pure compiler/property tests cover untargeted equality, stable relative order,
idempotent reuse, operation conflicts, null/default resets, missing/cyclic refs,
foreign refs, size bounds and declared reflow at all breakpoints. Integration tests
prove preparation/digest parity and execution of legacy versus new reviewed tasks.
Replay compound real-model conversations with the shared corpus.
[tldraw actions](https://tldraw.dev/starter-kits/agent) provide an inspectable close
analogue; [OpenAI tool design](https://developers.openai.com/api/docs/guides/function-calling)
supports moving known arguments and invalid-state prevention into application code.
