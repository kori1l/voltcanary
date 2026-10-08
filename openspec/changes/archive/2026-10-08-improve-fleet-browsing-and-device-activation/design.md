# Пагинация, экономный поиск и первое измерение: design

## Context And Goal

Devices/ML selectors ограничены первыми 100, Maintenance первыми 200; каждый символ повторяет справочники/signals. Add device закрывается до понятного подключения sender.

Coverage: R06, R07, R08, R18. Implemented and acceptance-checked on 2026-10-08.
Матрица владения и порядок поставки: [program index](../README.md).

## Pagination
Сохранить existing list contracts; offset-capable Devices/Locations/Maintenance использовать с stable ordering. Alerts используют уже существующий `/alerts/page` с page/size, totalElements/totalPages/hasNext. Старый `/alerts` сохраняет array payload. Новый cursor endpoint не понадобился. Total только authoritative, hasMore via server/page overfetch; new device101 и operation201 достижимы. Scope counts из dashboard query engine, не суммирование страниц UI.
## Requests
300ms debounce search, explicit reset и contract-defined status options. AbortSignal проходит domain API client; references keyed independent scope кешируются отдельно. Visible signals concurrency ≤6 calls либо authorised batch endpoint, не 200 одновременных reads.
## Activation
Create server ID остаётся canonical continuation. API/MQTT карточка даёт endpoint/topic и reviewed credential action, ожидает telemetry read. Simulation status displayed as returned. Refresh/timeouts не recreates device/source; ambiguous mutation outcome объясняется отдельно.

## Failure Modes And Compatibility

Implementation обязана сохранить canonical IDs, tenant/account lifecycle и server
resource checks. Данные, доступ к которым отклонён сервером, не попадают в URL, cache, local drafts,
telemetry logs или screenshots при верификации. Existing routes/API не меняются
несовместимо без отдельно указанной migration; new fields/endpoints additive.
Late responses cannot replace the selected context. UI completion is not evidence
of runtime delivery, inference, production readiness or measured performance.

## Verification And Rollout

Scenarios в specs/**/spec.md — обязательная приёмка, tasks.md остаётся unchecked
до кода и доказательств. Начать с focused tests, затем browser/integration на
реальных доступных records для затронутых flows. Документировать exercised scope,
normal/loading/empty/partial/error/stale/no-access и ограничения; generated evidence
оставлять в ignored artifacts. Встраивать feature постепенно, сохраняя working
fallback. Не проводить load claims на основании build или одного smoke.
