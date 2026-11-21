# Change: Conversational assistant with faithful dashboard preview

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Use the [current implementation order](../README.md#ai-first-execution-priority) for configurable scope. Live evaluation follows the owning change’s authorization and qualification gates.


Current status (2026-10-28): Shared preview/assistant baseline is implemented (35/42 tasks checked). Sections 11/12 track delegated acceptance. Close their tasks from the linked successor evidence during A5 integration and A6 qualification. Task 99.1 separately tracks configurable-resource alignment.

## Why
The assistant exposes planning controls instead of a continuous conversation. Dashboard reviews list panel names without showing the layout that will be saved, and follow-ups cannot express supported layout changes.

## What Changes
- A light, independent assistant overlay switches between floating, overlay sidebar and expanded modes without losing conversation or drafts. Floating/sidebar placement is configurable.
- One composer handles requests and clarification, retaining previous turns and readable execution results.
- Dashboard preparation accepts a bounded ordered composition from the actual widget catalogue, independently of templates. The owning dashboard service compiles, validates and digests the exact definition used for preview and creation. Legacy prepared template tasks remain executable.
- The assistant uses the ordinary dashboard canvas, widget renderer and read pipeline to preview that definition; no separate mock dashboard or fabricated readings.
- Versioned small-model instructions and fixtures describe preservation, ordering, removal, widths and clarification.
- Dashboard-owned widget capabilities supply typed settings and compatible visualizations to both the editor and AI; common title/header controls live in one General section for every widget.

## Follow-up ownership
The unverified recovery/semantic-refinement work in tasks11/12 is now detailed in
[the reliability program](../README.md#ai-dashboard-reliability-program). Those
successors extend the current implementation; this preview proposal's completed
work, compatibility and recorded acceptance remain the baseline. Successors own
any new migrations/contracts rather than inheriting the original no-migration scope.

## Impact
Frontend assistant/dashboard components, ai-agent-service planning, dashboard-service preparation/read-only preview, API documentation and focused verification. Existing authorization, publication and idempotency boundaries remain authoritative. No new dependencies or migrations.
