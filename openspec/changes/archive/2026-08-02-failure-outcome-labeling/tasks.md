## 1. Outcome Domain

- [x] 1.1 Define maintenance outcome entity and database migration.
- [x] 1.2 Define outcome DTOs and validation rules.
- [x] 1.3 Add outcome create/update/list APIs.
- [x] 1.4 Add audit metadata for outcome changes.
- [x] 1.5 Add device-service tests for outcome lifecycle.

## 2. Outcome Synchronization

- [x] 2.1 Define outcome event contract or internal reconciliation API.
- [x] 2.2 Project outcomes into analytics-service.
- [x] 2.3 Add idempotency and stale-event handling if events are used.
- [x] 2.4 Add reconciliation tests.

## 3. Label Taxonomy

- [x] 3.1 Extend ML label types for confirmed failure and feedback outcomes.
- [x] 3.2 Preserve backward compatibility for existing labels.
- [x] 3.3 Add database migrations and DTO updates.
- [x] 3.4 Add label audit tests for new label types.

## 4. Horizon Label Generation

- [x] 4.1 Define horizon label generation rules.
- [x] 4.2 Generate labels from outcomes for selected dataset versions.
- [x] 4.3 Store label generation lineage and source outcome ids.
- [x] 4.4 Prevent recent rows from being labeled normal before the horizon is observable.
- [x] 4.5 Add tests for positive, normal, maintenance-excluded, and unresolved cases.

## 5. Frontend And Docs

- [x] 5.1 Add outcome capture UI to maintenance workflows.
- [x] 5.2 Add label coverage and outcome summary to ML dataset detail.
- [x] 5.3 Add false-positive and normal-inspection feedback actions.
- [x] 5.4 Update API/event/operations docs.

## 6. Verification

- [x] 6.1 Run device-service tests.
- [x] 6.2 Run analytics-service label/outcome tests.
- [x] 6.3 Run frontend tests for outcome and label review workflows.
- [x] 6.4 Run `npm run openspec:validate`.
