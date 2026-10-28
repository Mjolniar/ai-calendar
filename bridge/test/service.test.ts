import { describe, expect, it } from "vitest";
import type { DeviceNotifier } from "../src/notifier.js";
import { CanvasSyncService } from "../src/canvas.js";
import { CalendarService } from "../src/service.js";
import { MemoryCalendarStore } from "../src/store.js";

class CountingNotifier implements DeviceNotifier {
  calls = 0;

  constructor(private readonly fail = false) {}

  async signalSync(): Promise<number> {
    this.calls += 1;
    if (this.fail) throw new Error("FCM unavailable");
    return 1;
  }
}

const taskInput = {
  taskKey: "student-planner",
  runId: "run-1",
  reminders: [
    {
      itemKey: "math-test",
      title: "Math test",
      startAt: "2026-08-20T09:00:00-06:00",
      timeZone: "America/Denver",
      critical: true,
    },
    {
      itemKey: "tuition",
      title: "Tuition due",
      startAt: "2026-08-25T09:00:00-06:00",
      timeZone: "America/Denver",
    },
  ],
};

describe("task reminder publishing", () => {
  it("routes course experiments to School and preserves a corrected feed on republish", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new CountingNotifier());
    const input = { taskKey: "course-email", reminders: [{ itemKey: "experiment-1", title: "Experiment #4 opens", notes: "Titration opens in Canvas.", startAt: "2026-09-11T08:00:00-06:00", timeZone: "America/Denver" }] };
    const created = await service.publishTaskReminders(input);
    expect(created.reminders[0].event.domain).toBe("school");
    await service.update(created.reminders[0].event.id, { domain: "school", category: "school" });
    const updated = await service.publishTaskReminders({ ...input, reminders: [{ ...input.reminders[0], title: "Experiment opens", notes: "Bring supplies." }] });
    expect(updated.reminders[0].event.domain).toBe("school");
    expect(updated.reminders[0].event.category).toBe("school");
    expect(updated.reminders[0].event.id).toBe(created.reminders[0].event.id);
    const personal = await service.create({ title: "Pick up dry cleaning", notes: "Before class", startAt: input.reminders[0].startAt, timeZone: "America/Denver" });
    expect(personal.event.domain).toBe("life");
  });
  it("creates a batch, retries idempotently, and signals once per changed batch", async () => {
    const notifier = new CountingNotifier();
    const service = new CalendarService(new MemoryCalendarStore(), notifier);

    const created = await service.publishTaskReminders(taskInput);
    expect(created.created).toBe(2);
    expect(created.updated).toBe(0);
    expect(created.notified).toBe(1);

    const retried = await service.publishTaskReminders({ ...taskInput, runId: "run-2" });
    expect(retried.created).toBe(0);
    expect(retried.updated).toBe(0);
    expect(retried.unchanged).toBe(2);
    expect(retried.notified).toBe(0);
    expect(retried.reminders[0].event.id).toBe(created.reminders[0].event.id);
    expect(notifier.calls).toBe(1);
  });

  it("updates an existing task item without duplicating it", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new CountingNotifier());
    const created = await service.publishTaskReminders(taskInput);
    const changed = structuredClone(taskInput);
    changed.runId = "run-2";
    changed.reminders[0].startAt = "2026-08-21T10:00:00-06:00";

    const updated = await service.publishTaskReminders(changed);
    expect(updated.updated).toBe(1);
    expect(updated.unchanged).toBe(1);
    expect(updated.reminders[0].event.id).toBe(created.reminders[0].event.id);
    expect((await store.listEvents(true))).toHaveLength(2);
  });

  it("stores changes even when push signaling fails", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new CountingNotifier(true));

    const published = await service.publishTaskReminders(taskInput);
    expect(published.created).toBe(2);
    expect(published.notified).toBe(0);
    expect(await store.listEvents()).toHaveLength(2);
  });

  it("archives routine class meetings but keeps actionable school work", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new CountingNotifier());

    const result = await service.publishTaskReminders({
      taskKey: "school-schedule",
      reminders: [
        {
          itemKey: "eth110-tuth",
          title: "ETH 110 Workplace Ethics",
          startAt: "2026-09-10T15:00:00-06:00",
          timeZone: "America/Denver",
          domain: "school",
          notes: "Section 001. Regular Tue/Thu class meeting, 3:00-4:15 PM.",
        },
        {
          itemKey: "eth110-final",
          title: "ETH 110 final presentation due Thursday",
          startAt: "2026-12-10T15:00:00-07:00",
          timeZone: "America/Denver",
          domain: "school",
          critical: true,
        },
      ],
    });

    expect(result.reminders[0].event.done).toBe(true);
    expect(result.reminders[1].event.done).toBe(false);
    expect(await service.list()).toHaveLength(1);
    expect((await service.list())[0].title).toContain("final presentation");
  });
});

describe("republishing and the routine class rule", () => {
  const bill = { itemKey: "rent", title: "Rent due", startAt: "2026-11-01T09:00:00-06:00", timeZone: "America/Denver" };

  it("a done item republished for a later date opens again, so it alerts", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new CountingNotifier());
    const first = await service.publishTaskReminders({ taskKey: "planner", reminders: [bill] });
    await service.update(first.reminders[0].event.id, { done: true });
    const next = await service.publishTaskReminders({ taskKey: "planner", reminders: [{ ...bill, startAt: "2026-12-01T09:00:00-07:00" }] });
    expect(next.reminders[0].event.done).toBe(false);
  });

  it("a done item republished for the same date stays done", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new CountingNotifier());
    const first = await service.publishTaskReminders({ taskKey: "planner", reminders: [bill] });
    await service.update(first.reminders[0].event.id, { done: true });
    const again = await service.publishTaskReminders({ taskKey: "planner", reminders: [{ ...bill, notes: "Paid by check" }] });
    expect(again.reminders[0].event.done).toBe(true);
  });

  it("a routine class meeting is filed only when first published; reopened, it stays open", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new CountingNotifier());
    const meeting = { itemKey: "eth-tuth", title: "ETH 110 class", startAt: "2026-11-03T15:00:00-07:00", timeZone: "America/Denver", domain: "school" as const, notes: "Regular Tue/Thu class meeting" };
    const first = await service.publishTaskReminders({ taskKey: "school", reminders: [meeting] });
    expect(first.reminders[0].event.done).toBe(true);
    await service.update(first.reminders[0].event.id, { done: false });
    const again = await service.publishTaskReminders({ taskKey: "school", reminders: [{ ...meeting, notes: `${meeting.notes}.` }] });
    expect(again.reminders[0].event.done).toBe(false);
  });

  it.each([
    "No ETH 110 class Tuesday",
    "Lecture moved to Tuesday",
    "Class cancelled Thursday",
    "Tuesday class meets in room 204 this week",
  ])("a note about a change is not a routine meeting: %s", async (title) => {
    const service = new CalendarService(new MemoryCalendarStore(), new CountingNotifier());
    const published = await service.publishTaskReminders({ taskKey: "school", reminders: [{ itemKey: title, title, startAt: "2026-11-03T15:00:00-07:00", timeZone: "America/Denver", domain: "school" }] });
    expect(published.reminders[0].event.done).toBe(false);
  });

  it("a Canvas sync never completes a school reminder made on a phone or by the planner", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new CountingNotifier());
    await service.createFromDevice("3f2b7c1e-8a4d-4f6b-9c2e-1d5a7b9c0e12", { title: "Biology lecture every Tuesday", startAt: "2026-11-03T15:00:00Z", timeZone: "America/Denver", domain: "school" });
    const empty: typeof fetch = async () => new Response("[]", { status: 200, headers: { "content-type": "application/json" } });
    const result = await new CanvasSyncService(service, empty).sync({ baseUrl: "https://school.example.test", accessToken: "t" }, new Date("2026-10-26T12:00:00Z"));
    expect(result.routineMeetingsArchived).toBe(0);
    expect((await service.list(false)).map((event) => event.title)).toContain("Biology lecture every Tuesday");
  });
});

describe("deliveries", () => {
  const delivery = {
    title: "Desk lamp arrives",
    startAt: "2026-10-06T09:00:00-06:00",
    timeZone: "America/Denver",
    notes: "UPS, order 12345",
  };

  it("lists a delivery without any alerts, whatever the caller asks for", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new CountingNotifier());
    const created = await service.create({ ...delivery, domain: "delivery", critical: true, reminderOffsetsMinutes: [1440, 60, 0] });
    expect(created.event.domain).toBe("delivery");
    expect(created.event.reminderOffsetsMinutes).toEqual([]);

    const published = await service.publishTaskReminders({
      taskKey: "order-emails",
      reminders: [{ ...delivery, itemKey: "lamp", domain: "delivery" }, { ...delivery, itemKey: "rent", title: "Rent due" }],
    });
    expect(published.reminders[0].event.domain).toBe("delivery");
    expect(published.reminders[0].event.reminderOffsetsMinutes).toEqual([]);
    // Anything else keeps the default day-before alert.
    expect(published.reminders[1].event.domain).toBe("life");
    expect(published.reminders[1].event.reminderOffsetsMinutes).toEqual([1440]);
    // Publishing the same delivery again changes nothing.
    const again = await service.publishTaskReminders({ taskKey: "order-emails", reminders: [{ ...delivery, itemKey: "lamp", domain: "delivery" }] });
    expect(again.unchanged).toBe(1);
  });

  it("treats the delivery category as the delivery feed", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new CountingNotifier());
    const created = await service.create({ ...delivery, category: "delivery" });
    expect(created.event.domain).toBe("delivery");
    expect(created.event.reminderOffsetsMinutes).toEqual([]);
    // Only an explicit choice files it there: wording alone does not silence a reminder.
    const worded = await service.create({ title: "Deliver the presentation", startAt: delivery.startAt, timeZone: "America/Denver" });
    expect(worded.event.domain).toBe("life");
    expect(worded.event.reminderOffsetsMinutes).toEqual([1440]);
  });

  it("drops the alerts when a reminder becomes a delivery and restores them when it stops being one", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new CountingNotifier());
    const created = await service.create({ ...delivery, reminderOffsetsMinutes: [1440, 60] });
    expect(created.event.reminderOffsetsMinutes).toEqual([1440, 60]);

    const moved = await service.update(created.event.id, { domain: "delivery" });
    expect(moved?.event.domain).toBe("delivery");
    expect(moved?.event.reminderOffsetsMinutes).toEqual([]);
    // Lead times sent for a delivery are not kept either.
    const edited = await service.update(created.event.id, { title: "Desk lamp arrives Tuesday", reminderOffsetsMinutes: [120] });
    expect(edited?.event.reminderOffsetsMinutes).toEqual([]);

    const back = await service.update(created.event.id, { domain: "life" });
    expect(back?.event.domain).toBe("life");
    expect(back?.event.reminderOffsetsMinutes).toEqual([1440]);
    const chosen = await service.update(created.event.id, { domain: "delivery" })
      .then(() => service.update(created.event.id, { domain: "life", reminderOffsetsMinutes: [30] }));
    expect(chosen?.event.reminderOffsetsMinutes).toEqual([30]);
  });

  it("files a reminder under deliveries when only its category is changed to delivery", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new CountingNotifier());
    const created = await service.create(delivery);
    const moved = await service.update(created.event.id, { category: "delivery" });
    expect(moved?.event.domain).toBe("delivery");
    expect(moved?.event.reminderOffsetsMinutes).toEqual([]);
  });
});
