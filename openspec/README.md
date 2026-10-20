# VoltCanary OpenSpec

Current requirements live under [specs](specs/). Product and architecture
policy lives in [project.md](project.md). Proposed implementation changes
live under [changes](changes/); historical snapshots stay under changes/archive.

## Active Program

- [Автопарк, поездки, воспроизведение и геозоны](changes/expand-vehicle-monitoring-console/proposal.md):
  расширение базового GPS-сценария, реализация и приёмка.

- [Базовая GPS-эмуляция автомобиля и карта](changes/add-vehicle-gps-simulation-map/proposal.md):
  proposed change; scope, design, delta requirements and pending implementation tasks.
- [UI/UX and user-created dashboards](changes/README.md): 16 proposed changes,
  dependency order, R01–R28 / DB-01–DB-43 / W01–W15 coverage and acceptance gates.

Run `npm.cmd run openspec:list` for current status. Before implementation use
`npm.cmd run openspec:apply -- <change-id>`, read its artifacts and follow the
project skill. Creation of a proposal is not completion of implementation tasks.
