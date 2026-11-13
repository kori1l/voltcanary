# Автобусный сценарий: выразимость, пробелы и готовность

Статус: **аудит целевой архитектуры**, 2026-11-03; не отчёт о рабочем сценарии.
Владельцы реализации — G2/G3/G4/G5/G7/G8 и существующие A5/A6. Специализированная
T1–T7 очередь не возобновляется. Связанные контракты: [platform](configurable-operations-platform.md),
[VCL](configurable-operations-language.md), [AI](ai-assistant-collaboration-and-retrieval.md),
[Spring bindings](ai-assistant-spring-implementation.md).

## 1. Какое требование уже подтверждено

Контролировать **только обязательные остановки**. Не спрашивать этот выбор повторно.
Обязательность должна прийти из выбранной пользователем политики/списка, а не из
самого факта наличия остановки в расписании. Область контроля30м, близость10м
для отображения — разные вещи. Пользователь хочет проверить открытие дверей;
дополнительные speed/duration условия требуют явной семантики review.

Два режима — обычные настройки существующего observed_within, не новые operators:

| Режим | Условие свидетеля обслуживания |
| --- | --- |
| DOOR_OPEN_SEEN | Известное OPEN в подтверждённом посещении; minimumDurationUs=0 |
| STATIONARY_DOOR_OPEN_FOR | OPEN и speed≤approved threshold непрерывно ≥approved duration |

Название режима — package/UI preset, не новое поле VCL grammar. Первая настройка
соответствует запросу об отсутствии открытия двери. Порог0.5м/с и2с в старом
synthetic примере относятся ко второму режиму и не являются уже данным consent.
Открытие0.4с не считается отсутствующим в первом режиме. Если пользователь требует
именно остановки, AI согласует predicate, а не незаметно усиливает критерий.

## 2. Проход через общие возможности

| Шаг | Общая возможность | Что нужно доказать |
| --- | --- | --- |
| Выбор машины/рейса | Resource discovery + typed questions | Vehicle отдельно от route/trip/day/direction; no label guessing |
| Получение остановок | Provider adapter + strict import + typed tables | Ordered occurrences/platform IDs, snapshot/version/source |
| Обязательность | User/company policy collection + authorized relation | Confirmed exact occurrences, applicability/cancellation policy |
| Зоны30м | Spatial features + shared map editor | Accurate centre, approved uncertainty; crossing definition |
| Позиция/дверь | Typed source mapping + operational events/coverage | No transition loss in latest-value coalescing; unknown remains unknown |
| Привязка наблюдения | Versioned reference/candidate binding | Stable occurrence across next-stop updates and exits |
| Проверка | lookup_asof → eligible visit contexts → zone_session → observed_within → absent_within | Same-session witness, closed coverage, event-time lateness |
| Тревога | Existing alert/dedup/notification ports | One logical alert/occurrence, safe corrections and recipient scope |
| Дашборд | Shared widgets/compiler | Actual bound source, zones, freshness, door evidence, alert explanation |
| Настройка AI | Registry/requirements/consultations/dry-run/review | Exact effects and receipts, not merely valid JSON |

HSL/GTFS знания находятся в provider package. Общий evaluator не получает
BUS_MISSED_STOP branch. Функции исходного протокола и его идентичностей требуют
adapter code/qualification; новая компания/маршрут/таблица/порог — конфигурация.

## 3. Четыре существенные границы готовности

**Capture сейчас недостаточен.** deploy/connectors/hsl/mapping.py принимает VP
с валидной GPS-точкой и сохраняет doorsOpen при наличии drst. Текущий pipeline
коалесцирует latest observation примерно каждые5с и не хранит полный durable
backlog. Это годится для карты, но может потерять короткое OPEN/CLOSED. Нельзя
квалифицировать отсутствие открытий и gap≤2с по этому latest-only stream.
G3 сохраняет operational events отдельно от latest positions; G5 adapter принимает
поддерживаемые события двери/рейса без обязательного GPS, не угадывая их позицию.
Local overflow/disconnect/retention loss даёт coverage gap. Даже полный local capture
не доказывает, что upstream публичный feed сообщил каждый физический факт.

**Occurrence binding нельзя подменить next_stop.** Generic VCL example уже получает
готовый occurrenceFieldId; это входной контракт, не реализованный HSL resolver.
Source package обязан materialize verified trip/stop occurrence identity и routing
contexts до evaluation. При открытом visit identity/zone/policy pinned; next_stop
может измениться, но оставшиеся position/door events всё ещё должны попадать в этот
visit до его завершения/UNKNOWN timeout. Нельзя отфильтровать их только потому,
что новая next stop необязательна, или пересадить witness в следующий visit.

Для одного заранее определённого контекста на событие владелец источника может
указать в mapping `occurrenceRouting` с версией `direct-occurrence-1` и двумя
ключами сохранённых строковых полей `subjectKey` и `occurrenceKey`. Схема
закрепляет их UUID, а фоновый исполнитель правила геозоны сверяет их с полями
сессии. Пустой или отсутствующий идентификатор сохраняется как UNKNOWN.
Источник передаёт один и тот же идентификатор визита для входа, открытия двери
и выхода, даже если `next_stop` меняется. Эта конфигурация описывает прямую
привязку. Разрешение HSL и распределение события по нескольким активным
контекстам остаются отдельными задачами.

Проекция active candidate contexts — shared bounded reference-binding capability,
а HSL journey/stop matching — adapter-specific source interpretation. Не считать
её автоматически реализованной строкой lookup_asof: EXACT_ONE lookup не создаёт
fan-out сам. До release G3/G5/G4 обязаны показать concrete executable input binding
и independent trace для next-stop change, loop/adjacent stops и two open contexts.
Fan-out bounded/costed в source binding manifest; no remote query per GPS point.
Если имеющийся binding/operator registry этого не выражает, сначала versioned
generic contract/compiler extension и fixtures, затем release — не hidden HSL
state machine внутри zone_session и не обещание AI выдумать binding.

**Свидетельство должно быть пространственно и временно пригодно.** Door event
без GPS — полезный факт, но не доказательство, что дверь открылась в выбранной
зоне. Witness связывается по subject/trip/occurrence/event time с covered session
и qualified position/uncertainty contract; stale last position не становится
точной координатой двери. Неоднозначность → UNKNOWN. Погрешность GPS и близкие
платформы могут не позволить различить10/30м; тогда точность обещания ограничена.
Прямой профиль геозоны проверяет положение по закреплённой геометрии и
политике точности. Для открытия двери без координат нужна запись позиции
с той же меткой времени, объектом, визитом и зоной. Идентификатор записи
позиции сохраняется вместе с результатом. Противоречивые позиции в этот
момент дают UNKNOWN. Запись без координат не меняет состояние входа или
выхода. Открытие за границей зоны не засчитывается даже во время ожидания
подтверждённого выезда.
Два GPS-сообщения с одной меткой времени дают одно наблюдение для
подтверждения выезда. Если позиции на границе входа или выезда
противоречат друг другу, итог проверки остаётся UNKNOWN.

Если источник передаёт GPS и дверь отдельными сообщениями, предикат может
использовать булево поле `doorObservation` вместе с `doorsOpen`. Для
сообщения только о позиции источник задаёт `doorObservation=false`. Если
сообщение относится к двери, но её состояние неизвестно, источник передаёт
`doorObservation=true` и оставляет `doorsOpen` неизвестным.

**Выезд из30м и непосещение — разные правила.** Текущий zone_session требует
наблюдённого входа и выхода. Если автобус вообще не попал в зону, перепрыгнул её
между редкими точками или маршрут был изменён, одно это правило не докажет пропуск.
«Не прибыл к обязательной точке к сроку» — отдельная комбинация expected obligations,
deadline window, route progress/exemptions и covered absence. Для неё нужен
registered expectation-event/trigger binding и явный review. Пока он не выражен
и не проверен, показать capability gap, не объявлять coverage всех видов пропуска.
Этот дополнительный сценарий полезен для доставки, обходов и обслуживания объектов.

## 4. Что значит алерт

При подтверждённой обязательности, observed covered entry/exit и отсутствии
подходящего witness после lateness: POSSIBLE_MISSED_OBSERVATION — «в доступных
данных не наблюдалось требуемое открытие двери». При gap/unknown geometry/context:
UNKNOWN с причиной, без утверждения о нарушении. Поздний witness корректирует
тот же outcome/alert. Прошёл мимо вне зоны и полное неприбытие не маскируются под
доказанный отрицательный результат этого правила.

Любая дверь OPEN не доказывает посадку пассажира, открытие конкретной двери или
реальное исполнение транспортного регламента. Для более сильных утверждений нужны
другие policy/data capabilities, а не более уверенная формулировка модели.

## 5. Проверки, которых нельзя заменить демонстрацией

| ID | Независимое ожидание |
| --- | --- |
| BUS01 | OPEN0.4с внутри зоны: DOOR_OPEN_SEEN serviced; optional2s policy отдельно согласована |
| BUS02 | Между latest GPS samples открылась/закрылась дверь: operational capture сохраняет оба факта либо UNKNOWN |
| BUS03 | next_stop сменился до выхода за30м, следующая остановка необязательна: старый visit получает exit/witness и не теряется |
| BUS04 | Одна платформа дважды на рейсе/соседняя платформа: разные occurrence IDs, no nearest/first matching |
| BUS05 | Door event без GPS при stale/ambiguous position: не выдуманное обслуживание/нарушение |
| BUS06 | Автобус не входил в30м: не ложный missed alert от exit rule; separate deadline capability явно указана |
| BUS07 | Смена рейса, service day/midnight/direction, detour/cancellation: old context UNKNOWN/EXEMPT, no silent rebind |
| BUS08 | Gap, overflow, restart, late witness: truthful coverage, one logical outcome, append-only correction |
| BUS09 | UI и AI для исходного запроса дают одинаковые canonical resources/policy/predicate и actual receipts |
| BUS10 | NL held-out dialogue с неоднозначной линией/рейсом и уже подтверждённой обязательностью: нужные уточнения, без повторной анкеты |

Это дополнение к retained S01–S24, generic A01–A66, AI01–AI24 и SPR01–SPR15;
не их замена. Existing synthetic2s corpus не переписывается в пользу нового score:
к нему добавляются отдельные versioned cases для DOOR_OPEN_SEEN и identity routing.
Reference VCL example является projection contract illustration, не end-to-end
доказательством сырых HSL данных или всех видов missed-stop detection.

## 6. Гибкость и возможности модели

Те же primitives дают delivery zone+proof of delivery, equipment threshold+sustained,
employee assignment+interval union+calendar threshold. Пользовательские таблицы,
scoped external DB, опциональные projections и strict imports позволяют менять
бизнес-схему без новых entity/controller на каждую отрасль. Arbitrary code/protocol/
новый temporal operator не обещаны как конфигурация: capability gap остаётся явным.

AI может интерпретировать цель, сопоставлять schemas, спрашивать business choices,
предлагать mapping/VCL/dashboard, запускать разрешённый dry-run и готовить review.
Правильность фактов/geometry/aggregates/authorization/receipt выполняет платформа.
Без implemented capabilities модель не может создать рабочий механизм текстом.
Даже после реализации нужна qualification конкретного model/profile/prompt/
retrieval/compiler tuple на BUS09/BUS10 и независимых runtime outcomes.
Предыдущий live baseline13/27 passed dialogues не является доказательством готовности
нового bus workflow. Дешёвая модель может оказаться достаточной для отдельных
typed skills, но это результат измерений, а не следствие выбора Spring AI.

## 7. Проверенные внешние сведения

[Официальный HFP contract](https://digitransit.fi/en/developers/apis/5-realtime-api/vehicle-positions/high-frequency-positioning/)
перечисляет doo/doc, различает next_stop и связанный с событием stop, описывает
смену next_stop при отправлении/проезде; drst=1 означает хотя бы одну открытую
дверь. Provider arrival/departure radius не объявляется нашим пользовательским30м.
Эти сведения подтверждают доступность формата, не полноту конкретного автобуса.
Доступ/registration, endpoint limits и актуальная availability проверяются adapter
capability probe; открытость MQTT не переносится автоматически на другие API.
