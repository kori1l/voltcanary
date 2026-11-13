# VoltCanary OpenSpec

Current requirements live under [specs](specs/). Product and architecture
policy lives in [project.md](project.md). Proposed implementation changes
live under [changes](changes/); historical snapshots stay under changes/archive.

## Programs

- [AI assistant orchestration](changes/README.md#ai-assistant-orchestration-program):
  proposed intents, dependent plans, server skills, bounded context, economical
  provider-neutral model routing and shared budgets. Six new changes plus existing
  verifier/recovery integration; implementation and live trials remain stopped.

- [Dashboard deletion](changes/add-dashboard-deletion/proposal.md):
  owner-only deletion and shared management confirmation implemented; Docker/browser
  verification is pending at the user's request. Active, not archived.

- [AI plan clarification](changes/archive/2026-10-21-add-ai-plan-clarification/proposal.md):
  reply and refinement controls on the page and drawer, persistent scoped continuation
  and small-model/browser recovery verified in Docker; archived.

- [Real-device AI handoff fixes](changes/archive/2026-10-21-fix-ai-real-device-handoff/proposal.md):
  quantity parsing, saved-plan navigation and real GPS source visibility verified
  in Docker and the demo owner's live HSL workflow; archived.

- [AI creation and identity hardening](changes/README.md#ai-creation-program):
  verified identity, isolated execution, typed devices and private template dashboards
  are implemented, tested with authenticated Docker/browser flows and archived.

- [Автопарк, поездки, воспроизведение и геозоны](changes/archive/2026-10-21-expand-vehicle-monitoring-console/proposal.md):
  implemented and verified in Docker/browser flows; archived 2026-10-21.

- [Базовая GPS-эмуляция автомобиля и карта](changes/archive/2026-10-21-add-vehicle-gps-simulation-map/proposal.md):
  implemented and verified in Docker/browser flows; archived 2026-10-21.
- [UI/UX and user-created dashboards](changes/README.md): 16 proposed changes,
  dependency order, R01–R28 / DB-01–DB-43 / W01–W15 coverage and acceptance gates.

Run `npm.cmd run openspec:list` for current status. Before implementation use
`npm.cmd run openspec:apply -- <change-id>`, read its artifacts and follow the
project skill. Creation of a proposal is not completion of implementation tasks.
