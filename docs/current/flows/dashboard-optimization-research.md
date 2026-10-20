# Исследование оптимизации дашбордов — 2026-10-11

**Результат:** подтверждены overfetching, N+1 чтений энергии по циклам,
лишние React commits, тяжёлое получение latest telemetry и фрагментация
дедупликации. Общего HTTP N+1 по количеству устройств в эквивалентном
пакетном запросе не обнаружено. SQL N+1 обслуживания найден в коде;
его влияние на большой реальной странице ещё нужно измерить.

Это отчёт об исходной реализации и план следующих изменений. Оптимизации
продукта в рамках исследования не внедрялись. Числа ниже относятся к
локальному окружению и не являются production SLO или оценкой capacity.

## Метод и границы доказательств

Рабочее дерево VoltCanary поверх `7397ef9ca687e18ea38e9c04d1cbd60bdee57d73`
с существующими незакоммиченными изменениями; PostgreSQL 16,
TimescaleDB 2.17.2, действующий Compose stack. Fixtures: четыре существующих
демонстрационных дашборда, семь устройств в inventory, шесть устройств в
исследованных fleet bindings. Данные и определения не изменялись.

- **E1 — runtime HTTP:** gateway `8080`, GET metadata, POST read-only
  `/dashboards/{id}/query`: пять последовательных `forceRefresh=true`, затем
  пять обычных запросов для каждого дашборда; отдельно subsets 1/6/full.
  Измерены wall time, TTFB, UTF-8 bytes, cache flag и дельты
  `http_server_requests_seconds_count` на портах 8081/8082/8084/8085/8086/8089.
  Счётчики общие: это локальные наблюдения, а не распределённый trace.
  URI-фильтр не учитывает `/auth/me`; числа не описывают все HTTP запросы.
- **E2 — payload:** подсчёт `sources`, scope и series buckets по JSON;
  сравнение с реально используемыми полями. Удаление всех
  `sources` — диагностическая нижняя граница, а не готовый безопасный DTO.
- **E3 — SQL:** точные representative SQL из repositories, фиксированные
  account/device scope и asOf, по три `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`
  в `BEGIN READ ONLY` с timeout 5 секунд и rollback. Индексы проверены.
  `pg_stat_statements` отсутствует; общего SQL trace всех batch нет.
- **E4 — composition experiments:** настоящий Java query engine с mock
  owner ports; матрица 1/6/12/24 widgets × 1/10/50/250/500 devices,
  отдельно изменение неиспользуемого `rows`, задержка источника 300 мс
  и 1/6/12/24/32 cycles. Это проверка числа вызовов/размера DTO, не DB load test.
- **E5 — render experiment:** настоящий `DashboardWidgetPanel`/series
  renderer под React Profiler, jsdom/dev, одна серия × 200 bins на график,
  1/6/12/24 графика, три шага таймера по секунде. Один sample на конфигурацию;
  durations не являются production browser CPU/paint latency.
- **E6 — delivery/UI/contracts:** production Vite build с manifest,
  временный preview на 3100, существующая страница в браузере, анализ hooks,
  scheduler и adapters; focused tests для refresh/context/access/outages.
  Preview остановлен после исследования. Dev StrictMode не используется
  как доказательство production двойных запросов.

Повторные API запросы проходили на уже работающем stack. `forceRefresh`
обходит application cache, но не сбрасывает SQL buffers/JIT/OS cache.
Настоящий cold startup не измерен; медианы пяти повторений не выдаются за p95.
Runtime matrix ограничена существующими fixtures. Большие fleets — только
изолированный E4, не синтетические записи в пользовательской базе.

Локальные воспроизводимые материалы (игнорируемые Git) находятся в
`artifacts/dashboard-research/`: `runtime.json`, четыре `*-batch.json`,
`sql.json`, `*-explain.json`, `latest-alternative.json`,
`latest-dynamic-keys.json`, 
`frontend-profiler.json`, `delivery.json`, копии диагностических тестов.
Runner scripts: `artifacts/dashboard-research.py`,
`artifacts/dashboard-research-sql.py`,
`artifacts/dashboard-research-latest-alternative.py`,
`artifacts/dashboard-research-delivery.py`,
`artifacts/prepare-dashboard-diagnostics.py`; logs находятся рядом.
Это локальное evidence, не обязательные файлы чистого checkout.
Таблицы этого документа сохраняют итог независимо от generated artifacts.
Пароль и bearer token не записывались в evidence; login использовал только
process environment. Временные тесты удалены из исходников после выполнения.

## Карта чтения и существующие ограничения

```text
PersonalDashboardsPage → metadata/definition → DashboardViewer preferences
  → visible active widget IDs → useDashboardQuery → gateway query
  → actor + definition + scope authorization → cache eligibility
  → bounded QueryReadScheduler / request-local dedup
  → telemetry / analytics / incidents / maintenance / energy / business facts
  → downstream scope reauthorization → owner repository SQL
  → WidgetEvidenceProjection + full sources → JSON → SVG/DOM
```

Важные владельцы реализации:

| Участок | Файл |
| --- | --- |
| Metadata/route/loading | `frontend/src/pages/PersonalDashboardsPage.tsx` |
| Видимые панели и settings | `frontend/src/features/dashboards/components/DashboardViewer.tsx` |
| Context, refresh, merge, cancellation | `frontend/src/dashboards/useDashboardQuery.ts` |
| Refresh/pause/backoff | `frontend/src/components/useReadRefresh.ts` |
| Timer/info/render | `frontend/src/features/dashboards/components/DashboardWidgetPanel.tsx` |
| SVG/points/transforms | `frontend/src/features/dashboards/components/DashboardSeriesDiagram.tsx` |
| Auth/cache/read keys | `backend/services/dashboard-service/src/main/java/com/voltcanary/dashboard/query/DashboardWidgetQueries.java` |
| Scheduler | `backend/services/dashboard-service/src/main/java/com/voltcanary/dashboard/query/QueryReadScheduler.java` |
| DTO duplication | `backend/services/dashboard-service/src/main/java/com/voltcanary/dashboard/query/WidgetEvidenceProjection.java` |
| Energy per cycle | `backend/services/dashboard-service/src/main/java/com/voltcanary/dashboard/adapter/HttpDashboardEvidenceReads.java` |
| Business calculations | `backend/services/dashboard-service/src/main/java/com/voltcanary/dashboard/adapter/DashboardBusinessReads.java` |
| Telemetry SQL | `backend/services/telemetry-service/src/main/java/com/voltcanary/telemetry/repository/DashboardTelemetryRepository.java` |
| Maintenance SQL | `backend/services/device-service/src/main/java/com/voltcanary/device/repository/DashboardMaintenanceRepository.java` |

Пределы уже существуют: 24 widgets, до 8 series в пользовательском widget,
500 scoped devices, 50 detail rows. Scheduler: 6 общих рабочих потоков,
bounded queue 144, batch deadline 5 секунд. HTTP clients переиспользуются,
connect/read timeout ограничен. Нельзя считать весь scope/request overhead
лишним: current authorization проверяется до cache и downstream владельцами.
Read path не запускает обучение/inference jobs и не мутирует бизнес-данные.

## HTTP baseline

Размеры — decimal bytes, весь ответ batch, без упаковки;
все widgets запрошены явно, что больше первого видимого viewport.

| Dashboard | Widgets | Forced median, мс (min–max) | Warm median, мс (min–max) | Bytes | Warm cache hits |
| --- | ---: | ---: | ---: |
| Monitoring | 18 | 921,30 (911,56–1021,81) | 964,44 (889,66–1137,68) | 3 064 173 | 0/5 |
| Analyst | 12 | 731,67 (698,01–826,11) | 692,84 (669,76–714,10) | 2 658 514 | 0/5 |
| Service | 9 | 427,18 (400,73–439,64) | 70,18 (69,64–88,60) | 627 762 | 5/5 |
| Energy | 11 | 680,67 (634,51–724,21) | 654,02 (612,68–674,73) | 2 787 108 | 0/5 |

Cache threshold — serialized batch < 2 000 000 characters, TTL 30 секунд,
64 entries. Analyst превышает threshold. Cost/operations и workspace также
имеют ограничения eligibility. Поэтому «просто повторить запрос» не ускоряет
три полных дашборда; service показывает, что рабочий cache уже даёт эффект.
Увеличение threshold без исправления DTO повысит удерживаемую память.

| Forced batch | Scope reads | Telemetry | Energy | Analytics | Другие |
| --- | ---: | ---: | --- |
| Monitoring | 31 | 11 | 14 | 1 | operations 1, tariffs 1 |
| Analyst | 13 | 6 | 1 | 3 | incidents 1 |
| Service | 13 | 5 | 0 | 2 | incidents 1, maintenance 1 |
| Energy | 9 | 4 | 3 | 0 | tariffs 1 |

Cache-hit service оставляет пять scope reads, без owner evidence reads.
Повторная авторизация здесь необходима по текущему контракту. Разделять
security cost и одинаковые evidence reads нужно прежде, чем сокращать calls.

Subsets подтверждают, что размер/число панелей влияет на работу:

| Dashboard | 1 widget, мс / bytes | 6 widgets, мс / bytes | Full, мс / bytes |
| --- | --- | --- | --- |
| Monitoring | 162,96 / 52 491 | 442,81 / 191 072 | 937,94 / 3 064 173 |
| Analyst | 42,64 / 10 132 | 623,09 / 2 523 705 | 699,15 / 2 658 514 |
| Service | 40,04 / 10 132 | 466,59 / 608 765 | 432,89 / 627 762 |
| Energy | 112,48 / 52 530 | 484,27 / 1 024 919 | 646,32 / 2 787 108 |

Это одиночные subset samples; нельзя выводить линейную latency из них.
Число owner calls определяется уникальными запросами и вложенными reads,
а не только числом widgets.

## N+1, overfetching и SQL

### N+1: подтверждение и отрицательные результаты

- **Fleet HTTP N+1 не подтверждён:** во всех 20 ячейках E4 эквивалентные
  fleet-count widgets делят один scope read и один telemetry read независимо
  от 1–500 устройств/1–24 widgets. Analytics также использует batch SQL,
  а не отдельный запрос каждого устройства. Это не доказывает отсутствие
  иных N+1 в workspace/maintenance/business paths.
- **Energy N+1 подтверждён:** operations facts read, затем по одному
  energy read на complete cycle. E4: 1/6/12/24/32 cycles → ровно
  1/6/12/24/32 energy calls. Runtime monitoring имеет 14 energy reads;
  вложенные reads не дедуплицируются обычным top-level scheduler.
  Comparison может добавить второй проход. Это уникальные интервалы,
  требующие batch interval API, а не простой cache одинакового ключа.
- **Maintenance SQL N+1 найден в repository:** count + page + device lookup
  для каждой операции, то есть `2 + R` statements без auth/timeout setup.
  Индекс lookup уже есть; runtime slope на непустых больших страницах
  не измерен. Уверенность в форме SQL высокая, в latency эффекте — средняя.
- **Read-key fragmentation подтверждена:** 24 fleet-count widgets с
  различным неиспользуемым `rows` дают 24 telemetry calls вместо одного.
  Dedup key включает весь owner request, а не только поля нужной проекции.
- Workspace capability lookups могут повторяться на widget: найдено по
  коду `WidgetScopeResolver`, но real workspace matrix не измерена.

### Дублирование ответа

Time-series panel с шестью series содержит 1 200 buckets (200 на series),
занимает около 821–829 КБ; `sources.telemetry` повторяет большую часть
данных и занимает около 413–417 КБ. Full scope копируется в каждую панель;
на данном fixture это около 6,4 КБ даже у context-note.

| Batch | Normalized bytes | Без sources, bytes |
| --- | ---: | ---: |
| Monitoring | 3 064 171 | 1 526 579 |
| Analyst | 2 658 514 | 1 325 025 |
| Service | 627 762 | 318 665 |
| Energy | 2 787 106 | 1 400 125 |

Два bytes отличаются от raw HTTP из-за нормализации чисел. Примерно половина
JSON относится к `sources`, но часть provenance нужна. Требуется compact DTO
и явное чтение деталей, а не безусловное удаление fields. В E4 пустой
telemetry fixture 500 devices × 24 widgets уже даёт **5 762 931 bytes**
из-за повторяющихся scope/freshness. Это не измерение такого real fleet.

Telemetry owner сейчас получает latest всех метрик, summary statistics,
quality и series, даже если widget использует только часть. Analytics
читает risk/predictions/maintenance вместе. Summary вычисляет несколько
reducers, включая p95 и extrema timestamps. Typed data needs могут убрать
ненужную работу, сохранив completeness/quality semantics.

### SQL-планы

| Representative query | Execution ms, три повтора | Rows / наблюдение |
| --- | --- | --- |
| Latest telemetry, 6 devices | 293,433 / 271,162 / 275,405 | 121 362 строки перед сортировкой → 48 latest values |
| Summary activePowerW | 23,194 / 22,479 / 23,003 | 1 итоговая строка |
| Energy raw interval | 29,457 / 30,465 / 29,829 | 14 754 строки |
| Analytics risk | 0,134 / 0,110 / 0,113 | 6 результатов из 40 строк |
| Maintenance device lookup | 0,084 / 0,084 / 0,089 | В выбранном fixture 0 matching rows |

Latest использует `DISTINCT ON(device_id, metric_name)` по всей истории до
asOf. External merge sort пишет/читает примерно 13 МиБ temporary blocks.
Другие запросы не доказаны bottleneck; маленький seq scan analytics нормален.
Индексы telemetry по account/device/metric/time уже существуют. Следует
сначала сократить чтение, а не добавлять индекс вслепую.

Два read-only SQL эксперимента без DDL:

1. LATERAL LIMIT 1 по registered metric catalog: **4,97–5,16 мс**, но
   `EXCEPT` показывает **18 различающихся строк**: три исторические demo
   метрики на шести устройствах отсутствуют в catalog. **Отклонён:** быстрый
   результат меняет существующую семантику.
2. Получение DISTINCT device/metric keys с исходными predicates, затем
   LATERAL latest: **512,238 / 169,844 / 155,535 мс**, `EXCEPT` в обе стороны
   **0 строк**. Сохраняет результат на fixture, но первая итерация медленнее
   baseline и всё ещё сканирует историю. Кандидат, не готовая замена.

Рекомендуется demand-aware latest и полный индекс/реестр observed keys либо
latest projection с явным historical-asOf fallback. Для projection отдельно
нужны backfill, out-of-order events, corrections, quality, account isolation
и tie-break `(recorded_at DESC, source_id DESC)`. Ограничивать историю только
ради скорости нельзя: старое последнее измерение не должно исчезать.

## Отрисовка и загрузка

`DashboardWidgetPanel` вызывает `useEvidenceClock()` с секундным interval,
но возвращаемое значение не используется. State update всё равно повторно
рендерит subtree, включая SVG transformations.

| Graphs × 200 bins | Timers | DOM nodes | Mount ms (jsdom/dev) | Idle commits за 3 секунды | Сумма idle Profiler ms |
| --- | ---: | ---: | ---: |
| 1 | 1 | 820 | 116,25 | 3 | 53,79 |
| 6 | 6 | 4 920 | 276,53 | 18 | 161,05 |
| 12 | 12 | 9 840 | 311,89 | 36 | 426,00 |
| 24 | 24 | 19 680 | 599,63 | 72 | 680,57 |

Каждый bin создаёт несколько SVG elements, tooltip/title и keyboard target.
Date parsing/diagram transforms повторяются. Сначала нужно убрать clock
из общего panel, оставить age tick только в открытых details, затем memo
по неизменным series/time/unit. Downsampling должен сохранять extrema,
gaps/quality и accessibility; ограничение points по ширине требует API/key
контракта. Canvas/worker не оправдан без production browser profile.

### Что уже сохраняется во время loading

Same-context `useDashboardQuery` refresh сохраняет panels и layout, merge
сохраняет уже полученные offscreen panels. `useSectionResource` тоже держит
данные при refresh и показывает stale после ошибки. Generic Loading всё
ещё используется при отсутствии metadata/первом переходе. На context change
старые evidence очищаются — это правильная защита от неверных данных.

Один batch ждёт свои результаты целиком: E4 быстрый incidents + telemetry
с delay 300 мс доставляются вместе через **335,97 мс**. Independent errors
не означают independent early delivery. IntersectionObserver уже ограничивает
запросы видимыми widgets; открытый экран не обязан запрашивать full batch E1.

Рекомендуемые состояния (будущая реализация):

| Событие | Что показывать | Cache/access правило |
| --- | --- | --- |
| Первый заход без definition | Нейтральный skeleton shell | Никаких чужих names/values |
| Definition разрешён, evidence pending | Skeleton в точных местах известных widgets | Geometry отдельно от evidence |
| Refresh того же контекста | Готовый canvas, небольшой Refreshing и age/asOf | Старые значения видимы как предыдущие, не как новое чтение |
| Медленный/ошибочный owner | Остальные панели usable; stale/unavailable локально | Unknown не подменять нулём |
| Revisit того же dashboard | Быстро layout; evidence после renewed authorization | Bounded actor/account/revision/context keyed memory cache |
| Filter/time/screen/revision change | Сохранять геометрию, очищать несовместимые values | Старый context нельзя представить новым |
| Account switch/logout/401/403/revocation | Немедленно purge evidence и чувствительные names | Не ждать TTL; проверка доступа до reuse |

Refresh показывает stale/age достаточно явно ещё до completion; на failed
refresh сохраняется исходный asOf. Cache key включает actor/account,
authorization epoch, dashboard revision, screen/widget query, authorized
scope, timezone и точный resolved time interval. Не использовать persistent
localStorage evidence по умолчанию. Layout cache тоже может содержать
чувствительные names и должен иметь scope/purge.

Progressive delivery лучше начинать с bounded microbatches fast/expensive
групп с единым server-resolved asOf/query context; не делать запрос на каждый
widget. Альтернатива — streaming/query-session API, но оно сложнее для auth,
revocation, retries и partial ordering. Текущий refresh без AbortSignal
защищён generation guards от старого результата, но не прекращает транспорт
после unmount/context change; рекомендуется реальное abort для refresh.

## Стандартный checklist

Статусы: **подтверждено**, **проверено без дефекта**, **кандидат по коду**,
**не измерено**. «Не измерено» не означает passed.

| Проверка | Вердикт / evidence |
| --- | --- |
| HTTP N+1 / batch | Fleet batch работает E4; energy cycles N+1 E4/E1 |
| SQL N+1 / ORM lazy loading | Maintenance 2+R по коду; path использует JDBC, ORM lazy loading неприменим |
| Duplicate fields / unused projections | Подтверждено E2; typed telemetry/analytics needs кандидат |
| Limit, paging, top-N, full denominator | Bounds есть; scope повторяется E2/E4; ограничивать detail можно, aggregate denominator нельзя |
| Aggregation / point budgets / viewport | 200 bins/series независимо от размеров; width-aware budget кандидат |
| Time bounds / raw history | Latest до asOf без нижней границы тяжёлый E3; energy boundary widening нужно для корректной интеграции |
| Cache / TTL / eligibility | Service hit E1; analyst размер, business eligibility — misses; invalidation нельзя заменить длинным TTL |
| Singleflight / read-key normalization | Request-local работает для одинаковых reads; неиспользуемый rows дробит key E4; cross-request singleflight не реализован |
| Auth reuse / workspace lookups | Renew-before-cache работает; workspace fanout кандидат по коду, runtime не измерен |
| SQL plans / rows read / temporary sort | Latest 121 362→48 и disk sort E3; прочие measured queries не bottleneck |
| Индексы / statistics / materialization | Существующие индексы проверены; два SQL кандидата E3; новый индекс/projection требует отдельной проверки |
| Pools / queues / timeouts / connection reuse | Ограничения и clients проверены по коду; pool saturation/queue wait под concurrency не измерены |
| Cancellation / obsolete responses | Context abort + generation guards проверены tests; refresh transport abort отсутствует по коду; interrupt downstream требует проверки |
| Polling / hidden tab / backoff / retry storm | Existing hidden pause/backoff/run lock tests проходят; many-clients retry storm не измерен |
| Waterfall / slowest source | Metadata→query по коду; batch slow-source blocking E4; полноценный browser HAR не получен |
| JS size / splitting / unused editor code | Build E6: page chunk 240 799 bytes, static graph 604 244 bytes; editor/grid входит в viewer graph |
| Asset delivery / cache / preload | Vite preview имеет ETag/no-cache; это не production ingress. Production CDN/Brotli/preload не проверены |
| React commits / derived transforms / memo | Подтверждены idle commits E5; memo/age isolation нужны |
| DOM / SVG / accessibility / chart hit targets | E5 до 19 680 nodes при одной серии на график; упрощение hit targets требует keyboard regression |
| Layout / ResizeObserver / CSS containment | Width observers есть; forced reflow/CLS и containment gain не измерены |
| Animation / frame budget / long tasks | Нет измеренного production trace; не заявлять устранённым |
| Heap / leaks / listeners / retained cache | Cache ограничен; memory leak/heap snapshots не измерены. Idle docker snapshot: dashboard ~701,5 МиБ, telemetry ~862,2 МиБ, device ~914,3 МиБ; не peak и не allocation profile |
| Device/network tiers / CPU / useful paint | Не измерены реальный production FUP, browser CPU/heap, slow-network/mobile tiers и p95 |
| Stale / partial / denied access / switch | Existing query tests и hooks проверены; loading strategy сохраняет purge/generation semantics |

CUA DOM bridge не предоставляет ResourceTiming/heap/profiler API, поэтому
jsdom timings и API wall time не подменяют browser useful paint. Полный
browser performance trace, production deployment и параллельная нагрузка
остаются обязательной verification для соответствующих implementation changes.

## Приоритетный план следующих изменений

P1 означает подтверждённую полезную оптимизацию; P2 — условный этап после
измерений. P0 correctness/security blocker исследованием не найден.
Effort S/M/L — относительная оценка, не обещание сроков.

| ID / приоритет / effort | Изменение и лучший способ | Evidence / ожидаемый эффект | Риски, зависимости и acceptance |
| --- | --- | --- | --- |
| O1 P1 M | Compact panel DTO: primary evidence один раз, scope dictionary/references; compact provenance, authorized details on demand с тем же revision/asOf | E2: около 50% raw JSON приходится на sources. Цель ≥40% снижения raw bytes на тех же fixtures без потери необходимых данных; реальный эффект измерить | DTO/API/UI change первым. Details endpoint не должен скрывать required quality/completeness или выдавать revoked data; сравнить значения/units/denominators и details parity. Альтернатива includeDetails=false дешевле миграции, но оставляет повтор scope |
| O2 P1 S | Удалить unused panel clock; age timer только в открытой информации; memo derived series/renderer | E5: цель 0 graph commits при трёх idle ticks, вне open details; убрать N timers | Frontend независимо от O1. Проверить обновление age в details, keyboard tooltips, filter changes, stable SVG; сравнить production Profiler. Один общий clock без isolation всё равно ререндерит графики |
| O3 P1 M/L | Typed owner projection/read key: только нужные latest/summary/quality/series; нормализация irrelevant rows/offset; затем latest strategy | E3/E4: latest271–293 мс, 24 ненужных reads. Цель identical fleet-count→1 telemetry call, series-only не делает unused latest; latest без disk sort на fixture | Сохранять quality/coverage и observed metrics. O1 помогает DTO, но не обязателен. Latest projection L и отдельный контракт ingestion/backfill; exact all-key SQL кандидат пока нестабилен. EXCEPT=0 + historical/out-of-order/quality/foreign account tests и repeated warm/cold plans обязательны |
| O4 P1 M | Batch interval energy API для operations/tariff segments; общий asOf, bounded intervals, ответ по interval key | E4: C energy calls→один bounded batch или заранее определённое число chunks; 1/6/12/24/32 без linear HTTP slope | Cross-service/API change. Полная parity energy/coverage/maintenance exclusions/time boundaries/partial failures; не вычислять расход простым вычитанием при reset/gap. Альтернатива bounded parallel reads ускоряет latency, но N+1 остаётся |
| O5 P1 S/M | Maintenance page: один batch device lookup по IDs возвращённой страницы; count+page+batch | Repository2+R→3 SQL независимо от R; latency выигрыш ещё не измерен | Device-service, независим. Непустые fixtures R1/10/50, SQL trace, auth/account isolation, порядок pagination, operation с несколькими devices. JOIN без grouping может дублировать page rows/count |
| O6 P1 M | Loading UX: layout skeleton, explicit refreshing/stale, bounded authorized revisit cache; refresh AbortController; затем progressive microbatch | E4 slow batch335,97 мс; same-context retention уже есть. Цель usable existing canvas сразу при refresh, быстрые группы доступны до slow completion | Начать с UI O2/O1, progressive contract отдельным этапом. Account/revision/context/revocation purge; slow owner, error, 401/403, navigate/unmount, delayed obsolete response tests; FUP/CLS измерить в настоящем browser |
| O8 P2 M | Cache после compact DTO: byte-budget LRU, per-owner validatable data, bounded same-context singleflight; business version tags для tariffs/facts | E1 service427→70мс; analyst misses могут исчезнуть после O1. Общий predicted speedup не задан | O1/O3, version/invalidation contract. Current auth до reuse, actor/grant isolation, time resolved keys, force semantics; expiry/mutation/revocation tests. Сначала in-process; Redis без measurements не нужен |
| O9 P2 S/M | Разделить editor/catalog/viewer imports, lazy editor/grid; profile SVG budgets по ширине | E6 viewer chunk240,8КБ, static graph604,2КБ; точная экономия требует сборки альтернативы | Frontend/build. Сравнить route graph и FUP cold route, editor interaction не должна ломаться; не делать fetch на каждый bin/widget |
| O10 P2 M | Измерить workspace auth fanout, pool saturation, production render/heap и many-client polling; затем устранить найденные повторы | Непроверенные части checklist | Не увеличивать pool вслепую. Единый trace ID, owner/sql counters, queue wait, heap/long-task trace, scale1/10/50/250/500, bounded concurrent clients. Отдельный load-testing change для capacity заявлений |

Следующие OpenSpec scopes (названия предлагаемые, changes ещё не созданы):

1. `optimize-dashboard-evidence-payload` — O1, DTO/details compatibility.
2. `reduce-dashboard-idle-rendering` — O2 и измерение chart transformations.
3. `optimize-dashboard-owner-reads` — O3 + O5; latest projection при необходимости
   выделить отдельно из-за ingestion/history semantics.
4. `batch-dashboard-business-energy-reads` — O4.
5. `improve-dashboard-progressive-loading` — O6; сначала безопасные UI states,
   затем shared-asOf progressive protocol.
6. `optimize-dashboard-delivery-and-cache` — O7/O8/O9 после повторного baseline;
   O10 supply измерения, не считать всё заранее требующим реализации.

Не рекомендуются без новых evidence: массовое увеличение thread/connection
pools, новые индексы на маленькой analytics table, Redis, полная DOM
virtualization при лимите24widgets, WebWorker/Canvas, global persistent
evidence cache. Отклонены registered-only latest, удаление auth renew,
ограничение aggregate до first page, downsampling теряющий peaks/gaps,
выдача stale другого контекста как актуального. Они меняют смысл или права.

## Воспроизведение и проверка последующих changes

1. Запустить обычный local stack по operations runbook; выбрать четыре
   persona fixtures с одинаковыми authorized device IDs и фиксированными
   from/to/timezone/asOf. Проверить health, widget count, series, source gaps.
2. Авторизоваться через `/auth/login`; держать credentials/token только в
   памяти процесса. GET `/dashboards/{id}`, POST query с revision и
   defaultContext; пять forced + пять warm, отдельно widgetIds1/6/full.
   Зафиксировать raw bytes, cache flag, timing и owner Prometheus deltas;
   сохранить exact request keys и server query time без credentials.
3. Для SQL скопировать актуальный repository statement с параметрами;
   `BEGIN READ ONLY; SET LOCAL statement_timeout='5s'; EXPLAIN (ANALYZE,
   BUFFERS, FORMAT JSON) ...; ROLLBACK;` три раза. Перед заменой SQL сравнить
   оба результата через двусторонний EXCEPT с теми же filters/asOf.
4. Изолированно повторить E4 и E5 по описанным размерам, считать calls,
   commits/nodes и bytes; не выдавать mocked latency за runtime.
5. Production build: `cd frontend; npm.cmd run build -- --manifest`;
   собрать transitive static imports из `.vite/manifest.json`. Проверить
   немедленную доставку готовых групп панелей через реальный ingress.
6. На production browser снять Network/HAR, Profiler/Performance/heap:
   cold/warm/revisit, same-context refresh, context/account/revision switch,
   viewport visibility, slow/failed owner, background pause. Измерять first
   useful paint и CLS отдельно от network completion. Минимум несколько
   повторов, указать device/network tier; p95 только с подходящей выборкой.
7. Проверки invariants: тот же scope/asOf/units/coverage, no hidden zero,
   extrema и maintenance exclusions, no unauthorized names/results,
   stale/partial independent panels, no obsolete response overwrite.

В этом исследовании прошли Java diagnostic suite **19 tests** (включает
копии существующих query/business checks), frontend diagnostic/focused
первый запуск **6 tests**, отдельный существующий refresh/query/viewer запуск
**8 tests**, production build. Суммы включают повторные тесты и не являются
числом уникальных сценариев. Временные диагностические исходники удалены;
существующая реализация не исправлялась. OpenSpec strict validation прошла
до и после архивации: **48 passed, 0 failed**. `structure:check` прошёл;
сохранены существующие warnings о размерах классов/числе зависимостей.
Research change архивирован как `2026-10-11-investigate-dashboard-optimization`,
все 14 research tasks завершены. По отдельному запросу пользователя после
runtime замеров остановлены 16 контейнеров проекта, Docker Desktop и его
WSL distributions; Docker Kubernetes также остановлен. Данные не удалялись.

Исследовательский deliverable завершён с явно указанными unverified items.
Ни production useful paint/heap, ни saturation, ни большое реальное fleet
не объявляются проверенными. Рекомендации и acceptance выше позволяют
переходить к implementation changes без предположения, что эффект уже получен.

## Implementation follow-up — 2026-10-11

The user authorized implementation after this research. The active change
`optimize-dashboard-read-and-render-path` implements the ten requested problems:

- Compact primary evidence, shared scope dictionaries and authorization-renewed
  source information for the original snapshot;30s/32-snapshot/32MB detail bounds.
- Information-only age clocks and memoized primary rendering.
- Typed telemetry projections and relevant owner keys; latest-only reads use asOf
  and ignore pagination/comparison fields they do not consume.
- Loose observed-metric key scan with indexed account/null-account candidates;
  no history cutoff. PostgreSQL/Timescale covering partial index and H2-compatible
  vendor migration preserve both production and local-test environments.
- One energy HTTP batch per operational period, and one cost batch including
  comparison tariff segments. Registry, calculator, bracketing, reset/gap and
  raw-maintenance-consumption policies remain unchanged. Batch-wide raw budget is
  500000 samples;1-64 keyed intervals with1-50 distinct devices each.
- Maintenance count/page plus one account-filtered device lookup per nonempty page.
- NDJSON delivery as panels complete, at one authorized revision/context/asOf,
  a single shared scope dictionary, bounded stream executor and explicit completion.
- Layout-only revisit skeletons, refresh indicators and AbortController for manual
  and automatic queries; actor/context/revision changes clear prior evidence.
- Compact16MB/64-entry LRU cache with30s TTL, force semantics and current business
  version fingerprints. Workspace evidence remains uncached. Business fingerprint
  reads still run on hits; telemetry energy is reused only while inputs match.
- Private no-store headers and immediate NDJSON flushes for ready panel groups.

Focused regression evidence, distinct from the original E1-E6 demo measurements:

| Check | Result | Scope and limitation |
| --- | --- | --- |
| Compact24-series fixture |623862 ->317728 raw bytes,49.1% reduction | Primary bins/quality preserved; one scope dictionary. This is not a remeasurement of the four demo dashboards. |
| Idle24-chart test |0 additional graph renders across3 idle seconds | Information age advances separately; component test, not production Profiler/FUP. |
|24 fleet counts with different unused pagination |1 telemetry read | Real scheduler with mocked owner; scope/count values preserved. |
| Latest121370-row Timescale fixture | Exact ordered result and bidirectional EXCEPT=0; isolated repeated legacy227-235ms vs optimized1.2-1.4ms |10 returned keys; includes400-day-old metric, observed non-catalogue metric, future/foreign/null-account/ties/NaN. Indexed reads and no disk sort; cached buffers, not cold production storage. |
| Series-only projection |1 series SQL;0 latest/summary/quality SQL | Owner repository selection regression. |
| Confirmed cycles1/6/12/24/32 |1 facts HTTP +1 energy-batch HTTP per period | Actual local HTTP adapter test; bounded batch math parity checked against single intervals. |
| Maintenance pages1/10/50 |3 prepared SQL statements each | PostgreSQL fixtures with multiple devices and foreign-account windows; page/order/flags retained. |
| Progressive delivery | Fast panel arrives while slow owner remains blocked; all groups share asOf/hash | Scheduler latch, NDJSON controller and split/truncated/auth-switch browser-stream tests; production ingress buffering and useful paint remain unmeasured. |
| Business cache | Warm hit; changed input version and force both reread; revoked details denied | Current metadata/auth renewal precedes reuse; workspace cache deliberately disabled. |

Docker Compose `infra` + `app` verification was completed against the existing
four demo dashboards on 2026-10-11. All services were checked through their health
endpoints; the telemetry vendor migration applied successfully. Chart owner reads
retain the authorized series-device detail list required by telemetry validation.

| Docker demo | Compact JSON bytes | Warm cache | Forced / warm elapsed, ms |
| --- | ---: | --- | ---: |
| Monitoring |1449781 |hit |1057 /217 |
| Analyst |1261104 |hit |299 /77 |
| Service |296941 |hit |154 /74 |
| Energy |1340371 |hit |301 /116 |

These are individual local requests after startup, not latency distributions or
production SLOs. Compared with the original demo byte measurements, compact
responses remove approximately half the payload. All panels were ready except
the service agenda's expected no-data state. The existing primary evidence
matched in33 field comparisons across charts, summaries and analytics rows;
timestamp formatting was normalized and floating means used1e-12 tolerance.
The analyst NDJSON request delivered its first group at88ms and completed at423ms:
12 panels, one shared asOf/contextHash and an explicit completion marker. Retained
details were authorized and matched the original snapshot watermark.

Production deployment still needs the new telemetry migration, the updated gateway
and dashboard service, and the matching frontend. Large/many-chunk fleets, cold disk,
many concurrent clients, production ingress CPU and browser FUP/CLS/heap have not
been certified by these functional fixtures. No new capacity or production SLO
claim is made. The original device/alert loading incidents remain outside this
dashboard research's established causes.

## Canonical energy selection follow-up — 2026-10-11

The energy batch's remaining repeated sample filtering/normalization has been
replaced. Telemetry selects account/device/source/metric and physical/time bounds
in SQL; adjacent/overlapping stream ranges merge before one measurement SELECT.
The PostgreSQL V009 partial covering index supports source/metric/time range
lookups; H2 has its portable equivalent. Physical replacements retain separate
boundaries while contiguous revisions of the same meter/source retain bracketing.

Each returned stream normalizes once. Binary time bounds select interval points
plus boundary neighbors; prefix totals retain full read-window excluded, duplicate
and conflicting sample diagnostics. Reset/rollover/gap/integration math, original
one-day bracketing, single-query comparison windows and raw maintenance policy are
preserved. Bounds remain64 intervals,50 devices per interval,500000 selected rows
and3 seconds, with10000 merged canonical ranges bounding PostgreSQL parameters.

| Verification | Result |
| --- | --- |
| Timescale fixture with unrelated sources |101990 broad rows ->906 selected rows for32 intervals; one SELECT for1/12/32; real duplicate rows retained; foreign/future/noncanonical evidence excluded |
| Final indexed plans |1.245 /0.934 /2.256ms for1/12/32 intervals; fewer than2000 scanned leaf rows; no disk sort on this warm fixture |
|32 overlapping-cycle normalization |640000 raw visits in the frozen legacy calculator ->20000 prepared raw visits; all result fields match |
| Random calculator parity |600 interval/read-window/quality/gap/reset/rollover cases match every legacy result field |
| Boundary and budget regressions |Same-meter revisions, replacement with reused source, overlapping/adjacent ranges, comparison brackets, missing meter,500000-row and10000-range caps pass |
| Service validation |46 telemetry tests pass; PostgreSQL/Timescale and H2 V009 migrations verified |
| Docker demo validation |Updated telemetry is healthy; all50 primary panel payloads match before/after, with1e-12 floating tolerance for SQL means; energy/cost/operation evidence matches exactly |

These figures demonstrate selected-row and processing-work reductions on local
fixtures. They do not certify production concurrency, cold storage latency or CPU
capacity. Query API/energy response shapes remain compatible.
