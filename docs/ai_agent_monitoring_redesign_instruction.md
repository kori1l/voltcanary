# Инструкция для AI-агента: доработка экрана Device Monitoring / Analytics

## 0. Цель задачи

Нужно переделать экран устройства так, чтобы он выглядел как **компактный monitoring / analytics dashboard**, а не как длинная лента разрозненных карточек.

Пользователь должен за 5–10 секунд понять:

1. Что случилось.
2. Насколько это важно.
3. Какие данные это подтверждают.
4. Что нужно сделать.
5. Где посмотреть технические детали.

Главная проблема текущего интерфейса: информация занимает слишком много вертикального пространства, блоки повторяют друг друга, telemetry-графики находятся слишком низко, а аналитические выводы размазаны по странице.

---

## 1. Основной принцип редизайна

### Было

Текущая структура похожа на длинный отчет:

```text
Header
Large alert banner
Tabs
KPI cards
Why this matters
Next action
Telemetry monitoring
Event timeline
Risk analysis
Technical details
```

### Должно стать

Сделать компактную рабочую область:

```text
Header
Compact incident summary
KPI row
Tabs
Main dashboard area:
  Left column 70%:
    Telemetry monitoring
    Event timeline
  Right column 30%:
    Risk analysis
    Why this matters
    Recommended action
    Technical details collapsed
```

Основной смысл: **графики и события должны быть ближе к началу страницы**, а объяснения и рекомендации должны быть компактно собраны справа.

---

## 2. Целевая структура страницы

Использовать примерно такую структуру:

```text
┌──────────────────────────────────────────────────────────────┐
│ Devices / Test                          [Refresh] [Recalculate]│
│ Kitchen · Active · Last telemetry 4:56 PM                     │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ [Low risk] Power spike detected                              │
│ 4 events in last 15m · Peak 128 W · Current 120.04 W          │
│ Likely related to device load. Voltage is stable.             │
│                                      [View alerts] [Checked]  │
└──────────────────────────────────────────────────────────────┘

┌──────────────┬──────────────┬──────────────┬──────────────┐
│ Active power │ Risk score   │ Last activity│ Samples      │
│ 120.04 W     │ 30/100 Low   │ 4:56 PM      │ 129          │
└──────────────┴──────────────┴──────────────┴──────────────┘

Tabs: Overview | Telemetry | Analytics | Alerts | Simulation

┌──────────────────────────────────────────┬──────────────────┐
│ Telemetry monitoring                     │ Risk analysis    │
│ [15m] [1h] [6h] [24h] [7d] [custom]      │ 30/100 · Low     │
│ Metrics: 5 · Points: Off · Detail: Compact│ Main reasons     │
│                                          │ Recommended      │
│ [Active power chart]                     │ action           │
│ [Voltage chart]                          │ Why this matters │
├──────────────────────────────────────────┤                  │
│ Event timeline                           │ ▸ Technical      │
│ 4:56 PM  Active power warning  123 W     │ details          │
│ 4:51 PM  Active power warning  126 W     │                  │
└──────────────────────────────────────────┴──────────────────┘
```

---

## 3. Header

### Что сделать

Оставить header компактным и не перегружать его аналитикой.

### Требуемый формат

```text
Devices / Test
Kitchen · Active · Last telemetry: 4:56 PM
[Refresh device] [Recalculate risk]
```

### Требования

- Высота header: не больше 80–90 px.
- Кнопки оставить справа.
- Header должен содержать только навигационный контекст и действия верхнего уровня.
- Не добавлять в header KPI-карточки, risk score или длинные alert-тексты.

---

## 4. Compact incident summary вместо большого alert banner

### Проблема сейчас

Большой alert banner занимает слишком много места и повторяет generic-текст:

```text
Low risk - Power spike
A power spike was detected above the normal range...
```

### Что сделать

Заменить большой banner на компактный incident summary.

### Новый формат

```text
[Low risk] Power spike detected
4 events in last 15m · Peak 128 W · Current value 120.04 W
Likely related to device load. Voltage is stable.

[View alerts] [Mark as checked]
```

### Требования

- Высота блока: 88–120 px.
- Заголовок в одну строку.
- Summary максимум 1–2 строки.
- Кнопки справа.
- Убрать длинный paragraph на всю ширину.
- Убрать повторяющиеся фразы.
- Если риск Low, не делать блок визуально тревожным как Critical.
- Если риск Warning/Critical, усилить визуальный акцент цветом и badge.

### Данные для incident summary

Использовать:

- riskLevel: `Low | Medium | High | Critical`
- incidentType: например `Power spike detected`
- eventCount: количество событий в выбранном диапазоне
- timeRange: например `last 15m`
- peakValue: максимум проблемной метрики
- currentValue: последнее значение проблемной метрики
- shortConclusion: короткий аналитический вывод

---

## 5. KPI row

### Что сделать

Сделать компактную строку KPI сразу после incident summary.

### Формат

```text
Active power: 120.04 W
Risk score: 30 / 100 · Low
Last activity: 4:56 PM
Samples analyzed: 129
```

### Требования

- 4 KPI в одну строку на desktop.
- Высота KPI-карточки: 72–88 px.
- Не добавлять длинные описания в KPI.
- Risk score должен быть визуально связан с risk level.
- KPI не должны дублировать текст incident summary.

### Рекомендованные KPI

1. Problem metric value: `Active power 120.04 W`
2. Risk score: `30 / 100 · Low`
3. Last activity: `4:56 PM`
4. Samples analyzed: `129`

---

## 6. Tabs

### Что сделать

Оставить tabs, но сделать их компактными и не создавать большой разрыв между summary и dashboard.

### Требования

- Tabs расположить после KPI row.
- Высота tabs: 40–48 px.
- Вертикальный отступ перед/после tabs: 12–16 px.
- Активный tab должен быть заметным, но не слишком тяжелым.

---

## 7. Main dashboard layout

### Что сделать

Заменить вертикальную ленту блоков на двухколоночную рабочую область.

### Desktop layout

```text
Left column: 68–72%
Right column: 28–32%
Gap: 16–20 px
```

### Left column

Содержит:

1. Telemetry monitoring.
2. Event timeline.

### Right column

Содержит:

1. Risk analysis.
2. Why this matters.
3. Recommended action.
4. Technical details collapsed.

### Mobile / narrow screens

На узких экранах колонки складываются в одну:

```text
Incident summary
KPI row
Telemetry monitoring
Risk analysis
Why this matters
Recommended action
Event timeline
Technical details
```

---

## 8. Telemetry monitoring

### Что сделать

Telemetry должен быть главным доказательным блоком и находиться высоко на странице.

### Новый формат блока

```text
Telemetry monitoring
Device health: Warning · Active power issue
Last sample: May 20, 4:56 PM · Data quality: good · Range: 15m

[15m] [1h] [6h] [24h] [7d] [custom]   Metrics: 5   Points: Off   Detail: Compact

[Active power chart]
[Voltage chart]
```

### Требования

- Telemetry должен быть виден на первом экране или почти на первом экране.
- Убрать отдельные большие карточки `Last sample / Data quality / Active alerts / Range`, если эти данные уже есть в KPI или summary.
- Controls должны быть компактной toolbar-строкой, а не отдельной большой карточкой.
- Не повторять длинный alert message внутри telemetry.
- Telemetry block должен занимать основное пространство left column.

---

## 9. Active power chart

### Проблема

Если Y-axis слишком широкий, spike визуально не выглядит как spike.

### Что сделать

Исправить масштаб и добавить аналитические элементы.

### Требования к Y-axis

- Если мощность не может быть отрицательной, `Y min = 0`.
- Не показывать отрицательные значения W.
- `Y max` не должен быть сильно выше фактического максимума.
- Рекомендованная формула:

```text
Y max = max(maxValue * 1.15, warningThreshold * 1.3)
```

### Требования к графику

- Добавить horizontal line: `Warning threshold`.
- Добавить optional line/zone: `Typical range`.
- Отмечать event markers на пиках.
- Пики должны быть визуально заметными.
- При hover показывать tooltip:

```text
Time: 4:56 PM
Active power: 123 W
Threshold: 100 W
Status: Warning
```

---

## 10. Chart cards

### Что сделать

Сделать карточки графиков более аналитическими.

### Active power card

Показывать:

```text
Active power    Warning
4 spikes above 100 W threshold
Latest: 120.04 W · Peak: 128 W · Threshold: 100 W · Alerts: 4
[chart]
```

### Voltage card

Показывать:

```text
Voltage    Normal
Stable during power spikes
Latest: 229 V · Range: 229–231 V · Stability: Good · Alerts: 0
[chart]
```

### Требования

- Для проблемной метрики показывать: latest, peak, threshold, alerts.
- Для стабильной метрики показывать: latest, range, stability, alerts.
- Warning card должна визуально отличаться от Normal card.
- Не делать все метрики одинаковыми по смыслу.

---

## 11. Event timeline

### Проблема сейчас

Timeline занимает слишком много вертикального пространства, потому что каждое событие выглядит как большая карточка.

### Что сделать

Переделать timeline в компактные строки или таблицу.

### Новый формат

```text
Event timeline
Thresholds, spikes, stale data, and telemetry gaps in selected range.

Time       Event                  Value   Metric
4:56 PM    Active power warning   123 W   activePowerW
4:51 PM    Active power warning   126 W   activePowerW
4:46 PM    Active power warning   128 W   activePowerW
4:42 PM    Active power warning   122 W   activePowerW
```

### Требования

- Высота одной строки: 44–56 px.
- Не использовать большие карточки для каждого события.
- Показывать: time, event, value, metric.
- Можно оставить тонкую severity-полоску слева.
- Timeline должен занимать минимум в 2 раза меньше высоты, чем сейчас.
- При клике на событие подсвечивать соответствующую точку на графике.
- Если событий много, показывать первые 5–7 и кнопку `Show all`.

---

## 12. Risk analysis

### Проблема сейчас

Risk analysis занимает много места, score вынесен в отдельную большую карточку, reasons повторяют generic alert text.

### Что сделать

Сделать компактный аналитический блок в right column.

### Новый формат

```text
Risk analysis
30 / 100 · Low risk
Last calculated: May 19, 11:53 PM

Main reasons:
• 4 active power warnings in selected range
• Peak value reached 128 W
• No voltage instability detected

Recommendation:
Check connected load and monitor recurrence.
```

### Требования

- Не делать отдельную большую карточку только под `30 / 100`.
- Score должен быть рядом с заголовком или в компактном badge.
- Reasons — bullet-list.
- Recommendation — короткий текст.
- Убрать повторяющиеся generic alert messages.

---

## 13. Why this matters

### Проблема сейчас

Блок содержит повторяющиеся карточки с почти одинаковым текстом.

### Что сделать

Превратить в компактный bullet-list.

### Новый формат

```text
Why this matters
• Active power exceeded the expected range 4 times.
• Peak value reached 128 W.
• Latest value is still elevated: 120.04 W.
• Voltage remained stable, so the issue is likely load-related.
```

### Требования

- Максимум 3–5 пунктов.
- Не использовать карточку на каждый пункт.
- Не повторять один и тот же alert message.
- Это должен быть аналитический вывод, а не список сырых событий.

---

## 14. Recommended action

### Проблема сейчас

Next action занимает отдельную большую карточку ради одной рекомендации.

### Что сделать

Сделать компактный блок в right column.

### Новый формат

```text
Recommended action
Check connected load and power conditions.
If the spike repeats, inspect the power socket or operating mode.

[Mark as checked]
```

### Требования

- Высота блока: 100–140 px.
- Кнопка: `Mark as checked`.
- Текст максимум 2–3 строки.
- Не дублировать полный alert message.

---

## 15. Technical details

### Что сделать

Оставить technical details свернутым по умолчанию.

### Формат

```text
▸ Technical details
```

При раскрытии:

```text
Baseline
Threshold config
Detection rules
Raw telemetry metadata
```

### Требования

- По умолчанию collapsed.
- Не занимать место в основном сценарии.
- Использовать только для debug / advanced information.
- Не помещать туда информацию, критичную для понимания основного статуса.

---

## 16. Тексты и формулировки

### Правило

Не использовать generic-заглушки. Текст должен быть конкретным и аналитическим.

### Заменить такие фразы

Плохо:

```text
Active power is warning.
Active power needs attention.
A power spike was detected above the normal range.
Events are marked on charts and timeline.
```

Хорошо:

```text
Active power exceeded the expected range 4 times in the last 15 minutes.
Latest value is 120.04 W, peak value reached 128 W.
Power exceeded the 100 W threshold. Voltage stayed stable, so the issue is likely related to load.
```

### Требования

- Одна мысль не должна повторяться больше 1–2 раз на странице.
- Incident summary говорит, что произошло.
- Why this matters объясняет, почему это важно.
- Recommended action говорит, что делать.
- Timeline показывает, когда это случилось.
- Telemetry показывает данные.
- Risk analysis объясняет risk score.

---

## 17. Visual hierarchy

### Что сделать

Ввести 3 уровня важности.

### Level 1 — главное

- Incident summary.
- Telemetry chart.

### Level 2 — поддержка решения

- KPI row.
- Event timeline.
- Risk analysis.

### Level 3 — вспомогательное

- Why this matters.
- Recommended action.
- Technical details.

### Требования

- Level 1 визуально крупнее.
- Level 2 компактнее.
- Level 3 не должен спорить за внимание с графиками.
- Не использовать одинаковые heavy cards для всех блоков.

---

## 18. Spacing и размеры

Использовать более плотные отступы.

```text
Page horizontal padding: 24–32 px
Between major sections: 16–20 px
Inside cards: 12–16 px
Between title and content: 8 px
KPI card height: 72–88 px
Timeline row height: 44–56 px
Incident summary height: 88–120 px
Tabs height: 40–48 px
Dashboard column gap: 16–20 px
```

### Требования

- Уменьшить вертикальные отступы.
- Не делать большие пустые области внутри карточек.
- Не растягивать блоки без необходимости.
- Основная информация должна помещаться в первый экран на desktop.

---

## 19. Порядок реализации

### Step 1 — Refactor layout

1. Создать новую структуру страницы:
   - Header.
   - IncidentSummary.
   - KPI row.
   - Tabs.
   - Dashboard grid.
2. Сделать desktop two-column layout.
3. Сделать responsive fallback в одну колонку.

### Step 2 — Incident summary

1. Заменить большой alert banner на компактный incident summary.
2. Подключить реальные данные:
   - risk level;
   - event count;
   - peak value;
   - current value;
   - time range;
   - conclusion.
3. Убрать длинные повторяющиеся тексты.

### Step 3 — KPI row

1. Сжать KPI-карточки.
2. Оставить 4 KPI.
3. Убрать длинные descriptions.

### Step 4 — Telemetry block

1. Поднять telemetry выше.
2. Убрать дублирующие cards `Last sample / Data quality / Active alerts / Range`, если они уже есть сверху.
3. Сжать toolbar.
4. Сделать chart cards более аналитическими.

### Step 5 — Active power chart

1. Исправить Y-axis.
2. Добавить warning threshold line.
3. Добавить markers для событий.
4. Добавить tooltip.

### Step 6 — Right analytics panel

1. Собрать Risk analysis, Why this matters и Recommended action в правую колонку.
2. Сжать тексты.
3. Заменить repeated alert messages на bullet-list.

### Step 7 — Event timeline

1. Переделать timeline из больших карточек в compact rows/table.
2. Добавить columns: Time, Event, Value, Metric.
3. Добавить click interaction: событие подсвечивает точку на графике.
4. Если событий больше 7, добавить `Show all`.

### Step 8 — Technical details

1. Сделать секцию collapsed по умолчанию.
2. Перенести туда debug information.
3. Не показывать technical details как часть основного сценария.

### Step 9 — Cleanup texts and spacing

1. Удалить дублирующий текст.
2. Заменить generic messages на конкретные аналитические выводы.
3. Уменьшить отступы.
4. Проверить, что первый экран стал компактным.

---

## 20. Suggested component structure

Можно использовать такую компонентную структуру, независимо от фреймворка:

```text
DevicePage
  DeviceHeader
  IncidentSummary
  KpiRow
    KpiCard
  DeviceTabs
  MonitoringDashboardGrid
    MainColumn
      TelemetryPanel
        TelemetryToolbar
        MetricChartCard
        MetricChart
      EventTimeline
        EventTimelineRow
    SideColumn
      RiskAnalysisPanel
      WhyThisMattersPanel
      RecommendedActionPanel
      TechnicalDetailsAccordion
```

### Data preparation layer

Желательно вынести подготовку данных отдельно от UI:

```text
buildIncidentSummary(device, telemetry, events, risk)
buildKpiList(device, telemetry, risk)
buildRiskReasons(events, telemetry, thresholds)
buildWhyThisMatters(events, telemetry, correlations)
buildRecommendedAction(risk, incidentType, correlations)
buildTimelineRows(events)
buildChartAnnotations(events, thresholds)
```

UI-компоненты должны получать уже подготовленные данные, а не собирать аналитику внутри JSX/template.

---

## 21. Data model expectations

Минимальный набор данных для нового UI:

```ts
type RiskLevel = 'low' | 'medium' | 'high' | 'critical';

type IncidentSummary = {
  riskLevel: RiskLevel;
  title: string;
  eventCount: number;
  timeRangeLabel: string;
  peakValue: string;
  currentValue: string;
  conclusion: string;
};

type Kpi = {
  label: string;
  value: string;
  hint?: string;
  severity?: 'normal' | 'warning' | 'critical' | 'info';
};

type TimelineEvent = {
  id: string;
  timeLabel: string;
  title: string;
  value: string;
  metricKey: string;
  severity: 'normal' | 'warning' | 'critical';
  timestamp: number;
};

type ChartAnnotation = {
  type: 'threshold' | 'event' | 'range';
  label: string;
  value?: number;
  timestamp?: number;
  severity?: 'warning' | 'critical' | 'info';
};
```

---

## 22. Acceptance criteria

Экран считается успешно доработанным, если:

1. На первом экране без прокрутки видно:
   - название устройства;
   - risk level;
   - что случилось;
   - ключевые KPI;
   - telemetry-график или его начало.

2. Большой alert banner заменен на compact incident summary.

3. Одна и та же фраза про power spike не повторяется больше 1–2 раз.

4. Telemetry находится выше, чем event timeline и technical details.

5. Timeline занимает минимум в 2 раза меньше вертикального места, чем раньше.

6. Risk analysis помещается в компактный блок справа.

7. Active power warning визуально читается как warning:
   - есть threshold line;
   - пик заметен;
   - Y-axis не раздут;
   - нет отрицательных W, если они невозможны.

8. Why this matters — это bullet-list, а не набор одинаковых карточек.

9. Recommended action — компактный action block, а не большая отдельная секция.

10. Technical details свернут по умолчанию.

11. Интерфейс выглядит как monitoring dashboard, а не как длинный отчет.

---

## 23. Что не делать

Не делать следующее:

1. Не добавлять ещё больше карточек.
2. Не оставлять длинный вертикальный flow из всех блоков подряд.
3. Не повторять один и тот же alert message в incident summary, why this matters, risk analysis и next action.
4. Не держать telemetry ниже всех explanatory-блоков.
5. Не делать каждое событие timeline большой карточкой.
6. Не использовать generic messages без чисел и контекста.
7. Не делать все блоки одинаково важными визуально.
8. Не показывать technical details раскрытым по умолчанию.

---

## 24. Финальная проверка глазами пользователя

После реализации открыть страницу и ответить на вопросы:

1. Я сразу понимаю, что случилось?
2. Я вижу, насколько это серьезно?
3. Я вижу ключевые числа: current value, peak value, risk score, event count?
4. Я вижу график достаточно рано?
5. Я понимаю, почему система дала такой risk score?
6. Я понимаю, что делать дальше?
7. Страница выглядит компактной, а не размазанной?

Если хотя бы на один вопрос ответ «нет», нужно продолжить доработку layout, текстов или spacing.
