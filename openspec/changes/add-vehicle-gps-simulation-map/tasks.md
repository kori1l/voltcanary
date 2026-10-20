## 1. Контракт и устройство

- [ ] 1.1 Добавить VEHICLE отдельной новой Flyway seed-миграцией; проверить каталог и создание без обязательной локации, сохранив старые типы.
- [ ] 1.2 Закрепить имена/единицы/ranges четырёх GPS metrics, source identity и atomic pairing в ingestion/telemetry contracts; обеспечить совместимые catalogs.
- [ ] 1.3 Определить DTO positions/latest/history/internal dashboard evidence по design, ошибки, caps/asOf/freshness и правила доступа; покрыть граничные запросы.

## 2. Эмулятор GPS-датчика

- [ ] 2.1 Добавить VEHICLE_GPS, CITY_LOOP template и явную совместимость presets/profiles; сохранить appliance catalog/reset-template semantics.
- [ ] 2.2 Расширить YAML v1 model/parser/validator gps section и profile-scoped generated metrics; проверить ranges, неподдерживаемые поля/mode/protocol/anomalies и старые YAML.
- [ ] 2.3 Вынести выбор TelemetryGenerator из прямой зависимости run manager от GenericApplianceGenerator; реализовать focused VehicleGpsGenerator и локальный closed route.
- [ ] 2.4 Проверить geometry/time: first point, distance/speed/course, повороты/wrap, монотонный elapsed, реальные timestamps, задержки scheduler без burst.
- [ ] 2.5 Подключить source/profile/type compatibility, existing HTTP sender/token, leases/start/stop/history/failures; проверить stop/restart и отсутствие прямых telemetry DB/Kafka writes.

## 3. Принятая GPS-телеметрия и чтение

- [ ] 3.1 Добавить focused GPS envelope validation в ingress и telemetry: обе координаты, finite/ranges, optional speed/course; rejected broker input не создаёт позицию.
- [ ] 3.2 Добавить transactional telemetry_positions projection, portable/vendor migrations и индексы; подключить текущую retention/cleanup, проверить H2/PostgreSQL.
- [ ] 3.3 Проверить atomic source/timestamp pairing, duplicate event/source-time и first accepted conflict, out-of-order/backfill/latest ordering; исключить rejected raw readings.
- [ ] 3.4 Реализовать authenticated latest/history endpoints: ownership/source checks, `(from,to]`, asOf, 24h/1000 cap, null/no-data/freshness и quality metadata.
- [ ] 3.5 Реализовать bounded internal latest+history dashboard read через existing authorization/port; проверить чужой source/device/account и revoked access/cache.

## 4. Создание автомобиля и карта в карточке

- [ ] 4.1 Добавить VEHICLE в мастер и default VEHICLE_GPS/CITY_LOOP/HTTP, profile-aware presets и базовые interval/duration/speed controls; сохранить save/start как явные действия.
- [ ] 4.2 Сохранить setup checkpoint/retry/reconcile/token handling; проверить partial failure без duplicate device/source и переход profile без несохранённых mutations.
- [ ] 4.3 Добавить pinned MapLibre dependency/CSS и VITE_MAP_STYLE_URL wiring/env docs; проверить attribution, WebGL/style/tiles failure и текстовый fallback.
- [ ] 4.4 Создать общий VehicleMap без API calls: [lon,lat], маркер/heading, gap segments, latest text, Follow/manual pan, resize и cleanup.
- [ ] 4.5 Добавить focused «Движение» tab для VEHICLE, один source selector, 15m/1h/24h, 5s visible polling с единым asOf, cancellation/context guards и переход в Simulation.
- [ ] 4.6 Проверить no source/no-data/stale/refresh failure, null vs zero, один point, truncated и невозможный переход; подтвердить отсутствие GPS N+1 в inventory.

## 5. Карта как обычный виджет

- [ ] 5.1 Добавить vehicle-map v1/POSITIONS manifest и валидируемый single fixed device/source binding; reducers/несовместимые visualizations/dynamic fleet binding недоступны.
- [ ] 5.2 Подключить typed decoder/evidence projection/port/frontend DTO и общий renderer к batch/progressive queries; сохранить time/contextHash/access/filter/cache semantics.
- [ ] 5.3 Добавить выбор автомобиля/источника в palette/settings; сохранить source, initial Follow, layout/screen при undo/redo, draft reload, copy и publication.
- [ ] 5.4 Подключить existing refresh (5s доступен для demo), обеспечить cache freshness без отдельного widget polling; проверить inactive screen/unmount и изоляцию ошибки панели.
- [ ] 5.5 Проверить historical asOf, >24h typed limit, excluded scope, revoked grant, stale/no-data/truncated и resize/mobile без ломания соседних виджетов.

## 6. Документация и приёмка

- [ ] 6.1 Обновить current API/simulation docs и индексы фактически реализованными contracts, YAML, limitations, deployment order и style env; не описывать будущие фичи как работающие.
- [ ] 6.2 Создать current flow проверки базового GPS-сценария; добавить ссылки в flows index и релевантный docs index, записать evidence и непроведённые проверки.
- [ ] 6.3 Запустить focused backend tests, PostgreSQL migration/query verification и frontend typecheck/test/build; расширить проверки при shared/gateway изменениях.
- [ ] 6.4 Выполнить browser/stack acceptance: create/start/movement/stop/stale/restart/dashboard publication/tile failure/access и appliance regression.
- [ ] 6.5 Выполнить openspec:validate и structure:check, сверить proposal/design/specs/tasks/docs; проверить необходимость обновления AGENTS/skills только при изменении workflow.
- [ ] 6.6 Архивировать change только после завершения реализации и всех обязательных проверок; generated artifacts/logs не включать в commit.
