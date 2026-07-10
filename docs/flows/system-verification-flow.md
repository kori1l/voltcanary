# VoltCanary System Verification Flow

Цель документа - дать ручной сценарный план проверки текущей системы VoltCanary и описать, как она должна работать в идеале. Документ ориентирован на проверку через UI `http://localhost:3000`, API Gateway `http://localhost:8080`, прямые service endpoints для диагностики и логи Docker Compose.

## 0. Базовые правила проверки

- Внешний клиент и frontend должны ходить через API Gateway, а не напрямую в backend-сервисы.
- Все защищенные пользовательские API должны требовать `Authorization: Bearer <accessToken>`.
- Gateway должен удалять клиентские `X-User-*` headers и выставлять их только из валидного JWT.
- Сервисные internal endpoints должны быть доступны только с `X-Internal-Service-Token` и только внутри доверенной сети.
- Данные устройств, телеметрии, аналитики и алертов должны быть изолированы по владельцу/account.
- Ошибки в UI должны быть понятными, без stack trace и внутренних URL.
- В логах не должны появляться JWT, refresh tokens, source tokens, webhook secrets, passwords или inline credentials.
- При любом сбое downstream-сервиса публичный ответ должен быть контролируемым `503`, а не зависший запрос или raw exception.
- Kafka/Redpanda flow считается at-least-once: дубли допустимы на transport-уровне, но бизнес-результат должен быть идемпотентным.

## 1. Подготовка окружения

### 1.1 Старт локальной системы

Шаги:

1. Запустить инфраструктуру и приложения: `docker compose --profile app up -d`.
2. Проверить контейнеры: `docker compose ps`.
3. Проверить frontend: открыть `http://localhost:3000`.
4. Проверить gateway health: `GET http://localhost:8080/actuator/health`.
5. Проверить backend service health напрямую при необходимости:
   - `http://localhost:8081/actuator/health`
   - `http://localhost:8082/actuator/health`
   - `http://localhost:8083/actuator/health`
   - `http://localhost:8084/actuator/health`
   - `http://localhost:8085/actuator/health`
   - `http://localhost:8086/actuator/health`
   - `http://localhost:8087/actuator/health`
   - `http://localhost:8088/actuator/health`
   - `http://localhost:8090/health`

Ожидаемо:

- Все нужные сервисы `healthy` или отвечают `UP`.
- Frontend открывается без white screen.
- В console browser нет React key warnings, routing warnings и uncaught runtime exceptions.
- В логах нет постоянных restart loops.

### 1.2 Проверка gateway routing

Шаги:

1. Без токена вызвать `GET /devices` через gateway.
2. Без токена вызвать `POST /auth/login`.
3. С невалидным токеном вызвать `GET /devices`.
4. С валидным токеном вызвать `GET /devices`.

Ожидаемо:

- Защищенный route без токена возвращает `401` с кодом уровня gateway, например `missing_token`.
- Public auth routes доступны без access token.
- Невалидный token возвращает `401`, а не `500`.
- Валидный token проходит, backend получает корректные `X-User-*` от gateway.

## 2. Auth и аккаунт пользователя

### 2.1 Регистрация нового пользователя

Шаги:

1. Открыть `/register`.
2. Зарегистрировать новый email с сильным паролем.
3. Убедиться, что после регистрации пользователь автоматически логинится или может войти вручную.
4. Открыть `/devices`.

Ожидаемо:

- Пользователь создан со статусом, разрешающим вход.
- Access и refresh tokens сохранены frontend-клиентом.
- Пользователь попадает в защищенную часть UI.
- Device account создается лениво при первом обращении к account/device API.

### 2.2 Валидация регистрации

Сценарии:

- Повторная регистрация того же email.
- Слабый пароль.
- Некорректный email.
- Пустые обязательные поля.

Ожидаемо:

- API возвращает `400` или конфликтный бизнес-код без stack trace.
- UI показывает понятную ошибку.
- Дубликат пользователя не создается.

### 2.3 Login/logout/refresh

Сценарии:

- Успешный login.
- Login с неверным паролем.
- Несколько неверных login подряд.
- Автоматический refresh после истечения access token.
- `POST /auth/logout`.
- `POST /auth/logout-all`.

Ожидаемо:

- Успешный login возвращает access и refresh tokens.
- Неверный пароль дает generic error, без раскрытия существования email.
- Brute-force защита ограничивает повторные попытки.
- Refresh доступен через gateway без валидного access token.
- Reuse старого revoked refresh token отклоняется.
- Logout ревокает refresh token, последующий refresh не проходит.
- Logout-all ревокает все активные сессии пользователя.

### 2.4 Password reset и email verification

Сценарии:

- Request forgot-password для существующего email.
- Request forgot-password для несуществующего email.
- Reset с валидным token.
- Reset с истекшим/невалидным token.
- Request email verification.
- Confirm email verification.

Ожидаемо:

- Public ответ для forgot-password не раскрывает существование email.
- Валидный reset меняет пароль и ревокает активные refresh sessions.
- Невалидный token не меняет пароль.
- Email verification меняет `email_verified`/статус согласно auth contract.

### 2.5 Admin auth workflows

Сценарии:

- USER открывает admin endpoint.
- ADMIN открывает список пользователей.
- ADMIN меняет роль пользователя на `SUPPORT` или `ADMIN`.
- ADMIN меняет статус пользователя на `DISABLED` или `LOCKED`.
- ADMIN смотрит и ревокает refresh sessions пользователя.

Ожидаемо:

- USER получает `403` на `/auth/admin/**`.
- ADMIN получает список и может менять роль/статус.
- Disabled/locked пользователь не может login/refresh.
- Смена статуса синхронизирует account lifecycle в device-service, где это применимо.

## 3. Device inventory и account boundary

### 3.1 Первый вход и пустое состояние

Шаги:

1. Войти новым пользователем.
2. Открыть `/devices`.
3. Открыть `/dashboard`.
4. Открыть `/alerts`.

Ожидаемо:

- Пустые состояния отображаются корректно.
- Нет бесконечных loaders.
- Нет `Cannot read property ...`, React key warnings или broken layout.
- Account создается автоматически через `/accounts/current`.

### 3.2 Locations

Сценарии:

- Создать location.
- Создать вложенный location с `parentId`.
- Отфильтровать/найти location по search.
- Обновить name/building/floor/room/zone/tags.
- Попробовать сделать location parent самому себе.
- Удалить location.

Ожидаемо:

- Location принадлежит текущему account.
- Search работает по name/building/floor/room/zone.
- Self-parent запрещен.
- Удаление не ломает список устройств; зависимые ограничения обрабатываются явно.

### 3.3 Device lifecycle

Сценарии:

- Создать устройство с name/type/location/manufacturer/model.
- Создать устройство с metadata без secret keys.
- Попробовать metadata с `password`, `token`, `secret`, `credential`.
- Открыть карточку устройства `/devices/{id}`.
- Обновить устройство.
- Disable device.
- Enable device.
- Decommission device.
- Delete device.

Ожидаемо:

- Устройство появляется в списке и dashboard.
- Secret-like metadata отклоняется.
- `DISABLED` устройство не должно принимать нормальную ingestion telemetry.
- `DECOMMISSIONED` устройство остается в истории, но не считается активным источником новой телеметрии.
- Delete является archive/decommission, не hard delete исторических telemetry dependencies.

### 3.4 Cross-account isolation

Шаги:

1. Создать User A и Device A.
2. Создать User B.
3. Под User B открыть `/devices/{deviceA}`.
4. Под User B запросить telemetry/analytics/alerts для Device A.

Ожидаемо:

- User B получает `404` или `403` согласно endpoint policy.
- Device A не отображается в списках User B.
- Telemetry, analytics и alerts Device A недоступны User B.

### 3.5 CSV import/export устройств

Сценарии:

- Export devices CSV.
- Import валидного CSV `name,type,locationId,manufacturer,model`.
- Import CSV с неправильным header.
- Import CSV с одной невалидной строкой среди валидных.

Ожидаемо:

- Export содержит ожидаемые колонки.
- Валидный import создает все устройства.
- Невалидный header отклоняется.
- Import валидируется целиком до записи; если есть невалидная строка, частичных устройств быть не должно.

## 4. Data sources, sensors и ingestion tokens

### 4.1 Data source lifecycle

Сценарии:

- Создать source для устройства: `sourceType=SIMULATOR`, `protocol=REST`.
- Создать source с некорректным `externalId`.
- List/get source.
- Patch source metadata.
- Rotate source token.
- Revoke source token.
- Delete source.

Ожидаемо:

- `externalId` trim-ится, ограничен длиной и допустимыми символами.
- Raw token возвращается только один раз при rotation.
- В БД хранится только hash/prefix, не raw token.
- После revoke/delete ingestion с этим token не проходит.
- Historical telemetry не удаляется.

### 4.2 Data source CSV import/export

Сценарии:

- Export sources CSV.
- Import валидного CSV `externalId,sourceType,protocol`.
- Import с дубликатами.
- Import с невалидным protocol.

Ожидаемо:

- Export содержит `id,deviceId,externalId,sourceType,protocol,status`.
- Валидация all-or-nothing.
- Дубликаты и невалидные значения отклоняются понятно.

### 4.3 Sensors

Сценарии:

- Создать sensor с name/type/unit/metricMapping.
- Создать sensor с metadata.
- Обновить sensor.
- Удалить sensor.
- Попробовать открыть sensor из другого account.

Ожидаемо:

- Sensor scoped к устройству и account.
- Mapping сохраняется и отображается.
- Cross-account доступ запрещен.

## 5. Ingestion HTTP flow

### 5.1 Успешная realtime telemetry ingestion

Предусловия:

- Есть active device.
- Есть active data source.
- Есть валидный source token.

Шаги:

1. Отправить `POST /ingestion/readings` через gateway или прямой ingestion endpoint с `Authorization: Bearer <sourceToken>`.
2. Payload содержит `deviceId`, `sourceId`, текущий `timestamp`, metrics `activePowerW`, `voltageV`, `currentA`, `energyWh`, `rssi`, опционально `relayState`.
3. Подождать несколько секунд.
4. Проверить `/telemetry/devices/{deviceId}/latest`.
5. Проверить `/telemetry/devices/{deviceId}/readings?metric=activePowerW`.

Ожидаемо:

- Ingestion возвращает `202 accepted`.
- Событие попадает в `telemetry.raw.v1`.
- Telemetry-service сохраняет numeric metrics в TimescaleDB.
- Latest и readings показывают новое значение.
- Analytics получает `telemetry.validated.v1` для realtime accepted event.

### 5.2 Дубли ingestion

Сценарии:

- Повторить тот же payload с тем же device/source/timestamp/metric.
- Повторить batch с частично дублирующимися readings.

Ожидаемо:

- API возвращает `202` со статусом duplicate или duplicateCount.
- В TimescaleDB не появляется дубликат строки metric.
- Quality flags отражают duplicate behavior.
- Downstream analytics не должен создавать повторные бизнес-эффекты из дубля.

### 5.3 Validation negative cases

Сценарии:

- Нет `deviceId`.
- Нет `sourceId`.
- Нет `timestamp`.
- Нет `metrics`.
- Timestamp слишком далеко в будущем.
- Timestamp слишком старый для realtime endpoint.
- Unknown metric.
- `NaN`, `Infinity`, string вместо numeric metric.
- Out-of-range voltage/current/power/rss.
- Слишком много metrics.
- Слишком большой batch.
- Token не соответствует source/device.
- Token revoked.
- Device disabled/decommissioned.

Ожидаемо:

- Некорректный payload дает `400` или `401/403` для auth/source проблем.
- Broker failure дает `503`, клиент может retry.
- Bad realtime events не ломают consumer; malformed Kafka messages уходят в DLQ.
- В UI нет новых точек для rejected readings.

### 5.4 Import/backfill ingestion

Сценарии:

- Отправить historical reading в `/ingestion/imports/readings`.
- Отправить batch historical readings.
- Проверить telemetry readings/export.
- Проверить analytics realtime side effects.

Ожидаемо:

- Backfill проходит отдельный import endpoint.
- Telemetry сохраняется и получает quality flag `BACKFILL`.
- Backfilled events не публикуют realtime `TelemetryValidatedEvent`.
- Analytics/alerts не должны внезапно создавать realtime alert только из historical import, если policy не изменена.

## 6. Simulator flow

### 6.1 Создание simulated device через UI

Шаги:

1. Открыть `/devices`.
2. Создать устройство или использовать existing active device.
3. Открыть device details.
4. Перейти на tab `Simulation`.
5. Создать/сбросить config template.
6. Выбрать profile и protocol.
7. Проверить sourceId, interval, duration, speed, voltage, RSSI, idle/active power.
8. Нажать validate.
9. Нажать save.
10. Нажать start.

Ожидаемо:

- Config создается через `/simulation/devices/{deviceId}/config`.
- Validate показывает валидный YAML.
- Start создает run со статусом `RUNNING`.
- `generatedPoints` растет.
- В telemetry charts начинают появляться новые точки.

### 6.2 Stop/restart simulator

Сценарии:

- Stop running simulation.
- Повторный stop.
- Повторный start после stop.
- Start при уже running simulation.
- Restart simulator-service container во время running simulation.

Ожидаемо:

- Stop переводит run в `STOPPED`.
- Повторный stop идемпотентен или возвращает понятный current state.
- Нельзя получить два активных run для одного device.
- После рестарта simulator-service stale DB run не должен оставаться `RUNNING`, если in-memory scheduled task потерян.
- UI status не должен показывать фальшивый `RUNNING`, если генерация не идет.

### 6.3 Simulator anomaly profiles

Сценарии:

- Normal profile без anomalies.
- Power spike enabled.
- Offline period enabled.
- Growth enabled.
- Некорректные duration/range values.
- Невалидный YAML руками.

Ожидаемо:

- Normal profile генерирует стабильную telemetry.
- Power spike виден на activePower chart и может привести к anomaly/alert.
- Offline period приводит к stale/offline telemetry status.
- Growth виден как trend/consumption growth.
- Невалидный YAML не сохраняется и не стартует.

## 7. Telemetry UI и chart behavior

### 7.1 Latest telemetry и metric cards

Шаги:

1. Открыть device details.
2. Проверить overview/telemetry sections.
3. Запустить simulator и подождать точки.
4. Проверить metrics: activePowerW, voltageV, currentA, energyWh, rssi.

Ожидаемо:

- Latest readings обновляются после ingestion.
- Cards показывают unit, min/max/average/latest, status.
- Если данных нет, UI показывает пустое состояние, а не сломанный chart.
- Если telemetry stale, UI показывает stale/warning status.

### 7.2 Time range scenarios

Сценарии:

- Выбрать presets: короткий диапазон, час, день, широкий диапазон.
- Выбрать custom range с нормальными данными.
- Выбрать custom range без данных.
- Выбрать слишком маленький range меньше минуты.
- Выбрать range, где `from > to`.
- Быстро менять ranges.
- Drag-select на chart очень маленький участок.
- Drag-select пустой участок.

Ожидаемо:

- Chart не ломает страницу.
- Минимальный custom/selection range нормализуется до безопасного интервала.
- Empty range показывает `No samples in selected range` или аналогичное состояние.
- Threshold lines остаются уникальными по React keys; нет warning `Encountered two children with the same key`.
- Axes/thresholds/legend отображаются стабильно.
- Нет synthetic fake zeros для пустых buckets.

### 7.3 Metrics API

Сценарии:

- `GET /telemetry/devices/{deviceId}/metrics` после отсутствия данных.
- `GET /telemetry/devices/{deviceId}/metrics` после появления данных.
- `GET /telemetry/devices/{deviceId}/quality`.
- `GET /telemetry/devices/{deviceId}/readings/export` с cursor pagination.
- Запрос range больше max chart range.
- Запрос unknown metric.

Ожидаемо:

- Metrics endpoint показывает только доступные/present metrics или корректный empty state.
- Quality возвращает recent flags.
- Export page возвращает `nextCursor` до конца данных.
- Слишком широкий chart range ограничивается/отклоняется контролируемо.
- Unknown metric возвращает `400`, не `500`.

## 8. Analytics flow

### 8.1 Baseline/features/anomalies/risk

Предусловия:

- Есть устройство с достаточной telemetry.

Сценарии:

- `GET /analytics/devices/{deviceId}/baseline`.
- `GET /analytics/devices/{deviceId}/features`.
- `GET /analytics/devices/{deviceId}/anomalies`.
- `GET /analytics/devices/{deviceId}/risk`.
- `GET /analytics/devices/{deviceId}/recommendations`.
- `GET /analytics/devices/{deviceId}/predictions/latest`.

Ожидаемо:

- До достаточных данных API возвращает понятное empty/insufficient-data состояние.
- После telemetry появляются baseline/features.
- Power spike/long cycle/growth создают anomaly signals.
- Risk score находится в диапазоне 0-100.
- Risk level соответствует шкале LOW/MEDIUM/HIGH/CRITICAL.
- Recommendations объясняют next action без заявления точного диагноза поломки.

### 8.2 Manual recalculate

Сценарии:

- Нажать recalculate в UI.
- Проверить `/analytics/devices/{deviceId}/jobs`.
- Открыть конкретный job.
- Повторно нажать recalculate быстро несколько раз.

Ожидаемо:

- Создается analytics job.
- Job проходит статусы до completed/failed.
- UI показывает progress/current state.
- Повторные recalculate не создают неконтролируемую очередь дублей или корректно отображаются как отдельные jobs.

### 8.3 ML dependency

Сценарии:

- ML service доступен.
- ML service недоступен.
- ML inference возвращает низкую confidence.
- ML inference получает maintenance `excludedIntervals`.

Ожидаемо:

- При доступном ML analytics сохраняет `modelName`, `modelVersion`, `predictionStatus`, scores, `signals`, explanations, recommended actions и `inputHash`.
- При normal simulator telemetry prediction имеет `BASELINE_HEURISTIC` или `MODEL`, `LOW`/`MEDIUM` risk и читаемые explanations.
- При power spike / voltage anomaly / current anomaly `anomalyScore` выше normal baseline, risk повышается до `MEDIUM`/`HIGH`/`CRITICAL`, а `signals` указывает метрику.
- При недоступном ML system degradation controlled: analytics job завершается, persisted prediction получает `UNAVAILABLE`, rule-based anomaly/risk/recommendation logic продолжает работать, backend не падает `500`.
- Confidence отображается и снижается при отсутствии baseline/recent window или при maintenance exclusions.
- `excludeFromMl=true` windows доходят до ML как `excludedIntervals`; если useful ML input подавлен maintenance, prediction status отражает suppression/degraded behavior.

## 9. Alerts flow

### 9.1 Alert creation from analytics event

Предусловия:

- Simulator генерирует anomaly или analytics publish event.

Шаги:

1. Включить scenario power spike/growth.
2. Дождаться analytics event.
3. Открыть `/alerts`.
4. Открыть device details alert section.
5. Запросить `/alerts/summary`.

Ожидаемо:

- Alert-service создает alert из `anomaly.detected.v1` или `risk-score.changed.v1`.
- Alert содержит deviceId, accountId, alertType, severity, status `OPEN`, source/correlation fields где доступны.
- `/alerts/summary` возвращает counts by status/severity/device.
- Static routes `/alerts/summary`, `/alerts/page`, `/alerts/support/search`, `/alerts/notification-channels`, `/alerts/notification-preferences` не должны попадать в `/alerts/{id}`.

### 9.2 Alert list/filter/page

Сценарии:

- Список alerts без фильтров.
- Фильтр по deviceId.
- Фильтр по status.
- Фильтр по severity.
- Фильтр по alertType.
- Фильтр по from/to.
- `GET /alerts/page?page=0&size=50`.
- Некорректный UUID/filter/page/size.

Ожидаемо:

- Список newest first.
- Фильтры комбинируются.
- Pagination metadata считается после ownership filtering.
- Некорректные фильтры возвращают `400`, не ломают UI.

### 9.3 Ack/resolve lifecycle

Сценарии:

- Acknowledge OPEN alert.
- Повторный acknowledge.
- Resolve ACKNOWLEDGED alert с reason.
- Resolve OPEN alert.
- Resolve с пустым reason.
- Открыть alert events/history если доступно.

Ожидаемо:

- OPEN -> ACKNOWLEDGED выставляет `acknowledgedAt` и `acknowledgedBy`.
- Повторный ack идемпотентен.
- Resolve выставляет `RESOLVED`, `resolvedAt`, `resolvedBy`, `resolutionReason`.
- Пустой reason сохраняется как default reason.
- Events имеют consistent payload и actor id.

### 9.4 Deduplication и reopen policy

Сценарии:

- Два одинаковых analytics event с одинаковым sourceEventId.
- Несколько events одного device/type/severity в один час.
- Event после resolved alert в том же condition window.
- Event другого severity/type.

Ожидаемо:

- Same sourceEventId возвращает существующий alert.
- Active unresolved condition дедуплицируется по `deviceId:alertType:severity:yyyyMMddHH`.
- После `RESOLVED` новый event создает новый `OPEN` alert.
- Другой type/severity создает отдельный alert.

### 9.5 Escalation

Сценарии:

- Оставить `WARNING` OPEN дольше threshold.
- Оставить `HIGH` OPEN дольше threshold.
- Оставить `CRITICAL` OPEN дольше threshold.
- Проверить повторный scan.

Ожидаемо:

- Alert остается `OPEN`.
- В `alert_events` появляется один `ESCALATED` event.
- Повторный scan не создает дубликаты escalation для одного alert.
- Escalation trigger-ит notification delivery pass.

### 9.6 Notification channels

Сценарии:

- `GET /alerts/notification-channels` на пустом account.
- Создать `WEBHOOK` channel.
- Создать `EMAIL`, `TELEGRAM`, `SLACK`, `SMS`, `PUSH` channel с provider-neutral URL config, если UI/API позволяет.
- Попробовать положить secret в `config`, например `token` или `password`.
- Disable channel.
- Enable channel.
- Update channel.
- Delete channel.

Ожидаемо:

- Пустой список возвращается без `400`.
- Channel scoped by account.
- `credentialsRef` разрешен; inline secret-like config запрещен.
- Disabled channel не получает delivery или получает attempt `DISABLED` согласно policy.
- Delete удаляет/скрывает channel из списка текущего account.

### 9.7 Notification preferences

Сценарии:

- Получить default preferences.
- Сохранить enabled=true, severities WARNING/HIGH/CRITICAL.
- Ограничить channelTypes.
- Ограничить deviceIds.
- Настроить quiet hours `22:00:00` - `07:00:00` timezone `Europe/Belgrade`.
- Проверить `criticalBypassQuietHours=true`.
- Передать невалидный timezone/time/severity.

Ожидаемо:

- Empty severities/channelTypes/deviceIds означает all allowed.
- Quiet hours могут пересекать midnight.
- Non-critical alerts в quiet hours не отправляются.
- CRITICAL проходит quiet hours при bypass=true.
- Невалидные значения дают `400`.

### 9.8 Notification attempts

Сценарии:

- Создать alert при enabled channel.
- Проверить `GET /alerts/{id}/notification-attempts`.
- Provider endpoint отвечает `2xx`.
- Provider endpoint отвечает `500`.
- Provider endpoint timeout.
- Channel disabled.

Ожидаемо:

- Delivery async after commit; Kafka consumer не блокируется внешним provider.
- `SENT` при accepted provider response.
- `FAILED` или `RETRYING` при ошибке/timeout.
- Retry count и lastErrorCode обновляются.
- Secrets не логируются.

### 9.9 Support/admin alert search

Сценарии:

- USER вызывает `/alerts/support/search`.
- SUPPORT вызывает `/alerts/support/search`.
- ADMIN вызывает `/alerts/support/search`.
- SUPPORT фильтрует по accountId/deviceId/status/severity/type/date.

Ожидаемо:

- USER получает `403`.
- SUPPORT/ADMIN видят cross-account search.
- Filters работают и не нарушают pagination.

## 10. Dashboard и navigation

### 10.1 Dashboard happy path

Шаги:

1. Войти пользователем с несколькими устройствами.
2. Открыть `/dashboard`.
3. Проверить summary devices, latest telemetry, recent alerts.
4. Перейти с dashboard на device details и alerts.

Ожидаемо:

- Dashboard не падает при пустых данных.
- Latest telemetry отображает актуальные значения или stale/empty state.
- Recent alerts кликабельны.
- Navigation сохраняет auth state.

### 10.2 Routing и browser behavior

Сценарии:

- Hard refresh на `/devices/{id}`.
- Hard refresh на `/alerts`.
- Открыть неизвестный route.
- Back/forward browser navigation.
- Logout и back button.

Ожидаемо:

- Protected routes сохраняются после refresh при валидном token.
- Unknown route ведет на `/devices` для authenticated и `/login` для anonymous.
- После logout back не показывает protected data без token.
- Нет React Router future warnings в console.

## 11. AI Agent flow

### 11.1 Rules/default provider

Сценарии:

- Открыть `/ai-agent`.
- Создать plan из prompt, например `Create a fridge simulator with HTTP telemetry and normal power cycle.`
- Посмотреть available tools.
- Execute plan.

Ожидаемо:

- Provider status отображается.
- Plan содержит понятные steps и planned tool calls.
- Execute выполняет только разрешенные операции.
- Ошибки tool calls показываются пользователю, а не ломают страницу.

### 11.2 Model provider settings

Сценарии:

- Открыть `/ai-agent/settings`.
- Получить provider capabilities.
- Создать model provider.
- Test provider.
- Rotate secret.
- Добавить model config.
- Set default provider/model.
- Delete provider/model.

Ожидаемо:

- Secrets не возвращаются обратно в UI/API.
- Rotate secret не логирует raw secret.
- Default provider/model явно меняется.
- Delete не оставляет UI в inconsistent state.

## 12. ML Service direct verification

Сценарии:

- `GET /health`.
- `POST /inference` с normal telemetry payload: voltage/current/power/energy в ожидаемых диапазонах.
- `POST /inference` с anomalous telemetry payload: power spike, voltage anomaly или current anomaly.
- `POST /inference` с пустыми metrics.
- `POST /inference` с `NaN`/`Infinity`.
- `POST /train`.
- `GET /runs/{run_id}`.
- `GET /models`.
- Невалидный payload.

Ожидаемо:

- Health отвечает быстро.
- Normal inference возвращает `predictionStatus=BASELINE_HEURISTIC` или `MODEL`, низкий/средний risk, explanations и `inputHash`.
- Anomalous inference возвращает более высокий `anomalyScore`, risk не ниже `MEDIUM` для severe выбросов, и signal по влияющей метрике.
- Empty metrics возвращает `INSUFFICIENT_DATA` с низкой confidence, а не fake low-risk success.
- Non-finite metric values дают `400`, не `500`.
- Inference сохраняет backward-compatible поля `deviceId`, `modelVersion`, `anomalyScore`, `degradationScore`, `riskLevel`, `confidence`, `explanations`.
- `/models` показывает active baseline heuristic model name/version/type/status.
- Train создает run или mock/stub run в текущем режиме.
- Невалидный payload дает `422/400`, не `500`.

## 13. Observability и operational checks

### 13.1 Logs

Сценарии:

- Проверить логи gateway при запросах.
- Проверить логи ingestion -> telemetry -> analytics -> alert для одного correlation id.
- Проверить rejected auth/ingestion.

Ожидаемо:

- Logs structured enough: method, path, status, duration, correlationId.
- Lifecycle actions alerts/devices содержат actor/user/account/target ids.
- Нет sensitive data.
- Error logs содержат reason category, но публичный ответ не раскрывает internals.

### 13.2 Metrics

Сценарии:

- Открыть actuator/prometheus для Java services.
- Проверить gateway request metrics.
- Проверить device metrics.
- Проверить telemetry consumed/inserted/invalid metrics.
- Проверить alert lifecycle/notification metrics.
- Открыть Grafana/Prometheus если observability profile запущен.

Ожидаемо:

- Метрики доступны и имеют полезные tags.
- Request/error/latency видны по routes/services.
- Notification attempts/success/failure/retry считаются.

### 13.3 Consumer lag и DLQ

Сценарии:

- Остановить telemetry-service, продолжить ingestion.
- Запустить telemetry-service обратно.
- Отправить malformed Kafka event или invalid input path, если доступен тестовый publisher.

Ожидаемо:

- Ingestion не блокируется бесконечно на analytics/telemetry.
- После восстановления telemetry-service догоняет backlog.
- Poison message не останавливает consumer навсегда; уходит в DLQ.
- Lag/consumer failure metrics отражают ситуацию.

## 14. Failure scenarios

### 14.1 Downstream service unavailable

Сценарии:

- Остановить device-service, открыть `/devices`.
- Остановить telemetry-service, открыть device telemetry tab.
- Остановить alert-service, открыть `/alerts`.
- Остановить ml-service, открыть analytics/prediction.

Ожидаемо:

- Gateway/service returns controlled `503` или UI-level empty/error state.
- UI не ломается и не уходит в бесконечный loader.
- После восстановления сервиса повторный refresh работает.

### 14.2 Database unavailable

Сценарии:

- Остановить PostgreSQL.
- Остановить TimescaleDB.
- Открыть affected pages.
- Вернуть DB.

Ожидаемо:

- Affected services становятся not ready или возвращают controlled error.
- Нет silent data loss.
- После восстановления сервисы reconnect-ятся или требуют явного restart с понятной диагностикой.

### 14.3 Redpanda unavailable

Сценарии:

- Остановить Redpanda.
- Отправить ingestion reading.
- Вернуть Redpanda.

Ожидаемо:

- Ingestion возвращает `503` при невозможности publish и не сообщает fake success.
- После восстановления publish работает.
- Дубликаты после client retry не создают duplicate telemetry rows.

### 14.4 Redis unavailable

Сценарии:

- Остановить Redis.
- Проверить login rate limiting/idempotency/ingestion behavior.

Ожидаемо:

- Если Redis включен как dependency, система деградирует контролируемо.
- Если предусмотрен in-memory fallback, он явно логируется и не нарушает безопасность критичных операций.

## 15. Security сценарии

### 15.1 Header spoofing

Сценарии:

- Клиент отправляет валидный token User A и `X-User-Id` User B.
- Клиент без token отправляет `X-User-Id`.
- Клиент пытается вызвать internal endpoint через gateway.

Ожидаемо:

- Gateway игнорирует/перезаписывает spoofed headers.
- Без token protected route возвращает `401`.
- Internal routes не доступны публично или требуют internal token и network boundary.

### 15.2 JWT edge cases

Сценарии:

- Expired access token.
- Wrong issuer.
- Wrong audience.
- Wrong token type.
- Invalid signature.
- Unknown `kid`.
- Missing `sub`.

Ожидаемо:

- Все invalid token cases дают `401`.
- Gateway audit log содержит reason category.
- UI пробует refresh только когда это безопасно, затем logout/redirect при failure.

### 15.3 Tenant isolation

Сценарии:

- User B угадывает UUID device/location/source/sensor/alert User A.
- User B угадывает alert id User A и пытается ack/resolve.
- User B запрашивает telemetry readings Device A.
- User B запрашивает analytics Device A.

Ожидаемо:

- Доступ запрещен в каждом сервисе, не только на frontend.
- Mutations не проходят.
- Audit/metrics фиксируют rejected ownership checks.

### 15.4 Secret handling

Сценарии:

- Source token rotation.
- Notification channel create/update with credentialsRef.
- AI provider secret rotation.
- Попытка сохранить secret-like keys в device metadata/notification config.

Ожидаемо:

- Raw secrets возвращаются только в момент создания/rotation, если это необходимо.
- Secret-like config keys rejected.
- Logs/API responses не содержат raw secrets.

## 16. Data retention и cleanup

Сценарии:

- Создать resolved alert.
- Создать alert events.
- Создать notification attempts.
- Настроить короткие TTL в local env для проверки cleanup.
- Дождаться scheduled cleanup или вызвать maintenance path, если есть.

Ожидаемо:

- Resolved alerts удаляются/архивируются после TTL.
- Alert events живут дольше alerts согласно policy.
- Notification attempts чистятся отдельно.
- Open/acknowledged unresolved alerts не удаляются retention job.

## 17. Полный golden path

Шаги:

1. Зарегистрировать User A.
2. Создать location.
3. Создать device.
4. Создать simulator data source и token.
5. Создать simulator config.
6. Запустить simulator normal profile.
7. Убедиться, что telemetry latest/readings/charts обновляются.
8. Проверить dashboard summary.
9. Запустить power spike scenario.
10. Дождаться analytics anomaly/risk.
11. Убедиться, что появился alert.
12. Проверить `/alerts/summary` и `/alerts` filters.
13. Создать notification channel и preferences.
14. Повторить alert-generating scenario и проверить notification attempts.
15. Acknowledge alert.
16. Resolve alert с reason.
17. Проверить, что новый повтор anomaly после resolve создает новый alert.
18. Stop simulator.
19. Проверить, что telemetry eventually становится stale/offline по UI rules.
20. Logout.

Ожидаемо:

- Вся цепочка работает без ручного вмешательства в БД.
- UI показывает данные по мере поступления.
- Eventual consistency допустима, но задержки должны быть объяснимыми: ingestion сразу `202`, telemetry через несколько секунд, analytics/alerts после consumer processing.
- Нет broken pages, duplicate key warnings, stale fake running simulations или raw backend errors в UI.

## 18. Быстрый smoke checklist перед демонстрацией

- Frontend открывается, login работает.
- `/devices` показывает список или пустое состояние.
- Можно создать device и открыть details.
- Simulation config validate/save/start работает.
- Telemetry charts показывают точки после старта simulator.
- Custom chart range без данных не ломает страницу.
- `/alerts` открывается без `400` на `/alerts/summary`.
- `/alerts/notification-channels` открывается без `400`.
- Alert ack/resolve работает на существующем alert.
- `/dashboard` не падает при пустых и заполненных данных.
- Logout возвращает на login, protected pages закрыты.

## 19. Критерии готовности системы

Система считается работающей корректно для текущего этапа, если:

- Пользователь может пройти golden path через UI без Postman/ручной БД.
- Все protected данные scoped by account.
- Ingestion -> Kafka -> Telemetry -> Analytics -> Alerts работает end-to-end.
- Empty states, stale data, no-data chart ranges и downstream failures отображаются контролируемо.
- Alert lifecycle, deduplication, reopen, escalation, notification settings и attempts соответствуют documented policy.
- Логи и метрики позволяют найти проблему по service/path/correlationId.
- Security edge cases не приводят к cross-tenant access или leakage secrets.
