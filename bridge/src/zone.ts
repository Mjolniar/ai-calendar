// The household's time zone: date-only reminders get 09:00 there, and the kitchen's "today" and
// week are picked by it. AI_CALENDAR_TIME_ZONE (an IANA name) changes it; a bad value is ignored.

export const DEFAULT_TIME_ZONE = "America/Denver";

export function homeTimeZone(): string {
  const zone = process.env.AI_CALENDAR_TIME_ZONE?.trim();
  return zone && knownZone(zone) ? zone : DEFAULT_TIME_ZONE;
}

function knownZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}
