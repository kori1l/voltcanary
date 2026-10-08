# Tasks: Доступность, мобильные рабочие экраны и загрузка по маршрутам

Checkbox отражает implementation и verification либо явно указанное исключение пользователя, а не успешность пропущенной проверки.
Перед кодом: `npm.cmd run openspec:apply -- improve-console-accessibility-and-page-delivery`, затем соответствующие
project surface skills. Покрытие: R11, R21, R22, R24, R25, R26.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Подготовить desktop/mobile prototype navigation и populated inventory/ML sections с 200% text.
- [x] 1.2 Реализовать shared Tabs и интегрировать device/ML workflows с keyboard semantics.
- [x] 1.3 Связать status/error/hints и дать entity-aware имена row actions.
- [x] 1.4 Убрать nested button-role у Agenda; keyboard Enter на Cancel не открывает родителя.
- [x] 1.5 Исправить required marker и neutral unavailable tokens, undefined variables и затронутые CSS overrides.
- [x] 1.6 Обновить task terminology ML workspace/Acknowledge без обещания Production ready; сохранить route URLs.
- [x] 1.7 Добавить mobile inventory cards/compact rows и discoverable ML section control по результату прототипа.
- [x] 1.8 Разделить route chunks через lazy loading, устойчивые fallback и explicit chunk retry.
- [x] 1.9 Закрыть доступные keyboard/AX и responsive проверки 360/390/768/1024/1280/1440 с длинными данными; native 200% zoom и screen-reader speech исключены из обязательной приёмки этого change по указанию пользователя 2026-10-09, не выполнены.
- [x] 1.10 Сравнить build chunks и initial auth requests до/после; не заявлять latency improvement только по size.

## 2. Documentation And Final Gates

- [x] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [x] 2.2 Выполнить frontend typecheck/test/build и доступные browser/workflow проверки; сохранить исключение по native zoom/speech (см. решение ниже). Backend migrations/contracts здесь не изменялись.
- [x] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.

## Acceptance evidence (2026-10-08)

Implemented shared manual-activation Tabs, linked Fields, independent Agenda
commands, entity-named retries/lifecycle actions, ML workspace/Acknowledge labels,
compact inventory rows, eight-section mobile picker and lazy route delivery.
Read-only populated synthetic actual-component prototypes fit all six required
widths (document scroll width equals client width). Browser arrows move focus
without selection; Enter activates. Doubled font stress reflows at all six widths.
This is not native browser zoom or screen-reader speech evidence. The connected
browser exposes accessibility trees but no speech/zoom capability. At this
checkpoint 1.9/2.2 were pending; the user-directed exception below supersedes
that archive gate without claiming the missing checks passed.

Frontend full 58 files / 173 tests passed, plus focused route continuity regression
and ML lifecycle naming checks. Typecheck/build pass. Main JS 791.29→204.21 kB,
gzip 219.38→64.07; ML 151.50, device details 122.19, shell 8.75 kB separated.
Initial login API requests remain 0 before/after. No latency claim.
Strict OpenSpec 36 items and structure checks pass (existing warnings).
Prerequisite behavior for evidence, command outcomes and session recovery remains
covered. Current flow and target mapping record implementation and pending gates.

## Queue continuation verification (2026-10-09)

User requested execution of all active changes in sequence. Reapplied this
earliest unfinished change. Frontend typecheck/build pass; the full suite passes
59 files / 176 tests with `npm run test -- --maxWorkers=2 --reporter=dot`.
An initial default-worker run failed four tests; all four files pass separately
(30 tests, one worker) and the bounded full run passes. This remains a test
concurrency limitation, not evidence of application latency. Logs are in ignored
`artifacts/active-changes-frontend-focused.log` and
`artifacts/active-changes-frontend-full.log`.

Strict OpenSpec validation passes 36 items; structure passes with existing risk
warnings. Browser inventory exposes only the in-app browser and MCP Apps, no
native app surface. Browser capabilities list visibility and viewport, with no
native zoom or screen-reader speech control. Tasks 1.9/2.2 were still open at
this checkpoint; the dependent visual editor was not started. Native acceptance procedure is in
`docs/current/flows/console-interface.md`. No architecture or agent validation
policy changes require AGENTS.md or skill updates.

## User-directed acceptance exception (2026-10-09)

User explicitly instructed: «Закрой этот 200 зум. Если нельзя выполнить и
заархивируй соответственно чендж». The native 200% zoom check cannot be
executed through the available browser capabilities. Archive is authorized
with this verification limitation and the already disclosed screen-reader
speech limitation. Both native checks remain unexecuted; neither is recorded
as passed or as accessibility certification. Available responsive, doubled-font,
keyboard/AX, typecheck/build and 176-test evidence remains valid. The exception
applies only to this change's acceptance, does not remove product accessibility
requirements or waive future changes' checks. Manual verification steps remain
in docs/current/flows/console-interface.md for later use.
