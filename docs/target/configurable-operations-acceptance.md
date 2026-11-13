# Проверка законченной архитектуры: инварианты и отказные сценарии

Статус: **проект приёмки**, не evidence работающего кода.
Нормативные контракты: [язык](configurable-operations-language.md),
[хранение/adapters](configurable-operations-storage.md),
[импорты](configurable-operations-imports.md).

## 1. Архитектурные решения, которые нельзя подменить при реализации

| Риск | Принятое решение | Проверка |
| --- | --- | --- |
| EAV и обязательные связи для простой таблицы | Native dense typed table; inline refs; optional history/projection | List/get SQL и benchmark без relation/history feature |
| Универсальный SQL от AI | VCL AST, field UUIDs, closed method registry, parameterized adapters | Invalid grammar/type/access rejection до I/O |
| Обязательная копия клиентской БД | EXTERNAL direct reads, PROJECTED only subscribed fields | Connection query работает без initial full import |
| Неизвестный remote commit | Same-DB/API idempotency receipt + status lookup | Lost ACK после commit не создаёт вторую запись |
| Вечные права бывшего пользователя | Explicit revocable AutomationGrant | Revocation fences pending evaluation/delivery |
| Смена правил в середине окна | Pinned state, boundary cutover, reviewed reevaluation | Нет второго live alert stream |
| Исправление истории меняет прошлое молча | valid/recorded time, original cutoff, append-only correction | Original decision reproducible |
| Импорт «почти подошёл» | Sealed manifest, exact mappings/types/versions | Одной bad row достаточно отклонить ATOMIC mode |
| Попытка доказать отсутствие из sampling | Capture capability + coverage-aware absence operator | UNKNOWN на gap/unknown upstream |
| Год проектирования до полезного результата | UI/API/AI входят в каждый вертикальный срез | Каждый milestone имеет end-to-end проверку |

## 2. Математические и доменные контрольные примеры

Work fixture, даты synthetic, timezone задаётся явно:
- W1=[08:00,12:00), W2=[11:00,17:00) → union duration 9h.
- Approved unpaid break=[12:00,13:00) → net 8h.
- Условие >8h → false; >=8h → true. Counting samples вместо duration запрещён.
- После approved correction break=[12:00,12:30) → net 8.5h и correction outcome.
- При первоначальном recorded cutoff старый результат остаётся 8h.
- Assignment employee A→B в 12:30 разбивает интервалы/перерывы; каждому net 4h.
- Неизвестный break/assignment делает exact net UNKNOWN; нельзя выдать 9h как work.
- Europe/Helsinki 2026-10-25 02:00+03:00 →04:00+02:00 = 3 elapsed hours.
- Local 03:30 на DST fold без offset/explicit policy отвергается importer.

Visit fixture: approved circle 30m, declared uncertainty 5m.
40m → OUTSIDE; 24m → INSIDE; 29m → UNKNOWN; 36m → OUTSIDE.
First 24m observation is left-censored, not entry. Source records OPEN at 5m
with stationary evidence for 2s, then CLOSED before 36m exit → service observed.
No OPEN with covered window and confirmed exit → possible missed observation.
Door event gap or ambiguous nearby feature → UNKNOWN, never asserted violation.
A 10m display circle is not an additional eligibility condition.

Cold-chain fixture: temperature greater than 8 Cel continuously for 10min under
the declared measured/sample-hold contract. One missing 30s segment with maxGap=2s
prevents asserting continuous duration. A valid source schema mapping to another
field name uses the same sustained operator, no COLD_CHAIN code branch.

## 3. Обязательная матрица отказов

| ID | Действие/сбой | Обязательный результат |
| --- | --- | --- |
| A01 | Collection с TEXT/DECIMAL/REF, без joins/history | Dense single-table list/read; нет extra edge/history rows |
| A02 | Изменение label при immutable fieldId | Data interpretation unchanged; stale displayed review revalidated |
| A03 | Изменение DECIMAL scale/unit/type | Impact/migration review; старые rows/rules не переинтерпретируются |
| A04 | Filter по дорогому unindexed field | Typed INDEX_REQUIRED/estimated plan; нет hidden hot-path full scan |
| A05 | Concurrent native updates same record version | Один CAS winner, второй409; no lost update |
| A06 | Повтор native key, другой payload |409; исходный receipt не заменён |
| A07 | SOURCE schema меняет key/type |SCHEMA_DRIFT, mapping paused; no auto coercion |
| A08 | External source без stable key | Read capability честно ограничена; адресная mutation запрещена |
| A09 | Direct external read без projection | Работает через bounded pushdown; полной local copy нет |
| A10 | Rules требуют history от direct-only source | Publication rejects missing capability or explicit projection review |
| A11 | UpdatedAt poll не видит deletes | Absence по удалению строки не квалифицируется |
| A12 | Remote commit прошёл, ответ потерян |UNKNOWN → status same key → один remote receipt/result |
| A13 | Remote write без CAS/receipt facility |UNSUPPORTED_MUTATION, не best-effort replay |
| A14 | Клиентская БД read role не видит field | Нет introspection/query leakage через platform metadata/counts |
| A15 | У rule owner отозван grant | Новые effects fenced; pending delivery rechecks epoch |
| A16 | Тревога раскрывает hidden field косвенно | Output recipient check; raw masking alone insufficient |
| A17 | Query cursor/snapshot истёк |SNAPSHOT_EXPIRED; no silent latest continuation |
| A18 | Foreign dataset UUID в where/ref | Denied без count/label leakage |
| A19 | Некорректный UTF-8/CSV quoting/duplicate header | Reject before target write |
| A20 | JSON duplicate key/unknown mapped column | Reject; no last-value-wins parsing |
| A21 | Decimal comma без explicit mapping |TYPE_MISMATCH; no locale guessing |
| A22 | Превышение precision/rounding not approved |PRECISION_LOSS; no silent rounding |
| A23 | Две строки с одинаковым import key | Reject deterministic atomic import |
| A24 | Reference lookup неоднозначен/чужой |REF_AMBIGUOUS/REF_DENIED; no first-match |
| A25 | Manifest validated, target позже изменён |VALIDATION_STALE/409; renewed diff/consent |
| A26 | ATOMIC import с одной bad row |0 target rows changed |
| A27 | CHUNKED import отменён посередине |Committed chunks retained, remaining stopped, PARTIAL/CANCELLED truthful |
| A28 | STAGED_REPLACE после validation удалит referenced rows |Explicit deletion impact/review; old generation remains until swap |
| A29 | state window получает duplicate/reordered event |Single-key inbox/state transaction; no duplicate outcome |
| A30 | Worker crash до/после outbox commit |Fenced lease replay; committed state survives |
| A31 | reference projection watermark отстаёт |Park/UNKNOWN, no current remote lookup per event |
| A32 | Смена rule revision в открытой смене |Old state drains; no overlap live deployments for same occurrence |
| A33 | Позднее исправление табеля |Append-only corrected outcome; original cutoff reproducible |
| A34 | source silence на spatial exit |UNKNOWN; clock tick не является наблюдением выезда |
| A35 | Boolean field отсутствует |Unknown; NOT unknown remains unknown |
| A36 | Остановка на соседней платформе |Explicit resource identity/ambiguity; no nearest substitution |
| A37 | Два vehicle streams одного сотрудника |Interval union по сотруднику, overlap counted once |
| A38 | Работа/перерывы на midnight/DST |Explicit calendar split + actual elapsed duration |
| A39 | Budget exhausted/cancel specialist |No unreserved model call/late accepted-state overwrite |
| A40 | Схема корректна, AI выбрал ignition вместо work |Complete-task failure; no asserted employee overtime |
| A41 | Valid JSON вызывает неподдержанный method |Reject before owner/provider side effects |
| A42 | Дочерний review с ещё не созданной таблицей |Local ref resolves only from successful creation receipt |
| A43 | Old request replay, model disabled |Stored owner-authorized result без generation |
| A44 | Source document содержит инструкции/SQL/password |Untrusted evidence only; no tool privilege or trace secret |
| A45 | Database native dataset/object quota exhausted |No partial provisioning leak; explicit failed job/cleanup receipt |
| A46 | Index build invalid после timeout |Inspect/reconcile same owned index; not READY and no duplicate |
| A47 | Rule evidence source expired by retention |EVIDENCE_EXPIRED, no fabricated history |
| A48 | UI and AI создают один workflow |Equivalent canonical VCL, owner receipts и runtime outcome |
| A49 | Смена employee schema/labels у другой компании |Same operators; no new evaluator branch |
| A50 | Business scenario требует нового protocol/operator |Explicit capability gap/integration work, no invented support |
| A51 | UPDATE: nullable field omitted / explicit NULL | Omission retains; NULL clears; NULL to required field rejected before write |
| A52 | CSV voltage TEXT '220', sourceUnit V, target kV | parse_decimal → convert_unit yields exactly DECIMAL '0.22'; identity relabel rejected |
| A53 | Native mutation получает external precondition или наоборот | TARGET_MODE_MISMATCH; no fictitious source fingerprint for native |
| A54 | Remote receipt not visible while first transaction is in flight | No inference of rollback; same-key verified dedup/status only; timeout remains UNKNOWN |
| A55 | Two external TEXT keys differ only by Unicode normalization | Distinct source identities; display NFC does not merge keys; no full-copy locator registry |
| A56 | External record UUID без registered locator | LOCATOR_REQUIRED, no table scan; persisted REF registers only selected key |
| A57 | STAGED_REPLACE races a writer holding old generation | Shared writer fence/locked head gives one valid ordering; no late commit to retired generation |
| A58 | Replace preserves existing stable keys | Same record UUIDs, reserved IDs for inserts, deletion impact for missing keys |
| A59 | Session witnesses из другой session branch / orphan graph node | Compile rejection before activation; exactly one terminal emit, all nodes reachable as its ancestors |
| A60 | Node lateness differs from root / exit inferred from idle clock | Reject inconsistent timing; idle does not prove exit; minimum actual exit observations required |
| A61 | Calendar month includes DST boundary | Local month split with actual elapsed duration; 31-day fixed-window cap does not reject valid month |
| A62 | PAUSE / RESUME during open session | Unfinished state UNKNOWN(PAUSED), no effects; resume new epoch, no implicit catch-up |
| A63 | Validation stopped before EOF | Observed counts + validationComplete=false; total/unprocessed nullable; no approval |
| A64 | AI compiles a rule against not-yet-created collection | DRAFT_ONLY; dependent apply awaits successful exact-ID creation receipt and production recompile |
| A65 | Composite business key / source key is not declared or is nullable | No guessed uniqueness; key contract validation, native constraint and source verification |
| A66 | Apply changes body document or reuses stale review digest | Closed apply body + saved document, digest/revision checks and current auth; no effect |

## 4. Закрытие архитектурных вопросов и поставка

Архитектура выбирает VCL typed AST, dense native tables, external adapter protocol,
optional projection/history, explicit import modes, AutomationGrant и durable
event-time evaluator. Эти решения не оставляются будущему «выберем на реализации».
Реализация проверяет выбранное решение; при провале hard invariants/benchmark
изменение архитектуры оформляется новой reviewed revision.

Language/schema/examples должны быть проверены структурно и семантически.
Grammar-проверки не заменяют compiler/runtime tests A01–A66.
Revision-2 API/review sequencing: [control protocol](configurable-operations-control.md).
Executable documentation vectors: `npm run vcl:check` (Node + PowerShell7).
They check documentation consistency and do not close these runtime scenarios.
Performance gate: common no-relations workload ≤20% overhead против equivalent
typed SQL implementation на объявленных hardware/data/ACL/audit; no accidental
full scan/N+1/remote-per-event. Отдельно измеряются catalog growth, imports,
history/projection, joins, temporal state и AI cost. Цель не является измерением.

Поставка вертикальными срезами, а не «сначала весь конструктор, потом AI»:
- M0: contracts, type checker и independently labelled fixtures.
- M1: native/external dataset describe/query, strict import, points/circles,
  typed simple rule, manual review и AI proposal/apply.
- M2: covered sessions/absence, reference projections, original HSL case.
- M3: interval assignments/windows/corrections, fleet work case.
- M4: held-out third-domain composition, performance/security/provider qualification.
Каждый milestone использует один language/compiler/owner API; G0/G1/GX/G2–G8 —
владельцы модулей, а не требование полностью закончить каждый до начала AI.

## 5. Что считается реализацией законченной версии

Завершение — все заявленные language methods, storage/adapter capability contracts,
import modes, reviewed write/recovery, temporal operators, common UI/AI operations
и acceptance matrix в согласованном scope реализованы и проверены.
Одна демонстрация/M1 не объявляется законченной платформой.
Подключаемая БД может честно не поддерживать write/CDC/history; UI/AI показывают
её конкретные capabilities. Это ограничение конкретного источника, а не
недокументированная потеря гарантии платформы.

