import { Location } from "../devices/devicesApi";
export interface CreateDefaults {
  startAt: string;
  endAt: string;
  deviceIds: string[];
}

export interface TimelineSlot {
  start: Date;
  end: Date;
  label: string;
  subLabel: string;
}

export function startOfDay(date: Date) {
  const next = new Date(date);
  next.setHours(0, 0, 0, 0);
  return next;
}

export function startOfWeek(date: Date) {
  const next = startOfDay(date);
  const day = next.getDay() || 7;
  next.setDate(next.getDate() - day + 1);
  return next;
}

export function addDays(date: Date, days: number) {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

export function addHours(date: Date, hours: number) {
  const next = new Date(date);
  next.setHours(next.getHours() + hours);
  return next;
}

export function defaultsForCalendarSelection(deviceId: string, selectionStart: Date, selectionEnd: Date): CreateDefaults {
  const normalizedStart = selectionStart <= selectionEnd ? selectionStart : selectionEnd;
  const normalizedEnd = selectionStart <= selectionEnd ? selectionEnd : selectionStart;
  const start = new Date(normalizedStart);
  start.setHours(9, 0, 0, 0);
  const end = new Date(normalizedEnd);
  end.setHours(17, 0, 0, 0);
  if (end <= start) {
    end.setTime(addHours(start, 2).getTime());
  }
  return {
    startAt: toDateTimeLocalValue(start),
    endAt: toDateTimeLocalValue(end),
    deviceIds: [deviceId],
  };
}

export function daySpan(start: Date, end: Date) {
  return Math.max(1, Math.ceil((startOfDay(end).getTime() - startOfDay(start).getTime()) / 86400000));
}

export function buildTimelineSlots(start: Date, end: Date): TimelineSlot[] {
  const days = daySpan(start, end);
  if (days <= 21) {
    return buildDailySlots(start, end);
  }
  if (days <= 120) {
    return buildWeeklySlots(start, end);
  }
  return buildMonthlySlots(start, end);
}

export function buildDailySlots(start: Date, end: Date) {
  const slots: TimelineSlot[] = [];
  let cursor = startOfDay(start);
  const limit = startOfDay(end);
  while (cursor < limit) {
    slots.push({
      start: cursor,
      end: addDays(cursor, 1),
      label: weekdayShort(cursor),
      subLabel: cursor.toLocaleDateString([], { month: "short", day: "numeric" }),
    });
    cursor = addDays(cursor, 1);
  }
  return slots.length > 0 ? slots : [{
    start: startOfDay(start),
    end: addDays(startOfDay(start), 1),
    label: weekdayShort(start),
    subLabel: start.toLocaleDateString([], { month: "short", day: "numeric" }),
  }];
}

export function buildWeeklySlots(start: Date, end: Date) {
  const slots: TimelineSlot[] = [];
  let cursor = startOfDay(start);
  const limit = startOfDay(end);
  while (cursor < limit) {
    const next = new Date(Math.min(addDays(cursor, 7).getTime(), limit.getTime()));
    slots.push({
      start: cursor,
      end: next,
      label: formatCompactDate(cursor),
      subLabel: next > addDays(cursor, 1) ? `to ${formatCompactDate(addDays(next, -1))}` : "",
    });
    cursor = next;
  }
  return slots;
}

export function buildMonthlySlots(start: Date, end: Date) {
  const slots: TimelineSlot[] = [];
  let cursor = startOfDay(start);
  const limit = startOfDay(end);
  while (cursor < limit) {
    const monthEnd = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1);
    const next = new Date(Math.min(monthEnd.getTime(), limit.getTime()));
    slots.push({
      start: cursor,
      end: next,
      label: cursor.toLocaleDateString([], { month: "short", year: "numeric" }),
      subLabel: `${cursor.getDate()}-${addDays(next, -1).getDate()}`,
    });
    cursor = next;
  }
  return slots;
}

export function columnMinWidth(slotCount: number) {
  if (slotCount <= 10) return 116;
  if (slotCount <= 16) return 96;
  return 76;
}

export function formatCompactDate(date: Date) {
  return date.toLocaleDateString([], { month: "short", day: "numeric" });
}

export function parseDateInput(value: string) {
  if (!value) return null;
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) return null;
  return startOfDay(new Date(year, month - 1, day));
}

export function toDateInputValue(date: Date) {
  return toDateTimeLocalValue(date).slice(0, 10);
}

export function toDateTimeLocalValue(date: Date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

export function toInstant(value: string) {
  return new Date(value).toISOString();
}

export function formatDateRange(start: Date, end: Date) {
  return `${start.toLocaleDateString([], { month: "short", day: "numeric" })} - ${end.toLocaleDateString([], { month: "short", day: "numeric", year: "numeric" })}`;
}

export function formatShortDateTime(value: string) {
  return new Date(value).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

export function formatTimeRange(start: Date, end: Date) {
  return `${start.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })} - ${end.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}`;
}

export function weekdayShort(date: Date) {
  return date.toLocaleDateString([], { weekday: "short" });
}

export function formatEnum(value: string) {
  return value.toLowerCase().split("_").map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join(" ");
}

export function locationLabel(location: Location) {
  return [location.name, location.building, location.floor ? `Floor ${location.floor}` : null, location.room].filter(Boolean).join(" - ");
}

export function rangesOverlap(leftStart: Date, leftEnd: Date, rightStart: Date, rightEnd: Date) {
  return leftStart < rightEnd && rightStart < leftEnd;
}
