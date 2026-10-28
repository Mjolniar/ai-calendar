import { randomBytes } from "node:crypto";
import { InputError } from "./service.js";
import type { CalendarStore } from "./store.js";
import type { DeviceNotifier } from "./notifier.js";

// The household kitchen: shopping list, pantry, the week's meal plan and how each dinner went.
// Every kitchen rule lives here (docs\kitchen.md); the apps only show the snapshot and
// send small edits.

export const ITEM_STATUSES = ["need", "ordered", "got"] as const;
export const PANTRY_STATUSES = ["have", "low", "out"] as const;
export const COOKED_VALUES = ["", "yes", "skipped"] as const;
export const RATING_VALUES = ["", "loved", "ok", "no"] as const;
export const CORRECTION_KINDS = ["out", "have", "note"] as const;
export const CORRECTION_STATUSES = ["open", "applied", "dismissed"] as const;

export type ItemStatus = (typeof ITEM_STATUSES)[number];
export type PantryStatus = (typeof PANTRY_STATUSES)[number];
export type ItemSource = "device" | "ai" | "pantry";

export type KitchenItem = {
  id: string;
  name: string;
  qty: string;
  note: string;
  status: ItemStatus;
  orderRef: string;
  source: ItemSource;
  addedBy: string;
  addedAt: string;
  updatedAt: string;
  doneAt?: string;
};

/** Claude's estimate of one thing on hand. `basis` says why ("bought 10/26", "used in Wed jambalaya"). */
export type PantryItem = {
  id: string;
  name: string;
  section: string;
  status: PantryStatus;
  note: string;
  basis?: string;
  /** When Claude last assessed it. */
  checkedAt?: string;
  updatedAt: string;
};

/**
 * A person's correction to the estimate. `out` and `have` name a pantry item and apply at once;
 * a `note` is free text ("tossed the cilantro", "we have 2 cans of beans") that Claude applies later.
 */
export type Correction = {
  id: string;
  kind: (typeof CORRECTION_KINDS)[number];
  text: string;
  itemId?: string;
  itemName?: string;
  status: (typeof CORRECTION_STATUSES)[number];
  reply: string;
  addedBy: string;
  addedAt: string;
  resolvedAt?: string;
};

export type DinnerNote = {
  cooked: (typeof COOKED_VALUES)[number];
  rating: (typeof RATING_VALUES)[number];
  note: string;
  updatedAt: string;
};

export type WeekPlan = Record<string, unknown> & {
  week: { start: string; end: string; title?: string };
  days: Array<Record<string, unknown> & { date: string; dinner: string }>;
};

export type WeekEntry = { start: string; end: string; title: string; publishedAt: string };

export type KitchenSnapshot = {
  revision: number;
  list: KitchenItem[];
  pantry: PantryItem[];
  week: WeekPlan | null;
  nextWeek: WeekPlan | null;
  notes: Record<string, DinnerNote>;
  corrections: Correction[];
};

type ListDoc = { items: KitchenItem[]; revision: number };
type PantryDoc = { items: PantryItem[]; revision: number };
type NotesDoc = { days: Record<string, DinnerNote>; revision: number };
type WeeksDoc = { weeks: Record<string, WeekEntry>; revision: number };
type CorrectionsDoc = { items: Correction[]; revision: number };

const LIST_KEY = "kitchen:list";
const PANTRY_KEY = "kitchen:pantry";
const NOTES_KEY = "kitchen:notes";
const WEEKS_KEY = "kitchen:weeks";
const CORRECTIONS_KEY = "kitchen:corrections";
const weekKey = (start: string) => `kitchen:week:${start}`;

const DAY_MS = 24 * 60 * 60 * 1000;
/** `got` items the device snapshot still shows, so a mistaken tick can be undone. */
const DEVICE_GOT_DAYS = 7;
/** `got` items older than this are dropped from storage. */
const KEEP_GOT_DAYS = 30;
const MAX_LIST_ITEMS = 400;
const MAX_PANTRY_ITEMS = 400;
const MAX_OPEN_CORRECTIONS = 200;
const MAX_PLAN_CHARS = 200_000;
const PLAN_ZONE = "America/Denver";
export const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export type ItemInput = { name: string; qty?: string; note?: string };
export type ItemPatch = { name?: string; qty?: string; note?: string; status?: ItemStatus; orderRef?: string };
export type PantryInput = { name: string; section?: string; status?: PantryStatus; note?: string; basis?: string; remove?: boolean };
export type PantryPatch = { status?: PantryStatus; note?: string; section?: string };
export type NotePatch = { cooked?: DinnerNote["cooked"]; rating?: DinnerNote["rating"]; note?: string };

const SIGNAL_TIMEOUT_MS = 4000;

export class KitchenService {
  constructor(
    private readonly store: CalendarStore,
    private readonly clock: () => Date = () => new Date(),
    private readonly notifier?: DeviceNotifier,
  ) {}

  /**
   * Tells the other devices the kitchen changed (`kitchen_sync`), so an open Kitchen view refreshes.
   * Best effort, like reminder signals: the change is already saved, so a slow or failing push never
   * fails the caller. [exceptDeviceId] is the device that made the change; it already has the result.
   */
  async signal(exceptDeviceId?: string): Promise<number> {
    if (!this.notifier?.signalKitchen) return 0;
    let timer: NodeJS.Timeout | undefined;
    try {
      const devices = (await this.store.listDevices()).filter((device) => device.id !== exceptDeviceId);
      if (devices.length === 0) return 0;
      const timeout = new Promise<number>((_, reject) => {
        timer = setTimeout(() => reject(new Error("kitchen signal timed out")), SIGNAL_TIMEOUT_MS);
        timer.unref?.();
      });
      return await Promise.race([this.notifier.signalKitchen(devices, this.clock().getTime()), timeout]);
    } catch (error) {
      console.error("Kitchen saved, but push signaling failed", error instanceof Error ? error.message : error);
      return 0;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  /** What the apps show. `gotDays` limits how far back `got` items reach. */
  async snapshot(gotDays = DEVICE_GOT_DAYS): Promise<KitchenSnapshot> {
    const [list, pantry, notes, weeks, corrections] = await Promise.all([
      this.read<ListDoc>(LIST_KEY), this.read<PantryDoc>(PANTRY_KEY), this.read<NotesDoc>(NOTES_KEY), this.read<WeeksDoc>(WEEKS_KEY),
      this.read<CorrectionsDoc>(CORRECTIONS_KEY),
    ]);
    const today = localDate(this.clock());
    const entries = Object.values(weeks?.weeks ?? {});
    const current = entries.filter((w) => w.start <= today && today <= w.end).sort((a, b) => b.start.localeCompare(a.start))[0];
    const next = entries.filter((w) => w.start > today).sort((a, b) => a.start.localeCompare(b.start))[0];
    const [week, nextWeek] = await Promise.all([current ? this.week(current.start) : null, next ? this.week(next.start) : null]);
    const dates = new Set([...planDates(week), ...planDates(nextWeek)]);
    const allNotes = notes?.days ?? {};
    const cutoff = this.clock().getTime() - gotDays * DAY_MS;
    return {
      revision: (list?.revision ?? 0) + (pantry?.revision ?? 0) + (notes?.revision ?? 0) + (weeks?.revision ?? 0) + (corrections?.revision ?? 0),
      list: orderList((list?.items ?? []).filter((item) => item.status !== "got" || Date.parse(item.doneAt ?? item.updatedAt) >= cutoff)),
      pantry: [...(pantry?.items ?? [])].sort((a, b) => a.section.localeCompare(b.section) || a.name.localeCompare(b.name)),
      week,
      nextWeek,
      notes: Object.fromEntries(Object.entries(allNotes).filter(([date]) => dates.has(date))),
      corrections: orderCorrections((corrections?.items ?? []).filter((item) => item.status === "open" || Date.parse(item.resolvedAt ?? item.addedAt) >= cutoff)),
    };
  }

  /** The published weeks, newest first. */
  async weeks(): Promise<WeekEntry[]> {
    const weeks = await this.read<WeeksDoc>(WEEKS_KEY);
    return Object.values(weeks?.weeks ?? {}).sort((a, b) => b.start.localeCompare(a.start));
  }

  async week(start: string): Promise<WeekPlan | null> {
    const doc = await this.store.getKv(weekKey(start));
    return (doc?.plan as WeekPlan | undefined) ?? null;
  }

  async addItems(inputs: ItemInput[], source: ItemSource, addedBy: string): Promise<Array<{ item: KitchenItem; created: boolean }>> {
    const clean = inputs.map((input) => ({
      name: requiredText(input.name, "name", 120),
      qty: optionalText(input.qty, "qty", 40),
      note: optionalText(input.note, "note", 300),
    }));
    const now = this.now();
    return this.mutateList((items) => clean.map((input) => upsertOpenItem(items, input, source, addedBy, now)));
  }

  async updateItem(id: string, patch: ItemPatch): Promise<KitchenItem | null> {
    const name = patch.name === undefined ? undefined : requiredText(patch.name, "name", 120);
    const qty = optionalText(patch.qty, "qty", 40);
    const note = optionalText(patch.note, "note", 300);
    const orderRef = optionalText(patch.orderRef, "orderRef", 200);
    if (patch.status !== undefined && !ITEM_STATUSES.includes(patch.status)) throw new InputError("status must be need, ordered or got");
    const now = this.now();
    const result = await this.mutateList((items) => {
      const item = items.find((candidate) => candidate.id === id);
      if (!item) return null;
      const wasGot = item.status === "got";
      if (name !== undefined) item.name = name;
      if (qty !== undefined) item.qty = qty;
      if (note !== undefined) item.note = note;
      if (orderRef !== undefined) item.orderRef = orderRef;
      if (patch.status !== undefined && patch.status !== item.status) {
        item.status = patch.status;
        if (patch.status === "got") item.doneAt = now;
        else delete item.doneAt;
      }
      item.updatedAt = now;
      return { item: { ...item }, becameGot: !wasGot && item.status === "got" };
    });
    if (!result) return null;
    // Rule 3: bought means the pantry has it again.
    if (result.becameGot) await this.restock([result.item.name]);
    return result.item;
  }

  async deleteItem(id: string): Promise<boolean> {
    return this.mutateList((items) => {
      const index = items.findIndex((item) => item.id === id);
      if (index < 0) return false;
      items.splice(index, 1);
      return true;
    });
  }

  /** Upsert by name (AI clients). Returns each item, and the list item rule 2 added for it, if any. */
  async setPantry(inputs: PantryInput[]): Promise<Array<{ name: string; item: PantryItem | null; removed: boolean; listItem?: KitchenItem }>> {
    const clean = inputs.map((input) => ({
      name: requiredText(input.name, "name", 120),
      section: optionalText(input.section, "section", 60),
      status: pantryStatus(input.status),
      note: optionalText(input.note, "note", 300),
      basis: optionalText(input.basis, "basis", 200),
      remove: input.remove === true,
    }));
    const now = this.now();
    const outcome = await this.mutatePantry((items) => clean.map((input) => {
      const index = items.findIndex((item) => sameName(item.name, input.name));
      if (input.remove) {
        if (index >= 0) items.splice(index, 1);
        return { name: input.name, item: null, removed: index >= 0, wentOut: false };
      }
      if (index < 0) {
        if (items.length >= MAX_PANTRY_ITEMS) throw new InputError(`The pantry holds at most ${MAX_PANTRY_ITEMS} items`);
        const item: PantryItem = {
          id: newId("p"), name: input.name, section: input.section || "Other", status: input.status ?? "have", note: input.note ?? "",
          basis: input.basis ?? "", checkedAt: now, updatedAt: now,
        };
        items.push(item);
        return { name: input.name, item: { ...item }, removed: false, wentOut: item.status === "out" };
      }
      const item = items[index];
      const wasOut = item.status === "out";
      if (input.section) item.section = input.section;
      if (input.status) item.status = input.status;
      if (input.note !== undefined) item.note = input.note;
      if (input.basis !== undefined) item.basis = input.basis;
      item.checkedAt = now;
      item.updatedAt = now;
      return { name: input.name, item: { ...item }, removed: false, wentOut: !wasOut && item.status === "out" };
    }));
    const added = await this.addForOut(outcome.filter((entry) => entry.wentOut).map((entry) => entry.item!.name));
    return outcome.map(({ wentOut: _wentOut, ...entry }) => {
      const listItem = entry.item ? added.get(normalizeName(entry.item.name)) : undefined;
      return listItem ? { ...entry, listItem } : entry;
    });
  }

  /** Add one pantry item from a device; an item with the same name is returned instead. */
  async addPantryItem(input: { name: string; section?: string; status?: PantryStatus }): Promise<{ item: PantryItem; created: boolean; listItem?: KitchenItem }> {
    const name = requiredText(input.name, "name", 120);
    const section = optionalText(input.section, "section", 60);
    const status = pantryStatus(input.status) ?? "have";
    const now = this.now();
    const result = await this.mutatePantry((items) => {
      const existing = items.find((item) => sameName(item.name, name));
      if (existing) return { item: { ...existing }, created: false };
      if (items.length >= MAX_PANTRY_ITEMS) throw new InputError(`The pantry holds at most ${MAX_PANTRY_ITEMS} items`);
      const item: PantryItem = { id: newId("p"), name, section: section || "Other", status, note: "", updatedAt: now };
      items.push(item);
      return { item: { ...item }, created: true };
    });
    if (!result.created || result.item.status !== "out") return result;
    const listItem = (await this.addForOut([result.item.name])).get(normalizeName(result.item.name));
    return listItem ? { ...result, listItem } : result;
  }

  async updatePantryItem(id: string, patch: PantryPatch): Promise<{ item: PantryItem; listItem?: KitchenItem } | null> {
    const status = pantryStatus(patch.status);
    const note = optionalText(patch.note, "note", 300);
    const section = optionalText(patch.section, "section", 60);
    const now = this.now();
    const result = await this.mutatePantry((items) => {
      const item = items.find((candidate) => candidate.id === id);
      if (!item) return null;
      const wasOut = item.status === "out";
      if (status) item.status = status;
      if (note !== undefined) item.note = note;
      if (section) item.section = section;
      item.updatedAt = now;
      return { item: { ...item }, wentOut: !wasOut && item.status === "out" };
    });
    if (!result) return null;
    if (!result.wentOut) return { item: result.item };
    // Rule 2: out means buy it.
    const listItem = (await this.addForOut([result.item.name])).get(normalizeName(result.item.name));
    return listItem ? { item: result.item, listItem } : { item: result.item };
  }

  async deletePantryItem(id: string): Promise<boolean> {
    return this.mutatePantry((items) => {
      const index = items.findIndex((item) => item.id === id);
      if (index < 0) return false;
      items.splice(index, 1);
      return true;
    });
  }

  async setNote(date: string, patch: NotePatch): Promise<DinnerNote> {
    if (!validDate(date)) throw new InputError("date must be a valid YYYY-MM-DD date");
    if (patch.cooked !== undefined && !COOKED_VALUES.includes(patch.cooked)) throw new InputError("cooked must be yes, skipped or empty");
    if (patch.rating !== undefined && !RATING_VALUES.includes(patch.rating)) throw new InputError("rating must be loved, ok, no or empty");
    const note = optionalText(patch.note, "note", 1000);
    const now = this.now();
    return this.store.mutateKv(NOTES_KEY, (current) => {
      const doc = (current as NotesDoc | null) ?? { days: {}, revision: 0 };
      const days = doc.days ?? {};
      const before = days[date] ?? { cooked: "", rating: "", note: "", updatedAt: now };
      const after: DinnerNote = {
        cooked: patch.cooked ?? before.cooked,
        rating: patch.rating ?? before.rating,
        note: note ?? before.note,
        updatedAt: now,
      };
      return { value: { days: { ...days, [date]: after }, revision: (doc.revision ?? 0) + 1 }, result: after };
    });
  }

  /**
   * A person's correction. `out`/`have` name a pantry item and apply at once (out also lists it, rule
   * 2); the correction is kept as applied, with a reply. A `note` stays open for Claude.
   */
  async addCorrection(input: { kind: unknown; text?: unknown; itemId?: unknown }, addedBy: string): Promise<{ correction: Correction; item?: PantryItem; listItem?: KitchenItem } | null> {
    if (!CORRECTION_KINDS.includes(input.kind as Correction["kind"])) throw new InputError("kind must be out, have or note");
    const kind = input.kind as Correction["kind"];
    const text = optionalText(input.text, "text", 300) ?? "";
    if (kind === "note" && !text) throw new InputError("A note needs text (1-300 characters)");
    const itemId = typeof input.itemId === "string" && input.itemId ? input.itemId : undefined;
    if (kind !== "note" && !itemId) throw new InputError("out and have need the pantry item's itemId");
    const now = this.now();
    let item: PantryItem | undefined;
    let listItem: KitchenItem | undefined;
    if (itemId) {
      const pantry = await this.read<PantryDoc>(PANTRY_KEY);
      item = pantry?.items.find((candidate) => candidate.id === itemId);
      if (!item) return null;
      if (kind !== "note") {
        const changed = await this.updatePantryItem(itemId, { status: kind });
        if (!changed) return null;
        listItem = changed.listItem;
        const basis = kind === "out" ? `${addedBy} said it's out` : `${addedBy} said you have it`;
        item = await this.mutatePantry((items) => {
          const target = items.find((candidate) => candidate.id === itemId);
          if (!target) return undefined;
          target.basis = basis;
          return { ...target };
        }, (found) => Boolean(found)) ?? changed.item;
      }
    }
    const reply = kind === "out"
      ? (listItem ? "Marked out and added to the shopping list." : "Marked out; it was already on the shopping list.")
      : kind === "have" ? "Marked as on hand." : "";
    const correction: Correction = {
      id: newId("c"), kind, text, status: kind === "note" ? "open" : "applied", reply, addedBy: addedBy.slice(0, 120), addedAt: now,
      ...(item ? { itemId: item.id, itemName: item.name } : {}),
      ...(kind === "note" ? {} : { resolvedAt: now }),
    };
    await this.mutateCorrections((items) => {
      if (items.filter((entry) => entry.status === "open").length >= MAX_OPEN_CORRECTIONS) throw new InputError(`At most ${MAX_OPEN_CORRECTIONS} open corrections`);
      items.push(correction);
      return null;
    });
    return { correction, ...(item ? { item } : {}), ...(listItem ? { listItem } : {}) };
  }

  /** A person takes back a note Claude hasn't handled yet. Handled ones stay. */
  async withdrawCorrection(id: string): Promise<boolean> {
    return this.mutateCorrections((items) => {
      const index = items.findIndex((entry) => entry.id === id && entry.status === "open");
      if (index < 0) return false;
      items.splice(index, 1);
      return true;
    });
  }

  /** Claude marks corrections applied or dismissed, with a short reply the apps show. */
  async resolveCorrections(updates: Array<{ id: string; status: "applied" | "dismissed"; reply?: string }>): Promise<Array<{ id: string; correction?: Correction; missing?: true }>> {
    const clean = updates.map((update) => {
      if (update.status !== "applied" && update.status !== "dismissed") throw new InputError("status must be applied or dismissed");
      return { id: update.id, status: update.status, reply: optionalText(update.reply, "reply", 300) ?? "" };
    });
    const now = this.now();
    return this.mutateCorrections((items) => clean.map((update) => {
      const entry = items.find((candidate) => candidate.id === update.id);
      if (!entry) return { id: update.id, missing: true as const };
      entry.status = update.status;
      entry.reply = update.reply;
      entry.resolvedAt = now;
      return { id: update.id, correction: { ...entry } };
    }));
  }

  /** Store one week's plan, replacing the week with the same start. */
  async publishWeek(plan: unknown): Promise<{ start: string; end: string; replaced: boolean }> {
    const valid = validatePlan(plan);
    const { start, end } = valid.week;
    const publishedAt = this.now();
    const title = typeof valid.week.title === "string" ? valid.week.title.slice(0, 200) : `Week of ${start}`;
    await this.store.putKv(weekKey(start), { plan: valid, publishedAt });
    const replaced = await this.store.mutateKv(WEEKS_KEY, (current) => {
      const doc = (current as WeeksDoc | null) ?? { weeks: {}, revision: 0 };
      const weeks = doc.weeks ?? {};
      return {
        value: { weeks: { ...weeks, [start]: { start, end, title, publishedAt } }, revision: (doc.revision ?? 0) + 1 },
        result: Boolean(weeks[start]),
      };
    });
    return { start, end, replaced };
  }

  /** Rule 2 for several names at once: each gets an open list item unless it already has one. */
  private async addForOut(names: string[]): Promise<Map<string, KitchenItem>> {
    const added = new Map<string, KitchenItem>();
    if (names.length === 0) return added;
    const now = this.now();
    await this.mutateList((items) => {
      for (const name of names) {
        if (items.some((item) => item.status !== "got" && sameName(item.name, name))) continue;
        const result = upsertOpenItem(items, { name }, "pantry", "Pantry", now);
        added.set(normalizeName(name), result.item);
      }
      return null;
    });
    return added;
  }

  /** Rule 3: pantry items with these names become `have`. */
  private async restock(names: string[]): Promise<void> {
    const now = this.now();
    await this.mutatePantry((items) => {
      let changed = false;
      for (const item of items) {
        if (item.status !== "have" && names.some((name) => sameName(item.name, name))) {
          item.status = "have";
          item.updatedAt = now;
          changed = true;
        }
      }
      return changed;
    }, (changed) => changed);
  }

  private async mutateList<T>(change: (items: KitchenItem[]) => T): Promise<T> {
    const keepAfter = this.clock().getTime() - KEEP_GOT_DAYS * DAY_MS;
    return this.store.mutateKv(LIST_KEY, (current) => {
      const doc = (current as ListDoc | null) ?? { items: [], revision: 0 };
      const items = (doc.items ?? []).filter((item) => item.status !== "got" || Date.parse(item.doneAt ?? item.updatedAt) >= keepAfter);
      const result = change(items);
      if (items.length > MAX_LIST_ITEMS) throw new InputError(`The shopping list holds at most ${MAX_LIST_ITEMS} items`);
      return { value: { items, revision: (doc.revision ?? 0) + 1 }, result };
    });
  }

  private async mutatePantry<T>(change: (items: PantryItem[]) => T, wrote: (result: T) => boolean = () => true): Promise<T> {
    return this.store.mutateKv(PANTRY_KEY, (current) => {
      const doc = (current as PantryDoc | null) ?? { items: [], revision: 0 };
      const items = doc.items ?? [];
      const result = change(items);
      return { value: wrote(result) ? { items, revision: (doc.revision ?? 0) + 1 } : null, result };
    });
  }

  private async mutateCorrections<T>(change: (items: Correction[]) => T): Promise<T> {
    const keepAfter = this.clock().getTime() - KEEP_GOT_DAYS * DAY_MS;
    return this.store.mutateKv(CORRECTIONS_KEY, (current) => {
      const doc = (current as CorrectionsDoc | null) ?? { items: [], revision: 0 };
      const items = (doc.items ?? []).filter((entry) => entry.status === "open" || Date.parse(entry.resolvedAt ?? entry.addedAt) >= keepAfter);
      const result = change(items);
      return { value: { items, revision: (doc.revision ?? 0) + 1 }, result };
    });
  }

  private async read<T>(key: string): Promise<T | null> {
    return (await this.store.getKv(key)) as T | null;
  }

  private now(): string {
    return this.clock().toISOString();
  }
}

/** Rule 1: an open item (need or ordered) with the same name is reused; a sent qty or note replaces its own. */
function upsertOpenItem(items: KitchenItem[], input: { name: string; qty?: string; note?: string }, source: ItemSource, addedBy: string, now: string) {
  const open = items.find((item) => item.status !== "got" && sameName(item.name, input.name));
  if (open) {
    if (input.qty !== undefined) open.qty = input.qty;
    if (input.note !== undefined) open.note = input.note;
    if (input.qty !== undefined || input.note !== undefined) open.updatedAt = now;
    return { item: { ...open }, created: false };
  }
  const item: KitchenItem = {
    id: newId("k"), name: input.name, qty: input.qty ?? "", note: input.note ?? "", status: "need", orderRef: "",
    source, addedBy: addedBy.slice(0, 120), addedAt: now, updatedAt: now,
  };
  items.push(item);
  return { item: { ...item }, created: true };
}

/** Open corrections (oldest first), then handled ones (newest first). */
function orderCorrections(items: Correction[]): Correction[] {
  return [...items].sort((a, b) => (a.status === "open" ? 0 : 1) - (b.status === "open" ? 0 : 1)
    || (a.status === "open" ? a.addedAt.localeCompare(b.addedAt) : (b.resolvedAt ?? "").localeCompare(a.resolvedAt ?? "")));
}

/** need (oldest first), ordered (oldest first), then got (newest first). */
function orderList(items: KitchenItem[]): KitchenItem[] {
  const rank = { need: 0, ordered: 1, got: 2 } as const;
  return [...items].sort((a, b) => rank[a.status] - rank[b.status]
    || (a.status === "got" ? (b.doneAt ?? "").localeCompare(a.doneAt ?? "") : a.addedAt.localeCompare(b.addedAt)));
  // Ties keep the stored (added) order: Array.prototype.sort is stable.
}

/** Case, spacing, punctuation, accents and a plural "s" don't make a different item. */
export function normalizeName(name: string): string {
  return name.normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase()
    .replace(/[^a-z0-9]+/g, " ").trim().split(" ")
    .map((word) => (word.length > 3 && word.endsWith("s") && !word.endsWith("ss") ? word.slice(0, -1) : word))
    .join("");
}

function sameName(a: string, b: string): boolean {
  return normalizeName(a) === normalizeName(b);
}

export function localDate(at: Date, timeZone = PLAN_ZONE): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(at);
}

function planDates(plan: WeekPlan | null): string[] {
  return plan ? plan.days.map((day) => day.date) : [];
}

export function validDate(value: unknown): value is string {
  if (typeof value !== "string" || !DATE_PATTERN.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

function validatePlan(plan: unknown): WeekPlan {
  if (!plan || typeof plan !== "object" || Array.isArray(plan)) throw new InputError("plan must be an object");
  const value = plan as Record<string, unknown>;
  const week = value.week as Record<string, unknown> | undefined;
  if (!week || typeof week !== "object" || !validDate(week.start) || !validDate(week.end)) throw new InputError("plan.week needs start and end dates (YYYY-MM-DD)");
  if ((week.end as string) < (week.start as string)) throw new InputError("plan.week.end is before plan.week.start");
  const days = value.days;
  if (!Array.isArray(days) || days.length < 1 || days.length > 14) throw new InputError("plan.days must list 1 to 14 days");
  for (const day of days) {
    const entry = day as Record<string, unknown> | null;
    if (!entry || typeof entry !== "object" || !validDate(entry.date)) throw new InputError("every plan.days entry needs a date (YYYY-MM-DD)");
    if (typeof entry.dinner !== "string" || !entry.dinner.trim() || entry.dinner.length > 300) throw new InputError("every plan.days entry needs a dinner (1-300 characters)");
  }
  if (value.recipes !== undefined && (!Array.isArray(value.recipes) || value.recipes.length > 20)) throw new InputError("plan.recipes must be a list of at most 20");
  if (value.boxes !== undefined && (!Array.isArray(value.boxes) || value.boxes.length > 10)) throw new InputError("plan.boxes must be a list of at most 10");
  const text = JSON.stringify(value);
  if (text.length > MAX_PLAN_CHARS) throw new InputError(`plan is too large (${text.length} characters; at most ${MAX_PLAN_CHARS})`);
  return JSON.parse(text) as WeekPlan;
}

function requiredText(value: unknown, field: string, max: number): string {
  const text = typeof value === "string" ? value.trim() : "";
  if (!text || text.length > max) throw new InputError(`${field} must be 1-${max} characters`);
  return text;
}

function optionalText(value: unknown, field: string, max: number): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string" || value.trim().length > max) throw new InputError(`${field} must be text of at most ${max} characters`);
  return value.trim();
}

function pantryStatus(value: unknown): PantryStatus | undefined {
  if (value === undefined || value === null) return undefined;
  if (!PANTRY_STATUSES.includes(value as PantryStatus)) throw new InputError("status must be have, low or out");
  return value as PantryStatus;
}

function newId(prefix: string): string {
  return prefix + BigInt(`0x${randomBytes(8).toString("hex")}`).toString(36).padStart(13, "0").slice(-12);
}
