## Context
The existing layout model pins a selected card relative to an anchor and resolves collisions downward within a section. Reuse this model for every interaction.

## Goals / Non-Goals
Provide discoverable mouse, touch and keyboard movement, preview and cancellation. Preserve the viewer and backend contracts.

## Decisions
Use a dedicated pointer handle; determine the destination from the nearest edge of another card. Highlight that edge and name the destination. Commit only on pointer release over a valid destination. Escape, pointer cancellation, loss of capture and leaving the canvas discard the move. Keep four visible buttons as the keyboard and single-pointer alternative. Prohibit impossible side placements with a visible explanation. No new component or chart dependencies.

## Risks / Trade-offs
Charts and text remain interactive because only the handle starts dragging. Touch scroll remains available outside the handle. Drag preview does not mutate the draft. Browser DOM automation can verify buttons and persistence; synthetic pointer tests verify drag state and cancellation without claiming physical touch hardware coverage.

## Migration Plan
No persisted schema migration. Existing coordinates remain valid.
