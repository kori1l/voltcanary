# Дизайн базовой GPS-эмуляции и карты

Статус: базовый контракт реализован и проверен; последующие принятые расширения
сохраняют совместимость, как отмечено в разделе виджета ниже.

## 1. Текущая база и границы решения

Исследованная база:

- `frontend/src/devices/devicesApi.ts`: Device имеет type, manufacturer/model,
  metadata и nullable locationId; Sensor и DataSource уже отдельные понятия.
- `device-service/.../V003__seed_device_types_and_extend_devices.sql`:
  типы устройств — строки каталога, а не новый отдельный агрегат на каждый тип.
- `docs/current/simulation/frontend-flow.md`: устройство → SIMULATOR/REST
  источник → token → конфигурация → запуск.
- `SimulationConfigModel`, parser/validator/templates, `SimulationProfile`:
  YAML v1, каталог профилей и существующий lifecycle/config versions/runs.
- `SimulationRunManager`: сейчас напрямую использует GenericApplianceGenerator.
  Перед добавлением GPS выделить выбор реализации TelemetryGenerator.
- `TelemetryEventProcessor`: исходный TelemetryReading сохраняется до завершения
  validation; scalar measurements не представляют атомарную позицию.
- `WidgetManifests`, typed query engine и существующая публикация дашбордов:
  карта должна пройти эти границы, а не делать обходной fetch из renderer.

Один Device типа VEHICLE представляет автомобиль. GPS-датчик эмулируется
источником с профилем VEHICLE_GPS. Отдельная запись Sensor для каждого числового
поля не нужна: имеющийся pipeline не требует sensorId. Не создавать скрытые
sensor rows, новую сущность машины или универсальную систему capabilities.
Тип ограничивает первичный UI; доступность данных определяет ответ GPS API.

locationId остаётся организационным назначением (гараж/база), не текущими
координатами. GPS-движение не обновляет Device и не генерирует registry change
на каждом тике. Список устройств достаточно дополнить названием/иконкой типа;
не добавлять новый запрос GPS на каждую строку списка в этом change.

## 2. Конвейер и ответственность

```text
Device VEHICLE + DataSource SIMULATOR/REST
  → simulator-service / VEHICLE_GPS
  → ingestion-service (HTTP + existing source token)
  → telemetry.raw.v1
  → telemetry-service validation + atomic position projection
  → positions API / authorized internal dashboard read
  → shared map renderer in device tab or vehicle-map widget
```

Simulator производит данные и не пишет в telemetry DB/Kafka напрямую.
Ingestion сохраняет текущую source resolution/authentication/idempotency.
Telemetry владеет валидностью, хранением и чтением GPS. Dashboard выполняет
обычные проверки scope/revision/time/contextHash и получает typed evidence
через свой порт. Frontend отображает данные без фиктивных измерений.
Бизнес-правила — в focused application/domain collaborators, транспорт и SQL —
в adapters. Не расширять большие service/page файлы независимой логикой GPS.

## 3. Метрики и согласованность

| Поле metrics | Единица | Допустимые значения | Обязательность |
| --- | --- | --- | --- |
| latitude | deg | finite [-90, 90] | вместе с longitude |
| longitude | deg | finite [-180, 180] | вместе с latitude |
| speedKmh | km/h | finite [0, 300] | всегда в эмуляторе; опционально в GPS API |
| headingDeg | deg | finite [0, 360) | всегда в эмуляторе; опционально в GPS API |

Пример payload существующего HTTP ingress:

```json
{
  "deviceId": "<vehicle UUID>",
  "sourceId": "sim-car-001",
  "timestamp": "2026-10-11T10:00:00Z",
  "metrics": {
    "latitude": 44.8125,
    "longitude": 20.4612,
    "speedKmh": 36.0,
    "headingDeg": 92.0
  }
}
```

У всех полей одной точки общий sourceId, timestamp и исходный eventId.
Поддержать метрики в ingestion и telemetry catalogs; конверсия deg в %/ratio
и нормализация отрицательных координат к нулю запрещены. Если присутствует
ровно одна координата, nonnumeric/nonfinite/out-of-range координата или
недопустимые переданные speed/heading — GPS-message отклоняется в ingress.
Telemetry повторяет проверку для сообщений, поступивших напрямую из broker;
отклонённое сообщение не даёт GPS-проекцию. Обычные сообщения без координат
сохраняют существующую семантику.

Не вводить новую версию envelope/topic: текущий metrics map позволяет добавить
четыре ключа. Описать расширение и обновить всех владельцев validation до
включения нового симулятора. Не включать GPS в бытовые energy presets,
универсальные ML defaults или энергетические расчёты; ML workflows не расширять.

## 4. Профиль виртуального датчика

Новый SimulationProfile `VEHICLE_GPS`; единственный совместимый preset
`CITY_LOOP`. Список preset/profile совместимостей должен быть явным на backend
и использоваться frontend: POWER_SPIKE/CONSUMPTION_GROWTH не предлагать машине.
Существующие appliance presets/API signatures сохранить совместимыми;
при необходимости добавить optional profile filter и additive metadata.

Предлагаемый YAML v1 (новые sections/types ограничены VEHICLE_GPS):

```yaml
schemaVersion: 1
simulator:
  name: vehicle-gps-simulator
  profile: VEHICLE_GPS
  mode: NORMAL
  protocol: HTTP
target:
  sourceId: sim-car-001
schedule:
  interval: 5s
  startDelay: 0s
  duration: 30m
  speed: 1.0
gps:
  routeId: CITY_LOOP
  speedKmh: 36
metrics:
  latitude:
    type: generated
  longitude:
    type: generated
  speedKmh:
    type: generated
  headingDeg:
    type: generated
```

`generated` разрешён только для этих четырёх полей в VEHICLE_GPS, без expression,
value и отдельных независимых генераторов. В этом профиле обязательны ровно
эти четыре metrics, gps.routeId=CITY_LOOP, mode=NORMAL, protocol=HTTP,
schedule.speed=1 и startDelay=0; anomalies отсутствуют или пусты.
interval — целые 1–30 секунд; duration — от interval до 24h;
gps.speedKmh — finite 1–120. Ошибки включают путь YAML, без молчаливого fallback
на профиль холодильника. Старые профили не требуют gps и не принимают generated.
Основной UI показывает готовый маршрут, интервал/длительность и скорость;
существующий YAML editor остаётся advanced способом настройки, новый не нужен.

CITY_LOOP — локальный версионируемый набор координат вокруг Белграда с первой
точкой, повторённой в конце, минимум четырьмя различными точками, без нулевых
сегментов. Подобрать реалистичный короткий контур и проверить на карте; не
обещать движение строго по полосам/дорогам. Сегменты прямолинейные, routing API
не вызывается. Статический путь находится в simulator, не собирается из
frontend кода и не требует загрузки внешнего маршрута.

VehicleGpsGenerator реализует TelemetryGenerator. Предвычислить расстояния
сегментов (географические метры); за tick пройти расстояние v × elapsedSeconds
от начала run, найти сегмент по cumulative lengths и интерполировать положение.
После конца контура использовать modulo общей длины — без телепорта между
несвязанными концами. Курс вычислить по текущему ненулевому сегменту и
нормализовать к [0,360). Первый emitted point — первая точка маршрута.
Дальнейшее elapsed измеряется от первого tick текущего run монотонными часами;
в unit tests часы инъецируются. Timestamp — фактический UTC момент измерения,
не время, ускоренное в будущее. Просроченные scheduler ticks не должны
выдавать burst одинаковых timestamps или догонять очередь исторических точек.

Генератор не добавляет GPS-шум/события стоянки. Один run — одна версия config;
не подменять маршрут во время выполнения. Использовать текущие leases, start,
stop, run history, failure reporting и ограничение одного active run.
Новый start после stop начинает маршрут заново; recovery после process restart
следует текущей политике run lifecycle, без обещания автоматического продолжения.
GPS-source принадлежит выбранному автомобилю; при save/start проверяется
совместимость типа VEHICLE, профиля и SIMULATOR/REST источника. Изменение типа
устройства не должно обходить эту проверку.

## 5. Проверенная GPS-проекция

Добавить `telemetry.positions` в telemetry-service:

- reading_id, event_id, device_id, source_id, recorded_at, received_at;
- latitude, longitude; nullable speed_kmh/heading_deg; quality metadata.
- идентичность accepted позиции `(device_id, source_id, recorded_at)`;
  повторная доставка event_id также идемпотентна через текущий processed event flow.
- индекс `(device_id, source_id, recorded_at DESC)` и стабильный tie-breaker.

Вставка выполняется в той же транзакции, что accepted telemetry processing;
никакой отдельный consumer не создаёт eventual pairing двух scalar рядов.
Из отклонённых raw readings ничего не строить. Duplicate/conflicting повтор
того же source/timestamp не заменяет первую принятую точку. Out-of-order и
разрешённый backfill сохраняются в истории; latest определяется recorded_at,
не временем arrival. Смена источника никогда не объединяет два трека.
Проверка GPS pairing должна находиться до projection insertion, с отдельным
focused validator/projector, а не большой веткой в TelemetryEventProcessor.

Использовать portable Flyway DDL + vendor-specific индекс/Timescale настройки
в существующих db/vendor locations при необходимости. Retention GPS следует
текущей telemetry retention; отдельное бессрочное хранение/новую retention policy
не вводить. При наличии cleanup/chunk policy подключить к ней проекцию и
проверить удаление. Historical reconstruction из raw JSON не требуется:
новый тип ранее не поддерживался.

## 6. API чтения и лимиты

Новые публичные authenticated endpoints:

- `GET /telemetry/devices/{deviceId}/positions/latest?sourceId=sim-car-001&asOf=<UTC>`
- `GET /telemetry/devices/{deviceId}/positions?sourceId=sim-car-001&from=<UTC>&to=<UTC>`

sourceId — существующий внешний идентификатор источника, обязательный;
dataSourceId UUID применяется в device/setup API и не подменяет этот ключ.
Источник проверяется как принадлежащий выбранному устройству и текущему
authorized account/resource scope. Прямые read APIs используют текущий
TelemetryAccessService; internal dashboard read использует существующую
owner/viewer authorization и не доверяет произвольному accountId из browser.
Denied/not-found ответы сохраняют текущую политику отсутствия утечки имён.
Reads не запускают симуляцию, inference, token rotation или других mutations.

`asOf` необязателен только в public latest: default server now. Latest выбирает
recorded_at <= asOf, возвращает nullable position и `asOf` даже при no data.
History требует from < to, обе границы UTC; интервал `(from,to]`, максимум 24h.
Ошибочные диапазоны/source — 400 в стандартном error envelope, denied/not-found
— текущие 403/404 conventions. Свежесть latest относительно asOf: FRESH при
age <= 30s, STALE при age > 30s, NO_DATA при отсутствии позиции.
Возраст actual historical точки всегда доступен; historical snapshot не
подписывается как текущая live-позиция.

Latest position:

```json
{
  "deviceId": "<UUID>", "sourceId": "sim-car-001",
  "asOf": "2026-10-11T10:00:05Z", "freshness": "FRESH",
  "position": {
    "recordedAt": "2026-10-11T10:00:00Z",
    "receivedAt": "2026-10-11T10:00:01Z",
    "latitude": 44.8125, "longitude": 20.4612,
    "speedKmh": 36.0, "headingDeg": 92.0, "quality": ["OK"]
  }
}
```

История содержит deviceId/sourceId/from/to, points в порядке recordedAt,
returnedCount и truncated. Каждая точка имеет те же поля, что
position. Лимит фиксирован: 1000 точек. SQL берёт последние 1001 accepted
позиций в диапазоне; API возвращает последние 1000 в возрастающем порядке.
Если есть ранние точки, truncated=true. Карта показывает «Последние 1000 точек»
и предлагает сузить период. Пагинация/автоматическая загрузка старых точек
не входят в первую версию.
Это ограниченный след, а не полный маршрут/пробег. Никакого AVG координат.

Для dashboard один internal bounded read возвращает latest + history с теми
же semantics, единым asOf и authorised owner scope через существующий port.
Endpoint/DTO размещаются в current internal dashboard telemetry boundary;
их точный URI документируется при реализации, не создаётся публичный bypass.
Latest может быть до from: тогда маркер остаётся последней известной позицией,
а след в выбранном диапазоне пуст. Позиция после to никогда не попадает в
historical/fixed dashboard context.

## 7. Frontend карточки автомобиля

Мастер: VEHICLE → default VEHICLE_GPS/CITY_LOOP/HTTP, источник sim-car-<suffix>
по текущему правилу уникальности. Использовать существующие checkpoints,
retry/reconcile и token handling. После partial failure не создавать второй
автомобиль/источник и не выводить credential в YAML/log/localStorage.
Смена appliance ↔ vehicle сбрасывает несовместимый preset в draft с явным
сообщением; сохранённая конфигурация не меняется без Save.

Вкладка «Движение» доступна для VEHICLE. При одном совместимом источнике он
выбирается автоматически; при нескольких пользователь выбирает один. При
отсутствии источника — действие настройки эмуляции, без пустой карты в (0,0).
Период по умолчанию — последние 15 минут; выбор 15m/1h/24h. Единственные
map controls — масштаб и «Следовать». Start/Stop остаются в Simulation tab;
из «Движения» есть переход к ней, без дублирования lifecycle controls.

Polling 5s только для активной вкладки и видимого документа; один in-flight
request на view, cleanup/AbortController при смене vehicle/source/range/unmount.
Latest/history используют общий captured asOf. Поздний ответ предыдущего
контекста не заменяет новую карту. Ошибка refresh может оставить прежние данные
только того же context с отметкой ошибки/устаревания.

Общий VehicleMap принимает typed evidence, не делает fetch самостоятельно.
Координаты GeoJSON — `[longitude, latitude]`. При наличии данных первый fit
показывает маркер и след; дальше камера следует только при включённом Follow.
Ручное перетаскивание выключает Follow. Поворот маркера учитывает heading.
Плавное перемещение допустимо только между принятыми последовательными точками
и обозначает визуальный переход, не предсказанную GPS-позицию. После stale
не экстраполировать автомобиль вперёд. Между точками с gap > 30s разрывать
линию; переход на начало при новом run не рисовать как достоверный путь, если
он требует физически невозможной скорости > 300 km/h. Этот guard не заменяет
backend validation и не удаляет accepted точки из истории.
Для одной точки показать маркер без линии. Скорость/курс null → «Нет данных»,
а speed=0 остаётся измеренным нулём. Свежесть не означает ONLINE регистрации
и не доказывает движение/стоянку при отсутствии скорости.

## 8. Виджет в существующем дашборде

При текущей приёмке учитывать уже принятую main spec `hsl-live-vehicles`:
она расширила виджет явным `vehicleSources` для фиксированного автопарка.
Ограничение одного автомобиля ниже относится к legacy `sourceId` mode;
динамический scope и несколько машин с единственным sourceId по-прежнему запрещены.
Принятое расширение не удаляется ради повторной проверки базового сценария.

Manifest: type=`vehicle-map`, typeVersion=1, resultType=`POSITIONS`, reducers=[];
ровно одно fixed device binding + один sourceId. Не поддерживать dynamic fleet
scope или сумму координат. Имеющиеся runtime/access filters по-прежнему
пересекают binding; если машина исключена, показать scope-empty, не вернуть
её тайно. В palette — «Карта автомобиля»; настройка выбирает VEHICLE и его
источник, без полей unit/reducer/line/pie. Данные появляются после config.

Time context наследуется из dashboard: history `(from,to]`, latest <= asOf,
для исторического интервала asOf не позже to. Диапазон >24h даёт typed panel
limit error с предложением сузить время; другие панели работают. Существующий
refresh mechanism обслуживает карту; renderer не заводит отдельный таймер
и не обходит batch/progressive query/cache. Default dashboard refresh не
менять глобально; для dashboard с vehicle-map первый default — 5s, локальная
view preference сохраняется типизированным helper на данном браузере; для live-демо пользователь выбирает существующий 5s refresh,
если такого значения нет — добавить его в текущий общий механизм, без
постоянной GPS-подписки. Read/caching budget должен позволять увидеть новую
точку после следующего успешного refresh, не удерживать её старым cache TTL.

Сохранить binding/sourceId, type/version, presentation initial Follow, screen
и layout в draft, undo/redo, copy и publication. Карта использует размер
панели; ResizeObserver/resize карты при изменении grid/sidebar. Никаких
GPS-запросов, map instances и timers для неактивного dashboard screen.
Карта изолированно обрабатывает NO_DATA, STALE, truncated, denied, unsupported
и unavailable; её failure не ломает соседние widgets. ContextHash и access
checks соблюдаются перед выдачей cache result и при смене grants.

## 9. Карта и внешняя подложка

Выбор renderer — MapLibre GL JS; зависимость и CSS фиксируются в lockfile
при реализации. Официальный пример движения:
https://maplibre.org/maplibre-gl-js/docs/examples/animate-a-point-along-a-route/.
GeoJSON строится из accepted backend evidence, не из загруженного извне пути.
Конфигурация `VITE_MAP_STYLE_URL` задаёт URL MapLibre style; для локального
demo default — `https://tiles.openfreemap.org/styles/bright`, показанный в
официальных примерах MapLibre. Проверить доступность/условия сервиса в момент
реализации и сохранить обязательную attribution. Секретных ключей в frontend
не хранить; URL не должен содержать credentials. Новое env поле документировать
в frontend env example и Compose build args, если используется build-time env.

Первая версия требует интернет для подложки. Если style/tiles/WebGL недоступны,
показать отдельную ошибку карты и текстовые координаты/время/скорость с Retry;
сбой подложки не означает сбой датчика. Не передавать vehicle name, deviceId,
sourceId или track GeoJSON поставщику подложки; tile requests всё равно раскрывают
просматриваемую область. Не запрашивать browser geolocation.
Компонент должен освобождать map/listeners при unmount и не пересоздавать карту
на каждом tick. Controls доступны с клавиатуры и имеют labels; текущая позиция
доступна текстом, цвет не единственный носитель stale/error. Минимальная высота
в карточке 280px; dashboard использует существующие grid size constraints и
проверяется на небольших панелях/mobile, без viewport-wide fixed размеров.

## 10. Проверка, rollout и приёмка

Порядок поставки: catalogs/DDL/API → simulator profile → frontend/widget.
Старые backend catalogs отклоняют GPS, поэтому не включать генерацию до их
обновления. Старые dashboard clients показывают новый typeVersion как
unsupported по текущему контракту; существующие widgets не менять.
Отдельный rollout feature flag не требуется для локального MVP.

Автоматические проверки:

- Device seed/type и совместимость profile/source/type; appliance regression.
- YAML profile-scoped validation, отрицательные координаты, speed/duration bounds.
- Детерминированные first point, расстояние, course, corners, wrap, задержка tick,
  stop/restart, sender/auth failure без direct DB/Kafka пути.
- Ingress + telemetry validation, atomic accepted position, duplicate/conflict,
  out-of-order/backfill, источник/tenant, отсутствие точки из rejected raw JSON.
- API no-data, latest/asOf, диапазоны/1000 cap/gaps; PostgreSQL и H2 DDL.
- Dashboard manifest/config/query/results/access/cache/contextHash, failure
  isolation, publication/copy, без inactive-screen reads.
- Frontend wizard recovery, source selection, stale/no-data/map failure,
  abort/late response, null vs zero, [lon,lat], resize/follow and disposal.

Ручная сквозная проверка с поднятым стеком:

1. Создать VEHICLE с эмуляцией, убедиться в одном device/source/config.
2. Запустить CITY_LOOP; подтвердить минимум три accepted точки одного source,
   положительную скорость и изменение позиции на карте в течение 20s после
   первого успешного ingestion. Это acceptance target, не уже измеренный SLO.
3. Добавить карту на dashboard, Save/Publish/Reload; сравнить машину/источник,
   позицию, сохранение размера и выбранный период; соседняя scalar карта работает.
4. Stop → новые точки прекращаются по текущей stop semantics, последняя позиция
   остаётся, после 30s age — STALE; новый Start начинает контур заново.
5. Выключить доступ к tiles: текстовые GPS-данные остаются; отключить telemetry:
   показать ошибку refresh; сменить screen: GPS reads прекращаются.
6. Проверить чужой device/source и revoked dashboard grant: координаты не видны.
7. Проверить холодильник: создание, эмуляция, график и preset остались рабочими.

Команды: `npm run openspec:validate`, `npm run structure:check`;
из backend `mvn -pl services/device-service,services/simulator-service,services/ingestion-service,services/telemetry-service,services/dashboard-service -am test`;
из frontend `npm run typecheck`, `npm run test`, `npm run build`.
При shared/gateway изменениях расширить Maven проверку соответствующими modules.
ML production smoke не нужен, если реализация не меняет ML runtime/contracts.
Сохранить verification evidence/ограничения в docs flow; не отмечать неисполненный
Docker/browser сценарий как passed и не коммитить generated logs/artifacts.
