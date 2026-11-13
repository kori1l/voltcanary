# Change: Resolve dashboard data capabilities before widget composition

## Why
The shared widget descriptor already exposes settings, supported value types and
visualizations. It does not by itself guarantee that the model selects a recorded
door field instead of inventing a registered numeric metric. Current compilation
mixes catalogue lookup, normalization and presentation. Compatibility should be
resolved against actual authorized source metadata in application code.

## What Changes
- Introduce a read-only dashboard-owned resolver joining widget descriptors with
  actual selected device/source/field/metric metadata and supported view modes.
- Produce bounded choices with explicit semantics, units and availability states;
  the model uses task-local choice refs instead of guessing source/metric keys.
- Distinguish unsupported data, missing samples, stale observations, ambiguity and
  denied access. Never turn missing data into a successful reading or zero.
- Reuse existing vehicle-value/timeline numeric/boolean rendering and settings;
  audit and close only actual gaps, retaining recorded list mode and gap semantics.

## Dependencies
Requires `establish-ai-dashboard-conversation-evals` and
`persist-ai-dashboard-task-state` for renewed selections and recovery integration.
Requires the implemented shared `WidgetCapabilities` catalogue; do not introduce
a second frontend/AI compatibility matrix. Takes over unfinished capability/state-
chart acceptance from section11 of the preview change, without claiming it was
wholly absent from code.

## Ownership and impact
dashboard-service owns compatibility and ordinary authorized preparation. Existing
device/telemetry APIs own resource/source/field facts; ai-agent-service translates
opaque refs and orchestrates. Frontend uses the same metadata/renderer contracts.
No ingestion schema, fabricated telemetry, new metric registry or new event topic.

## Out of scope
No HSL-specific mapping table, guessed units, arbitrary formulas, automatic source
creation or expansion to unsupported variable/query families. A new widget needed
by a case must be implemented through the normal widget contract, not simulated
with static notes or made-up numeric metrics.

## Acceptance
Door state can be shown as a current boolean and recorded graph using the actual
source field; next-stop text/identifier semantics are retained. Ambiguous sources
produce one meaningful choice, absent samples remain no-data and text history is
not silently converted into a numeric line. API, editor and AI agree on supported
settings and view modes for every offered widget type.
