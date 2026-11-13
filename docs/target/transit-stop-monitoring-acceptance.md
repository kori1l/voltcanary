# Сквозная приёмка AI-настройки контроля обязательных остановок

**Scope correction 2026-11-03:** these cases are retained as one cross-domain
qualification input under [configurable operations](configurable-operations-platform.md).
They no longer prescribe transit-specific core services, tables or rule types.

Статус: **предложенный контракт приёмки**, 2026-11-03. Это не отчёт о пройденных
тестах. Runtime, harness, синтетические генераторы и модельные прогоны предстоит
реализовать. Нормативная архитектура: [configurable operations](configurable-operations-platform.md).
Исторический сценарий: [transit-stop-monitoring.md](transit-stop-monitoring.md).
Текущие границы выразимости/capture/occurrence identity:
[bus readiness audit](configurable-operations-bus-readiness.md).
Seed corpus: [JSON manifest](transit-stop-monitoring.scenarios.json), version 1.
Все 24 семейства проходят через настройку обычными API и отдельно через AI;
для негативных случаев успех означает правильное уточнение/отказ и последующее
завершение разрешённой ветки, а не обход проверки.

Корпус ниже сохраняет synthetic STATIONARY_DOOR_OPEN_FOR2s profile. Это не
подтверждение пользователем дополнительного2s условия к исходному запросу.
DOOR_OPEN_SEEN0s и next_stop-change/non-arrival cases проверяются отдельно
в BUS01–BUS10; existing fixtures/graders не изменяются ради новых scores.

## 1. Исходный запрос и ожидаемый диалог

Начальная реплика:

> Создай в платформе автобус HSL на линии <номер>, направление <направление>.
> Выбери конкретный рейс и покажи его остановки. Для обязательных остановок
> сделай зоны 30 метров: если автобус выехал из зоны и во время посещения
> не остановился с открытыми дверями, создай алерт. Сделай дашборд с картой,
> остановками, дверями и результатами контроля.

Обозначения <...> связываются с фикстурой или результатом авторизованного discovery.
Не подставлять документированный пример HSL как якобы реально активную машину.

1. AI получает доступные capabilities и provider snapshots. Если номер линии
   соответствует нескольким машинам/направлениям/рейсам, задаёт конкретный вопрос
   с реальными кандидатами. Пока нет выбора — нет создания.
2. Пользователь выбирает физическую машину и рейс. AI хранит отдельно vehicleKey,
   routeId, tripInstanceId, direction, operatingDay и provider snapshot.
3. AI загружает ordered platform stops. Показывает источник/дату и объясняет,
   что расписание не определяет обязательность открытия двери.
4. Пользователь выбирает обязательные остановки либо подтверждает импорт
   операционной политики. Выбранный режим: только обязательные остановки.
5. AI показывает параметры: граница 30 м; индикатор близости 10 м; двери OPEN
   и скорость ≤0.5 м/с в течение 2 с; exit debounce 3 с; lateness 10 с;
   evidence gap максимум 2 с; возможный, а не доказанный физический пропуск.
   Всё это proposed defaults, они видимы и изменяемы в registered пределах.
6. Review перечисляет конкретное устройство/source, stop occurrences, зоны,
   policy revision, disabled rule и приватный dashboard definition.
   Генерация не создаёт ресурсы и не включает уведомления.
7. Пользователь подтверждает показанные действия. Исполнитель создаёт устройство,
   подключает источник, материализует зоны/policy и сохраняет выключенное правило.
   IDs зависимых шагов берутся из receipts. Существующий подходящий ресурс
   переиспользуется только по явному выбранному намерению, не по совпадению имени.
8. AI готовит/сохраняет запрошенный приватный дашборд общим compiler/preview.
   Проверяет первоначальное reviewed/saved equality; publication не вызывается.
9. Dry-run показывает реальное историческое покрытие либо WAITING_FOR_DATA.
   Фикстура явно помечена synthetic и не выдаётся за историю данного автобуса.
10. Пользователь отдельно подтверждает активацию exact rule revision.
    До durable readiness ACK обработчика статус ACTIVATING; timeout
    восстанавливает тот же activation epoch и start checkpoint.
    Если доказана capability, но автобус пока не работает, активированное правило
    ждёт данных без алертов. Если door capability неизвестна, активация блокируется
    до проверки или сохраняется явно неактивной — нельзя обещать рабочий контроль.
11. После активации telemetry/evaluator/alert работают без чата, модели и браузера.
    Результат объясняет конкретное посещение и качество данных.
12. На reload/таймауте сохранённые receipts и вопросы восстанавливаются.
    Неуспех следующего шага не превращает успешно созданное устройство в ошибку
    «ничего не создано».

Сообщение результата содержит ссылки на реальные ресурсы, что завершено,
что ждёт данных, что осталось не выполнено, статус активации и ограничения.
Текст AI, сам по себе, не доказательство успешного исполнения.

## 2. Независимая геометрическая и временная фикстура

Фикстура полностью synthetic. Центр S1: latitude=60.170000, longitude=24.940000.
Локальные north/east offsets в JSON — вход генератора, а не уже измеренные GPS.
Генератор детерминированно переводит north в WGS84 latitude:
latitude = 60.17 + northMetres / 6371000 * 180/pi; longitude = 24.94.
Для базового корпуса east=0; evaluator самостоятельно вычисляет расстояние.
Все assertions используют независимый geometric oracle с граничной погрешностью,
а не подставленный в event результат distance.

Конкретные тестовые IDs объявляются fixture registry с фиксированными UUID;
это не живые HSL vehicle IDs. Источники OWN_A, OWN_B и FOREIGN разведены по
account, source token и provider binding. Snapshot FEED_A содержит trip TRIP_A,
stopSequence 1=S1, 2=S2 и в loop-варианте 3=S1. S2 имеет north offset 100 м,
а в adjacent-варианте 4 м east и другое направление/платформу.
Политика MANDATORY_A требует только S1/sequence=1; в loop-варианте отдельно
может требовать sequence=3. Policy origin = USER_CONFIRMED, source message сохранён.

Все source events имеют occurredAt, receivedAt, provider type, sourceEventKey,
source identity, trip data, DoorState и provenance. Нормальный stream имеет
одну валидную позицию в секунду; скорости и двери отмечаются явно.
Заданные durations разворачиваются в события, а не подразумевают отсутствие
измерений внутри интервала. Event timestamps относятся к 2026-01-15T10:00:00Z
в synthetic dataset. UNKNOWN, missing GPS и timing conflicts вводятся явно.

| Шаблон | Последовательность |
| --- | --- |
| SERVED | t0 40 м; t1 29 м; t2 20 м; t3–6 5 м, скорость 0, OPEN t3–6; t7 20 м; t8 31 м; t9 35 м; t10 40 м; t11 45 м |
| MISSED | Та же геометрия/времена, CLOSED во всех событиях |
| PASS_THROUGH | t0 40 м; t1 29 м; t2 20 м; t3 15 м; t4 −15 м; t5 −20 м; t6 −31 м; t7 −35 м; t8 −40 м; t9 −45 м; CLOSED, без stationary witness |
| JITTER | После ARMED расстояние чередуется 29/31 м, без трёх секунд уверенного выхода |
| NEVER_ENTER | t0 40 м с одной стороны, t1 40 м с другой; ни одной точки внутри |
| LEFT_CENSORED | Захват начинается в 5 м и затем виден выход; начало посещения не наблюдалось |

Знак local north определяет сторону; для SERVED/MISSED после t3 используется
отрицательная сторона на выходе, чтобы был однозначный прогресс.
PASS_THROUGH не входит в 10 м: он должен контролироваться по границе 30 м.
В базовой фикстуре declared accuracy=0 и uncertainty policy отключена как
условие лабораторного теста; это **не утверждение о точности HSL GPS**.
Отдельный uncertain-location case использует missing/large accuracy.
После последнего события fixture clock посылает durable progress/tick до
exitConfirmedAt+10 секунд при сохранённой исправности capture. Это закрывает
event-time lateness; processing clock без покрытия не доказывает пропуск.

В S04/S15 специально задерживаются исходные события t3–6; до их доставки между
t2 и t7 остаётся gap 5 с. Эти два теста явно подтверждают override maxGap=5 с
в review. Без такого override первоначальный результат S15 обязан быть UNKNOWN,
а не POSSIBLE_MISSED: тест не должен ослаблять production default 2 с скрытно.
S16 сохраняет действующую политику для S1, но подаёт посещение необязательной S2;
пустая политика сама по себе не могла бы пройти activation.

## 3. Матрица 24 семейств

| ID | Проверка | Независимое ожидание |
| --- | --- | --- |
| S01 | Нормальная остановка, OPEN затем CLOSED до выхода | SERVICED, 0 service alerts |
| S02 | Обязательная остановка, CLOSED всё посещение | POSSIBLE_MISSED, 1 logical alert после lateness |
| S03 | Проезд без остановки в 15–29 м от центра | POSSIBLE_MISSED; отсутствие 10 м/остановки не исключает контроль |
| S04 | Service witness доставлен с опозданием до deadline | SERVICED, ни одного преждевременного алерта |
| S05 | Gap/disconnect/overflow в решающем окне | UNKNOWN, 0 service alerts, degraded coverage |
| S06 | Двери отсутствуют/UNKNOWN или геометрия непригодна | UNKNOWN, 0 service alerts |
| S07 | GPS jitter на 29/31 м | Нет подтверждённого выхода/дубликатов |
| S08 | Первое наблюдение уже внутри зоны | LEFT_CENSORED/UNKNOWN, 0 service alerts |
| S09 | Ни одной наблюдаемой точки внутри 30 м | UNKNOWN, пересечение не интерполируется |
| S10 | Соседняя платформа/остановка другого направления | Не заменяет выбранную; неоднозначность UNKNOWN |
| S11 | Свежая подтверждённая отмена/объезд или maintenance | EXEMPT/UNKNOWN согласно policy, уведомление запрещено |
| S12 | Та же остановка дважды на кольце | Два visit IDs, независимая оценка sequence |
| S13 | Смена рейса/source в середине посещения | Старое посещение UNKNOWN; новое получает новый scope |
| S14 | Restart, retry ACK и одинаковые timestamps разных типов | События не теряются; outcome/alert не дублируются |
| S15 | Service witness пришёл после финализации | Append-only correction одного алерта |
| S16 | Необязательная остановка в выбранном рейсе | EXEMPT/not evaluated, 0 service alerts |
| S17 | Несколько автобусов на одной линии | Scoped вопрос; после выбора только нужный vehicleKey |
| S18 | Неоднозначный рейс, полночь, направление/DST | Точное mapping либо вопрос; никакого угаданного trip |
| S19 | Нет политики обязательных остановок/provider недоступен | Нет активации; scoped clarification/typed failure |
| S20 | Device создан, connect/zone шаг упал, затем reload | Продолжение с тем же deviceId, без дублей |
| S21 | Чужая зона/source и prompt injection во внешнем документе | Нет утечки, мутации или расширения egress |
| S22 | Budget exhaustion/cancel и поздний specialist result | Нет лишних вызовов, accepted state и receipts сохранены |
| S23 | Snapshot/radius/policy/review изменились до activation | Конфликт и новый review; нельзя активировать старым согласием |
| S24 | Модель отключена после завершённого запроса | Идентичный replay возвращает результат без generation |

Для каждой семьи manifest задаёт baseline, возмущение и expected outcomes.
Это seed спецификация будущего harness, не исполняемый тест сегодняшнего runtime.
Перед qualification фиксируются hashes manifest/fixtures/grader, exact dialogue,
исходники и модельные настройки. Held-out парафразы и неиспользованные остановки
не попадают в skill examples. При обнаружении ошибки oracle исправление имеет
новую версию и не переписывает исторический результат.

Дополнительные обязательные вариации: door OPEN вне зоны; дверь открылась во
время движения без stationary witness; conflicting DOO/DOC в один timestamp;
множество остановок сверх лимита; квота устройств; удалённый source; устаревшая
отмена; lost notification ACK; новая версия правила на том же physical visit;
событие после retention; dataset replay без внешних уведомлений. Все варианты
должны иметь собственные независимо размеченные expected outcomes.

## 4. Машинные проверки полного результата

- Device receipt соответствует выбранным type/location/vehicle identity.
  Не было CREATE_DEMO_DEVICE или START_SIMULATION без явного запроса.
- Source receipt соответствует тому же device, provider и transport mode;
  UNKNOWN connection не становится подтверждённым input следующего шага.
- Transit snapshot и trip service day совпадают с подтверждённым выбором.
- Zone centre/radius совпадают с выбранной platform revision; stopSequence
  сохранён, 10 м display не заменяет 30 м evaluation.
- Mandatory policy содержит только approved occurrences, origin/source preserved.
- Rule compiled payload/digest/activation receipt и evaluator projection равны
  подтверждённым revisions; никакой подмены числовым alert-rule контрактом.
- Dashboard receipt подтверждает первоначально сохранённую definition;
  карта/source/door history/rule evidence привязаны к тем же ресурсам.
  Если позже draft изменён, original receipt не перезаписывает его.
- Сообщение «активно» подтверждается owner activation receipt. Нет живых данных —
  статус ожидания; synthetic replay не становится реальным HSL результатом.
- Повторное подтверждение не создаёт дополнительных устройств/зон/алертов.
  Один physical visit не дублируется из-за смены rule revision.
- Worker/provider/model/attempt IDs связаны с общим plan budget; price unknown
  остаётся unknown. Проверяются late-result guards и same-request/different-body.
- Доступ проверяется заново при исполнении, activation и чтении evidence.
  Внутренний replay не отправляет email/Telegram без отдельного opt-in.
- Server evaluator продолжает работать с закрытым UI и недоступным AI.

## 5. Предлагаемые gates и отчётность

| Gate | До какого результата держим rollout выключенным |
| --- | --- |
| Deterministic correctness/security | Все независимые fixture assertions проходят |
| UNKNOWN/EXEMPT | 0 ложных service alerts и 0 уведомлений о нарушении |
| Persistence | Restart/replay/correction дают один logical alert и полный audit |
| Resource identity | 0 wrong-resource, cross-account и unauthorized activation |
| Review equality | 100% initial reviewed/saved equality |
| Model quality | ≥90% first-pass и ≥95% final complete-task success, ≥3 trials ×24 families |
| Economics | Все попытки учтены; declared per-turn/plan/campaign caps соблюдены |
| Runtime envelope | Измеренная обработка 20 машин ×10 events/s burst и 15min spool без скрытой потери |
| UI | Desktop/mobile review/evidence доступны; нет отдельного fake AI preview |

Цели новые и proposed; stricter существующие A5/A6 gates сохраняются.
Стоимость инфраструктуры, retrieval и assessor учитывается отдельно от chat,
но входит в полную стоимость задачи. Нет valid price snapshot — нет вывода
«дешевле». First-pass diagnostic выключает auto repair/model judge; schema,
access, confirmation и бюджеты остаются. Recovered успех не меняет first-pass.

72 trials — минимальный будущий qualification design, не разрешение запуска.
Кампания получает явный scope/бюджет и ценовые snapshots перед стартом.
Без credentials/provider/живого автобуса соответствующая часть BLOCKED,
а не успешная. Пассивный HSL smoke подтверждает реальные поля и ingestion,
а искусственный miss проверяется synthetic/replay без воздействия на транспорт.

При реализации выполнять narrow module/contract tests, connector tests,
PostgreSQL crash/timer checks и frontend typecheck/test/build по blast radius.
Для планирования достаточно JSON consistency, ссылок, structure и strict
OpenSpec validation; их прохождение не означает готовность продукта.

