import type { SeriesRule } from "./types.js";

/**
 * Repeating reminders: the dates a rule produces and the instant each one happens. Pure functions,
 * no clock and no store. Dates are local calendar dates (YYYY-MM-DD) in the series' time zone.
 */

export const LOCAL_DATE_PATTERN = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;
export const LOCAL_TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;

/** Keep every occurrence in this window ahead of now, and at least `MIN_UPCOMING` of them. */
export const HORIZON_DAYS = 60;
export const MIN_UPCOMING = 2;

const DAY_MS = 24 * 60 * 60 * 1000;
/** Guards against a rule that never reaches its end (a far `until`, a huge interval). */
const MAX_STEPS = 50_000;
const LAST_YEAR = 2200;

export function isLocalDate(value: unknown): value is string {
  if (typeof value !== "string" || !LOCAL_DATE_PATTERN.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  return day <= daysInMonth(year, month);
}

export function isLocalTime(value: unknown): value is string {
  return typeof value === "string" && LOCAL_TIME_PATTERN.test(value);
}

export function addDays(date: string, days: number): string {
  return formatDate(parseDate(date) + days * DAY_MS);
}

/** ISO weekday of a local date: 1 = Monday to 7 = Sunday. */
export function isoWeekday(date: string): number {
  return ((new Date(parseDate(date)).getUTCDay() + 6) % 7) + 1;
}

/**
 * Every date the rule produces, ascending, from its anchor. `count` and `until` end it; `notBefore`
 * does not apply here because the count runs from the anchor. Monthly on the 29th to 31st falls on the
 * last day of shorter months; yearly on February 29 falls on February 28.
 */
export function* ruleDates(rule: SeriesRule): Generator<string> {
  const interval = Math.max(1, Math.trunc(rule.interval || 1));
  let produced = 0;
  const emit = (date: string): "stop" | "yield" => {
    if (rule.until && date > rule.until) return "stop";
    if (rule.count !== undefined && produced >= rule.count) return "stop";
    produced += 1;
    return "yield";
  };
  const start = rule.startDate;
  const [startYear, startMonth, startDay] = start.split("-").map(Number);

  for (let step = 0; step < MAX_STEPS; step += 1) {
    let candidates: string[];
    switch (rule.freq) {
      case "daily":
        candidates = [addDays(start, step * interval)];
        break;
      case "weekly": {
        const weekdays = normalizeWeekdays(rule.weekdays, start);
        const monday = addDays(start, 1 - isoWeekday(start));
        const weekStart = addDays(monday, step * interval * 7);
        candidates = weekdays.map((weekday) => addDays(weekStart, weekday - 1)).filter((date) => date >= start);
        break;
      }
      case "monthly": {
        const months = startMonth - 1 + step * interval;
        const year = startYear + Math.floor(months / 12);
        const month = (months % 12) + 1;
        candidates = [formatYmd(year, month, Math.min(startDay, daysInMonth(year, month)))];
        break;
      }
      case "yearly": {
        const year = startYear + step * interval;
        candidates = [formatYmd(year, startMonth, Math.min(startDay, daysInMonth(year, startMonth)))];
        break;
      }
    }
    for (const date of candidates) {
      if (Number(date.slice(0, 4)) > LAST_YEAR) return;
      if (emit(date) === "stop") return;
      yield date;
    }
  }
}

/** Whether `date` is one of the rule's dates and not before `notBefore`. */
export function ruleIncludes(rule: SeriesRule, date: string): boolean {
  if (rule.notBefore && date < rule.notBefore) return false;
  for (const candidate of ruleDates(rule)) {
    if (candidate === date) return true;
    if (candidate > date) return false;
  }
  return false;
}

/** How many of the rule's dates fall before `date` (used to carry a count across a split). */
export function countBefore(rule: SeriesRule, date: string): number {
  let count = 0;
  for (const candidate of ruleDates(rule)) {
    if (candidate >= date) break;
    count += 1;
  }
  return count;
}

export type PlannedOccurrence = { date: string; startAt: string };

/**
 * The occurrences to keep listed: every one from now through the horizon, and at least the next
 * `MIN_UPCOMING`, skipping deleted dates and those before `notBefore`.
 */
export function plannedOccurrences(
  rule: SeriesRule,
  timeZone: string,
  skippedDates: readonly string[],
  nowMs: number,
  horizonDays = HORIZON_DAYS,
  minUpcoming = MIN_UPCOMING,
): PlannedOccurrence[] {
  const skipped = new Set(skippedDates);
  const horizonEnd = nowMs + horizonDays * DAY_MS;
  // A date more than a day before now cannot be in the future in any zone.
  const earliest = formatDate(nowMs - 2 * DAY_MS);
  const planned: PlannedOccurrence[] = [];
  for (const date of ruleDates(rule)) {
    if (date < earliest) continue;
    if (rule.notBefore && date < rule.notBefore) continue;
    if (skipped.has(date)) continue;
    const instant = localDateTimeToUtc(date, rule.time, timeZone);
    if (instant < nowMs) continue;
    if (instant > horizonEnd && planned.length >= minUpcoming) break;
    planned.push({ date, startAt: new Date(instant).toISOString() });
  }
  return planned;
}

/** The local calendar date of an instant in a zone. */
export function localDateOf(instantMs: number, timeZone: string): string {
  const parts = zonedParts(instantMs, timeZone);
  return formatYmd(parts.year, parts.month, parts.day);
}

/**
 * The UTC instant of a local wall time. A time skipped by a daylight saving change (2:30 AM on the
 * spring change) moves forward by the gap; a repeated time (1:30 AM in the fall) takes the first one.
 */
export function localDateTimeToUtc(date: string, time: string, timeZone: string): number {
  const [year, month, day] = date.split("-").map(Number);
  const [hour, minute] = time.split(":").map(Number);
  const wall = Date.UTC(year, month - 1, day, hour, minute);
  const first = wall - offsetMs(wall, timeZone);
  const second = wall - offsetMs(first, timeZone);
  const matches = [first, second].filter((candidate) => wallClock(candidate, timeZone) === wall);
  if (matches.length > 0) return Math.min(...matches);
  return Math.max(first, second);
}

function normalizeWeekdays(weekdays: number[] | undefined, start: string): number[] {
  const values = [...new Set((weekdays ?? []).filter((value) => Number.isInteger(value) && value >= 1 && value <= 7))].sort((a, b) => a - b);
  return values.length > 0 ? values : [isoWeekday(start)];
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function zonedParts(instantMs: number, timeZone: string) {
  let formatter = formatters.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    if (formatters.size < 100) formatters.set(timeZone, formatter);
  }
  const values: Record<string, number> = {};
  for (const part of formatter.formatToParts(new Date(instantMs))) {
    if (part.type !== "literal") values[part.type] = Number(part.value);
  }
  return { year: values.year, month: values.month, day: values.day, hour: values.hour % 24, minute: values.minute, second: values.second };
}

/** The zone's wall clock at an instant, as if it were UTC, to the minute. */
function wallClock(instantMs: number, timeZone: string): number {
  const parts = zonedParts(instantMs, timeZone);
  return Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute);
}

function offsetMs(instantMs: number, timeZone: string): number {
  return wallClock(instantMs, timeZone) - Math.floor(instantMs / 60_000) * 60_000;
}

function parseDate(date: string): number {
  const [year, month, day] = date.split("-").map(Number);
  return Date.UTC(year, month - 1, day);
}

function formatDate(ms: number): string {
  const value = new Date(ms);
  return formatYmd(value.getUTCFullYear(), value.getUTCMonth() + 1, value.getUTCDate());
}

function formatYmd(year: number, month: number, day: number): string {
  return `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}
