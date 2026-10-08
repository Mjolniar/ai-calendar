import { describe, expect, it } from "vitest";
import { countBefore, isLocalDate, localDateTimeToUtc, plannedOccurrences, ruleDates, ruleIncludes } from "../src/recurrence.js";
import type { SeriesRule } from "../src/types.js";

function take(rule: SeriesRule, n: number): string[] {
  const dates: string[] = [];
  for (const date of ruleDates(rule)) {
    dates.push(date);
    if (dates.length === n) break;
  }
  return dates;
}

const DENVER = "America/Denver";

describe("rule dates", () => {
  it("daily, every N days", () => {
    expect(take({ freq: "daily", interval: 1, startDate: "2026-10-30", time: "08:00" }, 4))
      .toEqual(["2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02"]);
    expect(take({ freq: "daily", interval: 3, startDate: "2026-12-30", time: "08:00" }, 3))
      .toEqual(["2026-12-30", "2027-01-02", "2027-01-05"]);
  });

  it("weekly defaults to the start date's weekday", () => {
    // October 9, 2026 is a Friday.
    expect(take({ freq: "weekly", interval: 1, startDate: "2026-10-09", time: "08:00" }, 3))
      .toEqual(["2026-10-09", "2026-10-16", "2026-10-23"]);
  });

  it("weekly on chosen weekdays skips days before the start in the first week", () => {
    // Wednesday start, Monday/Wednesday/Friday: the first Monday is in the next week.
    expect(take({ freq: "weekly", interval: 1, weekdays: [1, 3, 5], startDate: "2026-10-07", time: "09:00" }, 5))
      .toEqual(["2026-10-07", "2026-10-09", "2026-10-12", "2026-10-14", "2026-10-16"]);
  });

  it("every two weeks keeps the weeks counted from the start week, Monday to Sunday", () => {
    expect(take({ freq: "weekly", interval: 2, weekdays: [2, 7], startDate: "2026-10-06", time: "09:00" }, 4))
      .toEqual(["2026-10-06", "2026-10-11", "2026-10-20", "2026-10-25"]);
  });

  it("monthly on the 31st falls on the last day of shorter months, then returns to the 31st", () => {
    expect(take({ freq: "monthly", interval: 1, startDate: "2026-01-31", time: "09:00" }, 5))
      .toEqual(["2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31"]);
    expect(take({ freq: "monthly", interval: 1, startDate: "2027-12-29", time: "09:00" }, 3))
      .toEqual(["2027-12-29", "2028-01-29", "2028-02-29"]);
  });

  it("every N months crosses years", () => {
    expect(take({ freq: "monthly", interval: 5, startDate: "2026-10-15", time: "09:00" }, 3))
      .toEqual(["2026-10-15", "2027-03-15", "2027-08-15"]);
  });

  it("yearly on February 29 falls on February 28 in common years", () => {
    expect(take({ freq: "yearly", interval: 1, startDate: "2028-02-29", time: "09:00" }, 5))
      .toEqual(["2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29"]);
    expect(take({ freq: "yearly", interval: 2, startDate: "2026-10-09", time: "09:00" }, 2))
      .toEqual(["2026-10-09", "2028-10-09"]);
  });

  it("until is inclusive and count counts from the start", () => {
    expect([...ruleDates({ freq: "weekly", interval: 1, startDate: "2026-10-09", time: "08:00", until: "2026-10-23" })])
      .toEqual(["2026-10-09", "2026-10-16", "2026-10-23"]);
    expect([...ruleDates({ freq: "daily", interval: 2, startDate: "2026-10-09", time: "08:00", count: 3 })])
      .toEqual(["2026-10-09", "2026-10-11", "2026-10-13"]);
  });

  it("membership honours notBefore and counts before a date", () => {
    const rule: SeriesRule = { freq: "weekly", interval: 1, startDate: "2026-10-09", time: "08:00", notBefore: "2026-10-20" };
    expect(ruleIncludes(rule, "2026-10-16")).toBe(false);
    expect(ruleIncludes(rule, "2026-10-23")).toBe(true);
    expect(ruleIncludes(rule, "2026-10-24")).toBe(false);
    expect(countBefore({ ...rule, notBefore: undefined }, "2026-10-23")).toBe(2);
  });

  it("validates local dates", () => {
    expect(isLocalDate("2026-02-29")).toBe(false);
    expect(isLocalDate("2028-02-29")).toBe(true);
    expect(isLocalDate("2026-13-01")).toBe(false);
  });
});

describe("local time to instant", () => {
  it("keeps 8:00 AM at 8:00 AM across both daylight saving changes in Denver", () => {
    // MDT (UTC-6) before November 1, 2026; MST (UTC-7) after; MDT again from March 14, 2027.
    expect(new Date(localDateTimeToUtc("2026-10-30", "08:00", DENVER)).toISOString()).toBe("2026-10-30T14:00:00.000Z");
    expect(new Date(localDateTimeToUtc("2026-11-06", "08:00", DENVER)).toISOString()).toBe("2026-11-06T15:00:00.000Z");
    expect(new Date(localDateTimeToUtc("2027-03-12", "08:00", DENVER)).toISOString()).toBe("2027-03-12T15:00:00.000Z");
    expect(new Date(localDateTimeToUtc("2027-03-19", "08:00", DENVER)).toISOString()).toBe("2027-03-19T14:00:00.000Z");
  });

  it("moves a skipped time forward and takes the first of a repeated time", () => {
    // 2:30 AM does not exist on March 14, 2027: it becomes 3:30 AM MDT.
    expect(new Date(localDateTimeToUtc("2027-03-14", "02:30", DENVER)).toISOString()).toBe("2027-03-14T09:30:00.000Z");
    // 1:30 AM happens twice on November 1, 2026: the first is MDT.
    expect(new Date(localDateTimeToUtc("2026-11-01", "01:30", DENVER)).toISOString()).toBe("2026-11-01T07:30:00.000Z");
  });

  it("works in other zones", () => {
    expect(new Date(localDateTimeToUtc("2026-07-01", "09:00", "Europe/London")).toISOString()).toBe("2026-07-01T08:00:00.000Z");
    expect(new Date(localDateTimeToUtc("2026-07-01", "09:00", "UTC")).toISOString()).toBe("2026-07-01T09:00:00.000Z");
  });
});

describe("planned occurrences", () => {
  const now = Date.parse("2026-10-08T17:00:00Z"); // Thursday, 11:00 AM in Denver

  it("lists every occurrence in the next 60 days and none in the past", () => {
    const planned = plannedOccurrences({ freq: "weekly", interval: 1, startDate: "2026-09-04", time: "08:00" }, DENVER, [], now);
    expect(planned[0]).toEqual({ date: "2026-10-09", startAt: "2026-10-09T14:00:00.000Z" });
    expect(planned.at(-1)?.date).toBe("2026-12-04");
    expect(planned).toHaveLength(9);
    // After the change to standard time, 8:00 AM is 15:00 UTC.
    expect(planned.find((item) => item.date === "2026-11-06")?.startAt).toBe("2026-11-06T15:00:00.000Z");
  });

  it("keeps at least the next two even beyond the horizon", () => {
    const planned = plannedOccurrences({ freq: "yearly", interval: 1, startDate: "2026-03-01", time: "09:00" }, DENVER, [], now);
    expect(planned.map((item) => item.date)).toEqual(["2027-03-01", "2028-03-01"]);
  });

  it("skips deleted dates and stops at the end", () => {
    const planned = plannedOccurrences({ freq: "weekly", interval: 1, startDate: "2026-10-09", time: "08:00", count: 3 }, DENVER, ["2026-10-16"], now);
    expect(planned.map((item) => item.date)).toEqual(["2026-10-09", "2026-10-23"]);
  });

  it("leaves out an occurrence earlier today", () => {
    const planned = plannedOccurrences({ freq: "daily", interval: 1, startDate: "2026-10-01", time: "08:00", until: "2026-10-09" }, DENVER, [], now);
    expect(planned.map((item) => item.date)).toEqual(["2026-10-09"]);
  });
});
