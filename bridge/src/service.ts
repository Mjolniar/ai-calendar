import { createHash, randomUUID } from "node:crypto";
import type { CalendarStore } from "./store.js";
import type { DeviceNotifier } from "./notifier.js";
import type {
  EventDomain,
  EventInput,
  ReminderEvent,
  ReminderSeries,
  SeriesInput,
  SeriesRule,
  SeriesUpdateInput,
  TaskPublishInput,
} from "./types.js";
import { addDays, countBefore, isLocalDate, isLocalTime, plannedOccurrences, ruleIncludes } from "./recurrence.js";

const NOTIFY_TIMEOUT_MS = 10_000;
const PUBLISH_CONCURRENCY = 8;

/** Invalid caller input. HTTP routes map it to 400; MCP returns it as a tool error. */
export class InputError extends Error {
  readonly status = 400;
}

export type PublishedTaskReminder = {
  itemKey: string;
  status: "created" | "updated" | "unchanged";
  event: ReminderEvent;
};

export class CalendarService {
  constructor(
    private readonly store: CalendarStore,
    private readonly notifier: DeviceNotifier,
    private readonly clock: () => number = Date.now,
  ) {}

  async list(includeDone = false): Promise<ReminderEvent[]> {
    return (await this.store.listEvents(includeDone))
      .filter((event) => !event.deletedAt)
      .map(normalizeStoredEvent);
  }

  async get(id: string): Promise<ReminderEvent | null> {
    const event = await this.store.getEvent(id);
    return event && !event.deletedAt ? normalizeStoredEvent(event) : null;
  }

  async create(input: EventInput): Promise<{ event: ReminderEvent; created: boolean; notified: number }> {
    validateEventFields(input, true);
    if (input.idempotencyKey) {
      // Events created before deterministic idempotency IDs are still found by query.
      const existing = await this.store.findEventByIdempotencyKey(input.idempotencyKey);
      if (existing && !existing.deletedAt) return { event: normalizeStoredEvent(existing), created: false, notified: 0 };
    }
    const now = new Date().toISOString();
    const critical = input.critical ?? false;
    const domain = resolveInputDomain(input);
    const event: ReminderEvent = {
      // A deterministic ID makes concurrent retries with one idempotency key collide on one document.
      id: input.idempotencyKey ? idempotentEventId(input.idempotencyKey) : randomUUID(),
      title: input.title.trim(),
      startAt: new Date(input.startAt).toISOString(),
      timeZone: input.timeZone,
      location: input.location?.trim() ?? "",
      notes: input.notes?.trim() ?? "",
      critical,
      reminderOffsetsMinutes: alertOffsets(domain, input.reminderOffsetsMinutes, critical),
      done: false,
      domain,
      category: input.category ?? "automatic",
      source: "chatgpt",
      revision: Date.now(),
      createdAt: now,
      updatedAt: now,
      ...(input.idempotencyKey ? { idempotencyKey: input.idempotencyKey } : {}),
    };
    const { before } = await this.store.mutateEvent(event.id, (current) => current && !current.deletedAt ? null : event);
    if (before && !before.deletedAt) return { event: normalizeStoredEvent(before), created: false, notified: 0 };
    const notified = await this.notify(event.revision);
    return { event, created: true, notified };
  }

  async publishTaskReminders(input: TaskPublishInput): Promise<{
    taskKey: string;
    created: number;
    updated: number;
    unchanged: number;
    notified: number;
    reminders: PublishedTaskReminder[];
    suppressed?: number;
    suppressedItemKeys?: string[];
  }> {
    const taskKey = input.taskKey.trim();
    const now = new Date().toISOString();
    input.reminders.forEach((incoming) => validateEventFields(incoming, true));
    const outcomes: Array<PublishedTaskReminder | { itemKey: string; status: "suppressed" }> = new Array(input.reminders.length);

    // Items that share an ID are applied in order by one worker; distinct IDs run concurrently.
    const groups = new Map<string, number[]>();
    input.reminders.forEach((incoming, index) => {
      const id = taskReminderId(taskKey, incoming.itemKey.trim());
      groups.set(id, [...(groups.get(id) ?? []), index]);
    });
    await forEachConcurrent([...groups.entries()], PUBLISH_CONCURRENCY, async ([id, indexes]) => {
      for (const index of indexes) {
        outcomes[index] = await this.publishOne(id, taskKey, input, input.reminders[index], now);
      }
    });

    const reminders = outcomes.filter((item): item is PublishedTaskReminder => item.status !== "suppressed");
    const suppressedItemKeys = outcomes.filter((item) => item.status === "suppressed").map((item) => item.itemKey);
    const changed = reminders.some((item) => item.status !== "unchanged");

    const notified = changed ? await this.notify(Date.now()) : 0;
    return {
      taskKey,
      created: reminders.filter((item) => item.status === "created").length,
      updated: reminders.filter((item) => item.status === "updated").length,
      unchanged: reminders.filter((item) => item.status === "unchanged").length,
      notified,
      reminders,
      ...(suppressedItemKeys.length > 0 ? { suppressed: suppressedItemKeys.length, suppressedItemKeys } : {}),
    };
  }

  private async publishOne(
    id: string,
    taskKey: string,
    input: TaskPublishInput,
    incoming: TaskPublishInput["reminders"][number],
    now: string,
  ): Promise<PublishedTaskReminder | { itemKey: string; status: "suppressed" }> {
    const itemKey = incoming.itemKey.trim();
    let outcome: PublishedTaskReminder | { itemKey: string; status: "suppressed" } = { itemKey, status: "suppressed" };
    await this.store.mutateEvent(id, (stored) => {
      if (stored?.deletedAt && !input.restoreDeleted) {
        outcome = { itemKey, status: "suppressed" };
        return null;
      }
      const current = stored && !stored.deletedAt ? normalizeStoredEvent(stored) : null;
      const critical = incoming.critical ?? false;
      const domain = resolveInputDomain(incoming, current);
      const routineSchoolMeeting = (incoming.source ?? current?.source ?? "chatgpt") !== "canvas"
        && isRoutineSchoolMeeting(incoming);
      const candidate: ReminderEvent = {
        id,
        title: incoming.title.trim(),
        startAt: new Date(incoming.startAt).toISOString(),
        timeZone: incoming.timeZone,
        location: incoming.location?.trim() ?? "",
        notes: incoming.notes?.trim() ?? "",
        critical,
        reminderOffsetsMinutes: alertOffsets(domain, incoming.reminderOffsetsMinutes, critical),
        done: routineSchoolMeeting || current?.done || false,
        domain,
        category: incoming.category ?? current?.category ?? "automatic",
        source: incoming.source ?? current?.source ?? "chatgpt",
        ...((incoming.sourceUrl || current?.sourceUrl)
          ? { sourceUrl: incoming.sourceUrl || current?.sourceUrl }
          : {}),
        ...schoolMetadata(incoming, current),
        revision: current?.revision ?? Date.now(),
        createdAt: current?.createdAt ?? now,
        updatedAt: current?.updatedAt ?? now,
        sourceTaskKey: taskKey,
        sourceItemKey: itemKey,
        ...((input.runId?.trim() || current?.sourceRunId)
          ? { sourceRunId: input.runId?.trim() || current?.sourceRunId }
          : {}),
      };
      const status = !current ? "created" : sameReminder(current, candidate) ? "unchanged" : "updated";
      if (status === "unchanged") {
        outcome = { itemKey, status, event: current! };
        return null;
      }
      candidate.revision = Date.now();
      candidate.updatedAt = now;
      outcome = { itemKey, status, event: candidate };
      return candidate;
    });
    return outcome;
  }

  async update(id: string, input: Partial<EventInput> & { done?: boolean }): Promise<{ event: ReminderEvent; notified: number } | null> {
    validateEventFields(input, false);
    const { after } = await this.store.mutateEvent(id, (stored) => {
      if (!stored || stored.deletedAt) return null;
      const current = normalizeStoredEvent(stored);
      const critical = input.critical ?? current.critical;
      const domain = input.domain
        ?? (input.category === "delivery" ? "delivery" : undefined)
        ?? current.domain
        ?? normalizeDomain(undefined, input.category ?? current.category);
      // A reminder that stops being a delivery gets the usual alerts back unless the caller names its own.
      const leavingDeliveries = current.domain === "delivery" && domain !== "delivery";
      const next: ReminderEvent = {
      ...current,
      title: input.title?.trim() ?? current.title,
      startAt: input.startAt ? new Date(input.startAt).toISOString() : current.startAt,
      timeZone: input.timeZone ?? current.timeZone,
      location: input.location?.trim() ?? current.location,
      notes: input.notes?.trim() ?? current.notes,
      critical,
      reminderOffsetsMinutes: domain === "delivery" ? []
        : input.reminderOffsetsMinutes ? normalizeOffsets(input.reminderOffsetsMinutes, critical)
        : leavingDeliveries ? normalizeOffsets(undefined, critical)
        : current.reminderOffsetsMinutes,
      done: input.done ?? current.done,
      domain,
      category: input.category ?? current.category ?? "automatic",
      revision: Date.now(),
      updatedAt: new Date().toISOString(),
      };
      // An occurrence edited on its own ("this one") keeps its edit when the series is expanded
      // again. Completing it is not an edit.
      if (current.seriesId && !current.detached && !sameOccurrenceContent(current, next)) next.detached = true;
      return next;
    });
    if (!after) return null;
    const notified = await this.notify(after.revision);
    return { event: after, notified };
  }

  async setTaskItemsDone(taskKey: string, itemKeys: Set<string>): Promise<{ updated: number; notified: number }> {
    if (itemKeys.size === 0) return { updated: 0, notified: 0 };
    const events = await this.list(true);
    const now = new Date().toISOString();
    const revision = Date.now();
    const matches = events.filter((event) =>
      event.sourceTaskKey === taskKey
      && event.sourceItemKey
      && itemKeys.has(event.sourceItemKey)
      && !event.done,
    );
    const updated = await this.markDone(matches.map((event) => event.id), revision, now);
    return {
      updated,
      notified: updated > 0 ? await this.notify(revision) : 0,
    };
  }

  async archiveRoutineSchoolMeetings(): Promise<{ updated: number; notified: number }> {
    const events = await this.list(true);
    // Occurrences of a series the user made are intended, whatever their wording.
    const matches = events.filter((event) => !event.done && event.source !== "canvas" && !event.seriesId && isRoutineSchoolMeeting(event));
    if (matches.length === 0) return { updated: 0, notified: 0 };
    const now = new Date().toISOString();
    const revision = Date.now();
    const updated = await this.markDone(matches.map((event) => event.id), revision, now);
    return { updated, notified: updated > 0 ? await this.notify(revision) : 0 };
  }

  /** Transactionally sets done=true, so a concurrent edit is never overwritten with a stale copy. */
  private async markDone(ids: string[], revision: number, now: string): Promise<number> {
    let updated = 0;
    await forEachConcurrent(ids, PUBLISH_CONCURRENCY, async (id) => {
      const { after } = await this.store.mutateEvent(id, (stored) => (!stored || stored.deletedAt || stored.done)
        ? null
        : { ...normalizeStoredEvent(stored), done: true, revision, updatedAt: now });
      if (after) updated += 1;
    });
    return updated;
  }

  async delete(id: string): Promise<{ deleted: boolean; notified: number }> {
    const existing = await this.store.getEvent(id);
    if (!existing || existing.deletedAt) return { deleted: false, notified: 0 };
    let deleted: boolean;
    if (existing.seriesId && existing.occurrenceDate) {
      // Remember the date first, so the next expansion cannot bring the occurrence back.
      const date = existing.occurrenceDate;
      const now = this.clock();
      await this.store.mutateSeries(existing.seriesId, (series) => (!series || series.skippedDates.includes(date))
        ? null
        : { ...series, skippedDates: [...series.skippedDates, date].sort(), revision: now, updatedAt: new Date(now).toISOString() });
      deleted = await this.store.deleteEvent(id);
    } else if (existing.sourceTaskKey && existing.sourceItemKey) {
      // Task items are re-published by stable key; keep a tombstone so deletion is durable.
      const revision = Date.now();
      const deletedAt = new Date(revision).toISOString();
      const { after } = await this.store.mutateEvent(id, (stored) => (!stored || stored.deletedAt)
        ? null
        : { ...stored, done: true, revision, updatedAt: deletedAt, deletedAt });
      deleted = after !== null;
    } else {
      deleted = await this.store.deleteEvent(id);
    }
    return { deleted, notified: deleted ? await this.notify(Date.now()) : 0 };
  }

  async listSeries(): Promise<ReminderSeries[]> {
    return (await this.store.listSeries())
      .filter((series) => !series.deletedAt)
      .sort((a, b) => a.title.localeCompare(b.title) || a.id.localeCompare(b.id));
  }

  async getSeries(id: string): Promise<ReminderSeries | null> {
    const series = await this.store.getSeries(id);
    return series && !series.deletedAt ? series : null;
  }

  /** What `/v1/sync` returns: every reminder, the live series, and the highest change counter of both. */
  async syncSnapshot(): Promise<{ revision: number; events: ReminderEvent[]; series: ReminderSeries[] }> {
    const [events, series] = await Promise.all([this.list(true), this.listSeries()]);
    const revision = Math.max(0, ...events.map((event) => event.revision), ...series.map((item) => item.revision ?? 0));
    return { revision, events, series };
  }

  async createSeries(input: SeriesInput): Promise<{ series: ReminderSeries; created: boolean; occurrences: ReminderEvent[]; notified: number }> {
    validateSeriesFields(input, true);
    const rule = buildRule(input.rule);
    const id = input.idempotencyKey ? seriesIdFor(input.idempotencyKey) : randomUUID();
    const now = this.clock();
    const nowIso = new Date(now).toISOString();
    const critical = input.critical ?? false;
    const series: ReminderSeries = {
      id,
      title: input.title.trim(),
      notes: input.notes?.trim() ?? "",
      location: input.location?.trim() ?? "",
      domain: seriesDomain(input),
      category: input.category ?? "automatic",
      critical,
      reminderOffsetsMinutes: normalizeOffsets(input.reminderOffsetsMinutes, critical),
      timeZone: input.timeZone,
      rule,
      skippedDates: [],
      revision: now,
      createdAt: nowIso,
      updatedAt: nowIso,
      ...(input.idempotencyKey ? { idempotencyKey: input.idempotencyKey } : {}),
    };
    const { before } = await this.store.mutateSeries(id, (current) => current && !current.deletedAt ? null : series);
    if (before && !before.deletedAt) {
      return { series: before, created: false, occurrences: await this.occurrencesOf(id), notified: 0 };
    }
    const expanded = await this.expandSeries(series, await this.store.listEvents(true));
    return { series, created: true, occurrences: expanded.occurrences, notified: await this.notify(now) };
  }

  /**
   * Changes a series. Without `from`, or with `from` on or before its first date, the whole series
   * changes. With a later `from` ("this and all future ones") the series ends the day before and a
   * new series carries the change from that date. Either way, future occurrences that are not done
   * and were not edited on their own are rewritten; past and done ones stay as they are.
   */
  async updateSeries(id: string, input: SeriesUpdateInput): Promise<{
    series: ReminderSeries;
    previous?: ReminderSeries;
    occurrences: ReminderEvent[];
    notified: number;
  } | null> {
    validateSeriesFields(input, false);
    if (input.from !== undefined && !isLocalDate(input.from)) throw new InputError("from must be a date as YYYY-MM-DD");
    const old = await this.getSeries(id);
    if (!old) return null;
    const now = this.clock();
    const nowIso = new Date(now).toISOString();
    const fields = seriesFields(old, input);

    if (!input.from || input.from <= firstDateOf(old.rule)) {
      const series: ReminderSeries = { ...old, ...fields, rule: mergeRule(old.rule, input.rule), revision: now, updatedAt: nowIso };
      await this.store.mutateSeries(id, (current) => current && !current.deletedAt ? series : null);
      const expanded = await this.expandSeries(series, await this.store.listEvents(true));
      return { series, occurrences: expanded.occurrences, notified: await this.notify(now) };
    }

    const from = input.from;
    const newId = input.idempotencyKey ? seriesIdFor(input.idempotencyKey) : randomUUID();
    if (input.idempotencyKey) {
      // A retried split must not end the new part with the old part's shortened rule.
      const existing = await this.getSeries(newId);
      if (existing) return { series: existing, previous: old, occurrences: await this.occurrencesOf(newId), notified: 0 };
    }
    const dayBefore = addDays(from, -1);
    const ended: ReminderSeries = {
      ...old,
      rule: { ...old.rule, until: old.rule.until && old.rule.until < dayBefore ? old.rule.until : dayBefore },
      revision: now,
      updatedAt: nowIso,
    };
    const rule = mergeRule(old.rule, input.rule);
    if (input.rule?.startDate === undefined) {
      // Same anchor: week parity, the day of the month and the count carry over unchanged.
      rule.notBefore = old.rule.notBefore && old.rule.notBefore > from ? old.rule.notBefore : from;
    } else {
      if (rule.startDate < from) rule.notBefore = from;
      else delete rule.notBefore;
      if (old.rule.count !== undefined && input.rule.count === undefined) {
        const remaining = old.rule.count - countBefore(old.rule, from);
        if (remaining < 1) {
          // Nothing of the series is left from that date: ending it is the whole change.
          await this.store.mutateSeries(id, (current) => current && !current.deletedAt ? ended : null);
          await this.expandSeries(ended, await this.store.listEvents(true));
          return { series: ended, previous: ended, occurrences: [], notified: await this.notify(now) };
        }
        rule.count = remaining;
      }
    }
    const { idempotencyKey: _oldKey, ...rest } = old;
    const events = await this.store.listEvents(true);
    // A date the old series already shows as its own edited occurrence must not appear twice.
    const keptDates = events
      .filter((event) => event.seriesId === id && event.detached && !event.deletedAt && (event.occurrenceDate ?? "") >= from)
      .map((event) => event.occurrenceDate!);
    const series: ReminderSeries = {
      ...rest,
      ...fields,
      id: newId,
      rule,
      skippedDates: [...new Set([...old.skippedDates.filter((date) => date >= from), ...keptDates])].sort(),
      revision: now,
      createdAt: nowIso,
      updatedAt: nowIso,
      ...(input.idempotencyKey ? { idempotencyKey: input.idempotencyKey } : {}),
    };
    await this.store.mutateSeries(id, (current) => current && !current.deletedAt ? ended : null);
    await this.store.mutateSeries(newId, () => series);
    await this.expandSeries(ended, events);
    const expanded = await this.expandSeries(series, events);
    return { series, previous: ended, occurrences: expanded.occurrences, notified: await this.notify(now) };
  }

  /**
   * Deletes a series. Without `from` (or from its first date) the whole series goes with its future
   * occurrences that are not done; past and done ones are kept. With a later `from`, the series ends
   * the day before and every unfinished occurrence from that date on is removed.
   */
  async deleteSeries(id: string, from?: string): Promise<{ deleted: boolean; removed: number; notified: number }> {
    if (from !== undefined && !isLocalDate(from)) throw new InputError("from must be a date as YYYY-MM-DD");
    const old = await this.getSeries(id);
    if (!old) return { deleted: false, removed: 0, notified: 0 };
    const now = this.clock();
    const nowIso = new Date(now).toISOString();
    const open = (await this.store.listEvents(false)).filter((event) => event.seriesId === id && !event.deletedAt && !event.done);
    let doomed: ReminderEvent[];
    if (from && from > firstDateOf(old.rule)) {
      const dayBefore = addDays(from, -1);
      await this.store.mutateSeries(id, (current) => !current || current.deletedAt ? null : {
        ...current,
        rule: { ...current.rule, until: current.rule.until && current.rule.until < dayBefore ? current.rule.until : dayBefore },
        revision: now,
        updatedAt: nowIso,
      });
      doomed = open.filter((event) => (event.occurrenceDate ?? "") >= from);
    } else {
      await this.store.mutateSeries(id, (current) => !current || current.deletedAt ? null : { ...current, deletedAt: nowIso, revision: now, updatedAt: nowIso });
      doomed = open.filter((event) => Date.parse(event.startAt) >= now);
    }
    let removed = 0;
    await forEachConcurrent(doomed, PUBLISH_CONCURRENCY, async (event) => {
      if (await this.store.deleteEvent(event.id)) removed += 1;
    });
    return { deleted: true, removed, notified: await this.notify(now) };
  }

  /** Brings every series' occurrences up to the horizon. Runs with the scheduled Canvas sync. */
  async expandAllSeries(): Promise<{ series: number; created: number; updated: number; removed: number; notified: number }> {
    const [allSeries, events] = await Promise.all([this.listSeries(), this.store.listEvents(true)]);
    let created = 0;
    let updated = 0;
    let removed = 0;
    for (const series of allSeries) {
      const outcome = await this.expandSeries(series, events);
      created += outcome.created;
      updated += outcome.updated;
      removed += outcome.removed;
    }
    const changed = created + updated + removed > 0;
    return { series: allSeries.length, created, updated, removed, notified: changed ? await this.notify(this.clock()) : 0 };
  }

  private async occurrencesOf(seriesId: string): Promise<ReminderEvent[]> {
    return (await this.list(true)).filter((event) => event.seriesId === seriesId);
  }

  /**
   * Writes the series' planned occurrences and removes future ones the rule no longer produces.
   * Occurrences that are done, detached ("this one" edits) or past are never touched.
   */
  private async expandSeries(series: ReminderSeries, allEvents: ReminderEvent[]): Promise<{
    created: number;
    updated: number;
    removed: number;
    occurrences: ReminderEvent[];
  }> {
    const now = this.clock();
    const nowIso = new Date(now).toISOString();
    const planned = series.deletedAt ? [] : plannedOccurrences(series.rule, series.timeZone, series.skippedDates, now);
    let created = 0;
    let updated = 0;
    const occurrences: ReminderEvent[] = [];

    await forEachConcurrent(planned, PUBLISH_CONCURRENCY, async ({ date, startAt }) => {
      let status = "kept" as "created" | "updated" | "kept";
      const { before, after } = await this.store.mutateEvent(occurrenceId(series.id, date), (stored) => {
        if (stored && (stored.deletedAt || stored.done || stored.detached)) return null;
        const candidate = occurrenceFor(series, date, startAt, stored, now, nowIso);
        if (stored && sameOccurrenceContent(normalizeStoredEvent(stored), candidate)) return null;
        status = stored ? "updated" : "created";
        return candidate;
      });
      if (status === "created") created += 1;
      if (status === "updated") updated += 1;
      const current = after ?? before;
      if (current && !current.deletedAt) occurrences.push(normalizeStoredEvent(current));
    });

    const plannedDates = new Set(planned.map((item) => item.date));
    const stale = allEvents.filter((event) =>
      event.seriesId === series.id
      && event.occurrenceDate
      && !event.deletedAt
      && !event.done
      && !event.detached
      && Date.parse(event.startAt) >= now
      && !plannedDates.has(event.occurrenceDate)
      && (Boolean(series.deletedAt)
        || series.skippedDates.includes(event.occurrenceDate)
        || !ruleIncludes(series.rule, event.occurrenceDate)));
    let removed = 0;
    await forEachConcurrent(stale, PUBLISH_CONCURRENCY, async (event) => {
      if (await this.store.deleteEvent(event.id)) removed += 1;
    });

    occurrences.sort((a, b) => a.startAt.localeCompare(b.startAt));
    return { created, updated, removed, occurrences };
  }

  /** Push signaling is best effort: the data is already saved, so failures and slow FCM never fail the caller. */
  private async notify(revision: number): Promise<number> {
    let timer: NodeJS.Timeout | undefined;
    try {
      const devices = await this.store.listDevices();
      const timeout = new Promise<number>((_, reject) => {
        timer = setTimeout(() => reject(new Error("push signaling timed out")), NOTIFY_TIMEOUT_MS);
        timer.unref?.();
      });
      return await Promise.race([this.notifier.signalSync(devices, revision), timeout]);
    } catch (error) {
      console.error("Event saved, but push signaling failed", error instanceof Error ? error.message : error);
      return 0;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }
}

function seriesIdFor(key: string): string {
  return uuidFromSeed(`series\0${key}`);
}

/** Deterministic, so expanding again never duplicates an occurrence. */
function occurrenceId(seriesId: string, date: string): string {
  return uuidFromSeed(`occurrence\0${seriesId}\0${date}`);
}

function firstDateOf(rule: SeriesRule): string {
  return rule.notBefore && rule.notBefore > rule.startDate ? rule.notBefore : rule.startDate;
}

function occurrenceFor(
  series: ReminderSeries,
  date: string,
  startAt: string,
  stored: ReminderEvent | null,
  now: number,
  nowIso: string,
): ReminderEvent {
  return {
    id: occurrenceId(series.id, date),
    title: series.title,
    startAt,
    timeZone: series.timeZone,
    location: series.location,
    notes: series.notes,
    critical: series.critical,
    reminderOffsetsMinutes: [...series.reminderOffsetsMinutes],
    done: false,
    domain: series.domain,
    category: series.category,
    source: "chatgpt",
    revision: now,
    createdAt: stored?.createdAt ?? nowIso,
    updatedAt: nowIso,
    seriesId: series.id,
    occurrenceDate: date,
  };
}

/** The fields a person sees and edits. Equal means an edit changed nothing. */
function sameOccurrenceContent(left: ReminderEvent, right: ReminderEvent): boolean {
  return left.title === right.title
    && left.startAt === right.startAt
    && left.timeZone === right.timeZone
    && left.location === right.location
    && left.notes === right.notes
    && left.critical === right.critical
    && left.domain === right.domain
    && left.category === right.category
    && JSON.stringify(left.reminderOffsetsMinutes) === JSON.stringify(right.reminderOffsetsMinutes);
}

function seriesDomain(input: SeriesInput): EventDomain {
  return resolveInputDomain({ title: input.title, notes: input.notes, domain: input.domain, category: input.category, startAt: "", timeZone: "" });
}

function seriesFields(old: ReminderSeries, input: SeriesUpdateInput): Partial<ReminderSeries> {
  const critical = input.critical ?? old.critical;
  return {
    title: input.title?.trim() ?? old.title,
    notes: input.notes?.trim() ?? old.notes,
    location: input.location?.trim() ?? old.location,
    domain: input.domain ?? (input.category === "school" ? "school" : undefined) ?? old.domain,
    category: input.category ?? old.category,
    critical,
    reminderOffsetsMinutes: input.reminderOffsetsMinutes ? normalizeOffsets(input.reminderOffsetsMinutes, critical) : old.reminderOffsetsMinutes,
    timeZone: input.timeZone ?? old.timeZone,
  };
}

function buildRule(input: SeriesInput["rule"]): SeriesRule {
  const rule: SeriesRule = {
    freq: input.freq,
    interval: input.interval ?? 1,
    startDate: input.startDate,
    time: input.time,
    ...(input.freq === "weekly" && input.weekdays?.length ? { weekdays: [...new Set(input.weekdays)].sort((a, b) => a - b) } : {}),
    ...(input.until ? { until: input.until } : {}),
    ...(input.count !== undefined ? { count: input.count } : {}),
  };
  if (input.freq !== "weekly" && input.weekdays?.length) throw new InputError("rule.weekdays applies to weekly rules only");
  validateRule(rule);
  return rule;
}

function mergeRule(old: SeriesRule, patch: SeriesUpdateInput["rule"]): SeriesRule {
  if (!patch) return { ...old };
  const freq = patch.freq ?? old.freq;
  if (freq !== "weekly" && patch.weekdays?.length) throw new InputError("rule.weekdays applies to weekly rules only");
  const weekdays = freq !== "weekly" ? undefined : patch.weekdays ?? (old.freq === "weekly" ? old.weekdays : undefined);
  const until = patch.until === null ? undefined : patch.until ?? (patch.count != null ? undefined : old.until);
  const count = patch.count === null ? undefined : patch.count ?? (patch.until ? undefined : old.count);
  const rule: SeriesRule = {
    freq,
    interval: patch.interval ?? old.interval,
    startDate: patch.startDate ?? old.startDate,
    time: patch.time ?? old.time,
    ...(weekdays?.length ? { weekdays: [...new Set(weekdays)].sort((a, b) => a - b) } : {}),
    ...(until ? { until } : {}),
    ...(count !== undefined ? { count } : {}),
    ...(old.notBefore && patch.startDate === undefined ? { notBefore: old.notBefore } : {}),
  };
  validateRule(rule);
  return rule;
}

function validateRule(rule: SeriesRule): void {
  if (!["daily", "weekly", "monthly", "yearly"].includes(rule.freq)) throw new InputError("rule.freq must be daily, weekly, monthly or yearly");
  if (!Number.isInteger(rule.interval) || rule.interval < 1 || rule.interval > 999) throw new InputError("rule.interval must be a whole number from 1 to 999");
  if (!isLocalDate(rule.startDate)) throw new InputError("rule.startDate must be a date as YYYY-MM-DD");
  if (!isLocalTime(rule.time)) throw new InputError("rule.time must be a time as HH:mm");
  if (rule.weekdays !== undefined
    && (!Array.isArray(rule.weekdays) || rule.weekdays.length > 7 || !rule.weekdays.every((day) => Number.isInteger(day) && day >= 1 && day <= 7))) {
    throw new InputError("rule.weekdays must be ISO weekdays, 1 (Monday) to 7 (Sunday)");
  }
  if (rule.until !== undefined && !isLocalDate(rule.until)) throw new InputError("rule.until must be a date as YYYY-MM-DD");
  if (rule.until !== undefined && rule.until < rule.startDate) throw new InputError("rule.until must not be before rule.startDate");
  if (rule.count !== undefined && (!Number.isInteger(rule.count) || rule.count < 1 || rule.count > 1000)) throw new InputError("rule.count must be a whole number from 1 to 1000");
  if (rule.notBefore !== undefined && !isLocalDate(rule.notBefore)) throw new InputError("rule.notBefore must be a date as YYYY-MM-DD");
}

/** Shared validation of series input from every client. */
function validateSeriesFields(input: SeriesInput | SeriesUpdateInput, required: boolean): void {
  validateEventFields({ title: input.title, timeZone: input.timeZone }, false);
  if (required) {
    const rule = (input as SeriesInput).rule;
    validateEventFields({ title: input.title, timeZone: input.timeZone, startAt: new Date(0).toISOString() }, true);
    if (!rule || typeof rule !== "object") throw new InputError("rule is required");
    if (rule.until && rule.count !== undefined) throw new InputError("rule takes until or count, not both");
  } else if (input.rule) {
    if (input.rule.until && input.rule.count != null) throw new InputError("rule takes until or count, not both");
  }
  if (input.domain === "delivery" || input.category === "delivery") throw new InputError("Deliveries cannot repeat");
  const offsets = input.reminderOffsetsMinutes;
  if (offsets !== undefined
    && (!Array.isArray(offsets) || offsets.length > 20
      || !offsets.every((value) => typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 525600))) {
    throw new InputError("reminderOffsetsMinutes must be up to 20 numbers between 0 and 525600");
  }
}

function idempotentEventId(key: string): string {
  return uuidFromSeed(`idempotency\0${key}`);
}

function taskReminderId(taskKey: string, itemKey: string): string {
  return uuidFromSeed(`${taskKey}\0${itemKey}`);
}

function uuidFromSeed(seed: string): string {
  const hex = createHash("sha256").update(seed, "utf8").digest("hex").slice(0, 32).split("");
  hex[12] = "5";
  hex[16] = ((Number.parseInt(hex[16], 16) & 0x3) | 0x8).toString(16);
  const value = hex.join("");
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
}

function sameReminder(left: ReminderEvent, right: ReminderEvent): boolean {
  return left.title === right.title
    && left.startAt === right.startAt
    && left.timeZone === right.timeZone
    && left.location === right.location
    && left.notes === right.notes
    && left.critical === right.critical
    && left.done === right.done
    && (left.domain ?? normalizeDomain(undefined, left.category)) === right.domain
    && (left.category ?? "automatic") === right.category
    && (left.source ?? "chatgpt") === right.source
    && left.sourceUrl === right.sourceUrl
    && left.courseId === right.courseId
    && left.courseName === right.courseName
    && left.courseCode === right.courseCode
    && left.courseColorKey === right.courseColorKey
    && left.schoolItemType === right.schoolItemType
    && left.details === right.details
    && left.pointsPossible === right.pointsPossible
    && JSON.stringify(left.submissionTypes ?? []) === JSON.stringify(right.submissionTypes ?? [])
    && left.unlockAt === right.unlockAt
    && left.lockAt === right.lockAt
    && left.allowedAttempts === right.allowedAttempts
    && JSON.stringify(left.reminderOffsetsMinutes) === JSON.stringify(right.reminderOffsetsMinutes)
    && left.sourceTaskKey === right.sourceTaskKey
    && left.sourceItemKey === right.sourceItemKey;
}

function normalizeDomain(
  domain: EventInput["domain"],
  category: EventInput["category"],
): EventDomain {
  return domain ?? (category === "school" ? "school" : category === "delivery" ? "delivery" : "life");
}

/**
 * Deliveries are a list, not reminders: whatever lead times a caller sends, none are kept, so no
 * device ever schedules an alert for one.
 */
function alertOffsets(domain: EventDomain, offsets: number[] | undefined, critical: boolean): number[] {
  return domain === "delivery" ? [] : normalizeOffsets(offsets, critical);
}

function resolveInputDomain(input: EventInput, current?: ReminderEvent | null): EventDomain {
  if (input.domain) return input.domain;
  if (input.category === "school") return "school";
  if (input.category === "delivery") return "delivery";
  if (current?.domain) return current.domain;
  const academicTitle = /\b(assignment|homework|syllabus|quiz|midterm|exam|chm\s*110l?|nutr\s*150|eth\s*110)\b/i.test(input.title);
  const courseExperiment = /\b(exp(?:eriment)?\s*#?\s*\d+|laboratory|lab)\b/i.test(input.title)
    && /\b(canvas|course|instructor|chemistry)\b/i.test(`${input.title} ${input.notes ?? ""}`);
  return academicTitle || courseExperiment ? "school" : normalizeDomain(undefined, input.category);
}

function normalizeStoredEvent(event: ReminderEvent): ReminderEvent {
  const category = event.category ?? "automatic";
  const text = `${event.title} ${event.location} ${event.notes}`.toLowerCase();
  const inferredSchool = category === "school"
    || /\b(assignment|canvas|class|course|exam|final|homework|lecture|midterm|quiz|school|semester|syllabus|university|campus|eth 110|nutr 150|chm 110)\b/.test(text);
  return {
    ...event,
    category,
    domain: event.domain ?? (category === "delivery" ? "delivery" : inferredSchool ? "school" : "life"),
    source: event.source ?? "chatgpt",
  };
}

function schoolMetadata(
  incoming: TaskPublishInput["reminders"][number],
  current: ReminderEvent | null,
): Partial<ReminderEvent> {
  const text = (value: string | undefined, fallback: string | undefined) => value?.trim() || fallback;
  const courseId = text(incoming.courseId, current?.courseId);
  const courseName = text(incoming.courseName, current?.courseName);
  const courseCode = text(incoming.courseCode, current?.courseCode);
  const courseColorKey = text(incoming.courseColorKey, current?.courseColorKey) || courseId || courseName;
  const details = text(incoming.details, current?.details);
  const unlockAt = normalizeOptionalDate(incoming.unlockAt) ?? current?.unlockAt;
  const lockAt = normalizeOptionalDate(incoming.lockAt) ?? current?.lockAt;
  const submissionTypes = incoming.submissionTypes?.map((value) => value.trim()).filter(Boolean)
    ?? current?.submissionTypes;
  return {
    ...(courseId ? { courseId } : {}),
    ...(courseName ? { courseName } : {}),
    ...(courseCode ? { courseCode } : {}),
    ...(courseColorKey ? { courseColorKey } : {}),
    ...((incoming.schoolItemType ?? current?.schoolItemType)
      ? { schoolItemType: incoming.schoolItemType ?? current?.schoolItemType }
      : {}),
    ...(details ? { details } : {}),
    ...((incoming.pointsPossible ?? current?.pointsPossible) !== undefined
      ? { pointsPossible: incoming.pointsPossible ?? current?.pointsPossible }
      : {}),
    ...(submissionTypes?.length ? { submissionTypes } : {}),
    ...(unlockAt ? { unlockAt } : {}),
    ...(lockAt ? { lockAt } : {}),
    ...((incoming.allowedAttempts ?? current?.allowedAttempts) !== undefined
      ? { allowedAttempts: incoming.allowedAttempts ?? current?.allowedAttempts }
      : {}),
  };
}

function normalizeOptionalDate(value: string | undefined): string | undefined {
  if (!value) return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? undefined : parsed.toISOString();
}

function isRoutineSchoolMeeting(input: Pick<ReminderEvent, "title" | "location" | "notes" | "domain" | "category"> | EventInput): boolean {
  if (normalizeDomain(input.domain, input.category) !== "school") return false;
  const text = `${input.title} ${input.location ?? ""} ${input.notes ?? ""}`.toLowerCase();
  const actionable = /\b(assignment|deadline|due|exam|final|homework|lab|midterm|presentation|project|quiz|registration|rescheduled|test)\b/.test(text);
  const meeting = /\b(class|class meeting|lecture|lesson meeting|recurring session)\b/.test(text);
  const recurring = /\b(every|each|weekly|regular|recurring|mondays?|tuesdays?|wednesdays?|thursdays?|fridays?|m\/w|t\/th|tu\/th|tue\/thu)\b/.test(text);
  return meeting && recurring && !actionable;
}

function normalizeOffsets(offsets: number[] | undefined, critical: boolean): number[] {
  const values = offsets ?? (critical ? [1440, 0] : [1440]);
  return [...new Set(values.map(Math.trunc).filter((value) => value >= 0 && value <= 525600))]
    .sort((a, b) => b - a);
}

const zoneCache = new Map<string, boolean>();

export function isValidTimeZone(value: unknown): value is string {
  if (typeof value !== "string" || value.length === 0 || value.length > 100) return false;
  const cached = zoneCache.get(value);
  if (cached !== undefined) return cached;
  let valid: boolean;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value });
    valid = true;
  } catch {
    valid = false;
  }
  if (zoneCache.size < 1000) zoneCache.set(value, valid);
  return valid;
}

/** Shared service-boundary validation for every client (MCP, Android, desktop, Canvas). */
function validateEventFields(input: Partial<Pick<EventInput, "title" | "startAt" | "timeZone">>, required: boolean): void {
  if (input.title !== undefined || required) {
    if (typeof input.title !== "string" || input.title.trim().length === 0) throw new InputError("title must not be blank");
  }
  if (input.startAt !== undefined || required) {
    if (typeof input.startAt !== "string" || Number.isNaN(Date.parse(input.startAt))) throw new InputError("startAt must be a valid timestamp");
  }
  if (input.timeZone !== undefined || required) {
    if (!isValidTimeZone(input.timeZone)) throw new InputError("timeZone must be a valid IANA time zone");
  }
}

async function forEachConcurrent<T>(items: T[], limit: number, worker: (item: T) => Promise<void>): Promise<void> {
  let cursor = 0;
  const run = async () => {
    while (cursor < items.length) {
      const item = items[cursor++];
      await worker(item);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, run));
}
