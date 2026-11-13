# Change: Compile semantic dashboard operations into bounded edits

## Why
`DashboardIntentEdits` already preserves omitted fields, but the model still
chooses technical widget edits and can emit a syntactically valid wrong group,
order or data selection. Stable editing requires a narrow application command
interface with explicit preservation semantics.

## What Changes
- Add a versioned operation union for current values, histories, maps, placement,
  sizing, presentation, titles, data replacement, removal and exact restoration.
- Compile operations against the selected immutable review and resolved authorized
  data choices; reuse matching widgets and generate stable keys in code.
- Lower commands through the existing delta/preparation pipeline. The owning
  dashboard service remains responsible for definitions, bindings, query validation,
  responsive packing and digests.
- Switch new dashboard prompts behind a rollout gate; preserve stored legacy
  templates/compositions/deltas and confirmed execution contracts.

## Dependencies
Requires the evaluation baseline, durable dashboard task state and resolved widget
capabilities. This change takes over section12.1 and the compilation/prompt portion
of12.2/12.3 in `redesign-ai-assistant-dashboard-preview`.
Do not enable the new default path before `verify-ai-dashboard-edit-outcomes`
provides acceptance checks and the final repair/evaluation release gate passes.

## Ownership and impact
ai-agent-service domain/application code owns operation interpretation and stable
edits; HTTP adapters fetch metadata and call dashboard preparation. Frontend renders
the same reviewed definition. No arbitrary model query/layout/SQL execution, new
microservice, generic tool runtime or multi-agent orchestration is introduced.

## Out of scope
No rewrite of the widget engine, free-form generated React, AI publication/deletion,
automatic domain execution, or unrestricted source/resource editing. Initial
creation still ends as a private reviewed draft using current confirmation.

## Acceptance
The user's compound move/card/history request changes exactly the requested
semantics, reuses existing matching widget identities and preserves unrelated
settings and relative order. A malformed operation or unauthorized reference
cannot become a review. Existing legacy reviewed tasks continue to execute their
original immutable definitions without model reinterpretation.
