## 1. Подготовка и baseline
- [x] 1.1 Разобрать read/render цепочку, существующие лимиты, auth/context/cache semantics и составить карту вызовов.
- [x] 1.2 Подготовить изолированные fixtures и воспроизводимую матрицу с production/dev разделением, прогревом и повторениями.
- [x] 1.3 Снять baseline HTTP/downstream/SQL, payload, waterfall, render/CPU/memory и времени полезной отрисовки; очистить диагностические данные.

## 2. Исследование запросов и данных
- [x] 2.1 Подтвердить или опровергнуть N+1 и дублирование по слоям и росту N; отличить уникальные чтения и auth cost от повторяемых lookup.
- [x] 2.2 Проверить overfetching, scope/time bounds, paging/aggregation/top-N и graph point budgets без потери смысловой полноты.
- [x] 2.3 Проверить batch/dedup/cache/TTL/invalidation, fanout/concurrency, polling/retry/cancellation/late-response и connection reuse.
- [x] 2.4 Проверить SQL-планы/индексы/ORM, rows read vs returned, pools/queues и повтор backend вычислений; отметить непроверенные участки.

## 3. Исследование отрисовки и UX
- [x] 3.1 Проверить JS/chunks/cache delivery и React/DOM/chart transforms/render/layout/animation/memory по стандартному чеклисту.
- [x] 3.2 Сравнить сохранение canvas, повторное использование раскладки/evidence и progressive skeleton; описать first-load/refresh/navigation/context/revision states.
- [x] 3.3 Проверить рекомендации на медленных/ошибочных источниках, смене контекста/аккаунта и отзыве доступа; сохранить stale/partial semantics и независимость панелей.

## 4. Итог и готовность к внедрению
- [x] 4.1 Выпустить docs/current/flows/dashboard-optimization-research.md с baseline, evidence, заполненным чеклистом, negative findings и ограничениями; обновить ближайший индекс.
- [x] 4.2 Составить приоритизированный список требуемых оптимизаций с реализацией, альтернативами, измеримым эффектом, рисками, effort/dependencies и acceptance/regression checks.
- [x] 4.3 Выделить последующие implementation changes и отклонённые/неподтверждённые оптимизации; проверить согласованность с действующими контрактами.
- [x] 4.4 Проверить воспроизводимость отчёта и отсутствие секретов/продуктовых изменений, выполнить strict OpenSpec validation; архивировать только после завершения исследования.
