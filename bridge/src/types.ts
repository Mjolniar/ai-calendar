/**
 * The feed a reminder is listed under. Deliveries (packages and orders on their way) are only
 * listed: they never alert, on any device.
 */
export type EventDomain = "life" | "school" | "delivery";

export type EventCategory = "automatic" | "school" | "medical" | "financial" | "housing" | "travel" | "work" | "personal" | "general" | "delivery";

export type ReminderEvent = {
  id: string;
  title: string;
  startAt: string;
  timeZone: string;
  location: string;
  notes: string;
  critical: boolean;
  reminderOffsetsMinutes: number[];
  done: boolean;
  domain: EventDomain;
  category: EventCategory;
  source: "chatgpt" | "canvas" | "manual";
  sourceUrl?: string;
  courseId?: string;
  courseName?: string;
  courseCode?: string;
  courseColorKey?: string;
  schoolItemType?: "assignment" | "quiz" | "discussion" | "peer_review" | "other";
  details?: string;
  pointsPossible?: number;
  submissionTypes?: string[];
  unlockAt?: string;
  lockAt?: string;
  allowedAttempts?: number;
  revision: number;
  createdAt: string;
  updatedAt: string;
  idempotencyKey?: string;
  sourceTaskKey?: string;
  sourceItemKey?: string;
  sourceRunId?: string;
  /** Set on a tombstone kept for a deleted task item so republishing does not resurrect it. */
  deletedAt?: string;
  /** Set on an occurrence of a repeating reminder: the series it belongs to. */
  seriesId?: string;
  /** The occurrence's local date (YYYY-MM-DD in `timeZone`) within its series. Its identity; never changes. */
  occurrenceDate?: string;
  /** An occurrence edited on its own ("this one"). Expanding the series leaves it alone. */
  detached?: boolean;
  /**
   * Set when a sync marked the reminder done because its source (Canvas) stopped listing it.
   * Cleared when the source lists it again, which reopens it.
   */
  retiredAt?: string;
};

export type SeriesFrequency = "daily" | "weekly" | "monthly" | "yearly";

/** When a repeating reminder happens. Dates and the time are local to the series' time zone. */
export type SeriesRule = {
  freq: SeriesFrequency;
  /** Every N days, weeks, months or years. */
  interval: number;
  /** Weekly only: ISO weekdays, 1 = Monday to 7 = Sunday. */
  weekdays?: number[];
  /** The anchor: the first date of the pattern (YYYY-MM-DD). Weeks, months and the day of the month count from it. */
  startDate: string;
  /** Local time of day, HH:mm. */
  time: string;
  /** Last date that may occur, inclusive. */
  until?: string;
  /** Number of occurrences counted from `startDate`, skipped ones included. */
  count?: number;
  /**
   * Occurrences before this date are not made. Set when "this and all future ones" splits a series,
   * so the new part keeps the old anchor (week parity, day of the month, count).
   */
  notBefore?: string;
};

export type ReminderSeries = {
  id: string;
  title: string;
  notes: string;
  location: string;
  domain: EventDomain;
  category: EventCategory;
  critical: boolean;
  reminderOffsetsMinutes: number[];
  timeZone: string;
  rule: SeriesRule;
  /** Dates whose occurrence was deleted; expansion never brings them back. */
  skippedDates: string[];
  revision: number;
  createdAt: string;
  updatedAt: string;
  deletedAt?: string;
  idempotencyKey?: string;
};

export type SeriesRuleInput = {
  freq: SeriesFrequency;
  interval?: number;
  weekdays?: number[];
  startDate: string;
  time: string;
  until?: string;
  count?: number;
};

export type SeriesInput = {
  title: string;
  timeZone: string;
  rule: SeriesRuleInput;
  notes?: string;
  location?: string;
  critical?: boolean;
  reminderOffsetsMinutes?: number[];
  domain?: EventDomain;
  category?: EventCategory;
  idempotencyKey?: string;
};

export type SeriesUpdateInput = {
  title?: string;
  timeZone?: string;
  notes?: string;
  location?: string;
  critical?: boolean;
  reminderOffsetsMinutes?: number[];
  domain?: EventDomain;
  category?: EventCategory;
  /** Fields to change in the rule. `null` clears `until` or `count`. */
  rule?: Partial<Omit<SeriesRuleInput, "until" | "count">> & { until?: string | null; count?: number | null };
  /** "This and all future ones": change the series from this local date on. Omitted: the whole series. */
  from?: string;
  /** Makes a retried split create the same new series. */
  idempotencyKey?: string;
};

export type DeviceRecord = {
  id: string;
  installationId: string;
  authHash: string;
  deviceName: string;
  createdAt: string;
  lastSeenAt: string;
  pushEnabled?: boolean;
  /** Set when FCM reported the installation as unregistered; cleared when the device reports a new ID. */
  pushInvalidSince?: string;
  /** Set by the admin revoke route: the device token no longer works until the device pairs again. */
  revokedAt?: string;
};

export type EventInput = {
  title: string;
  startAt: string;
  timeZone: string;
  location?: string;
  notes?: string;
  critical?: boolean;
  reminderOffsetsMinutes?: number[];
  idempotencyKey?: string;
  domain?: EventDomain;
  category?: EventCategory;
};

export type TaskReminderInput = EventInput & {
  itemKey: string;
  source?: "chatgpt" | "canvas";
  sourceUrl?: string;
  courseId?: string;
  courseName?: string;
  courseCode?: string;
  courseColorKey?: string;
  schoolItemType?: "assignment" | "quiz" | "discussion" | "peer_review" | "other";
  details?: string;
  pointsPossible?: number;
  submissionTypes?: string[];
  unlockAt?: string;
  lockAt?: string;
  allowedAttempts?: number;
};

export type TaskPublishInput = {
  taskKey: string;
  runId?: string;
  /** Re-create items that were previously deleted (tombstoned). Default false. */
  restoreDeleted?: boolean;
  reminders: TaskReminderInput[];
};

export type OAuthClient = {
  clientId: string;
  clientName: string;
  redirectUris: string[];
  createdAt: string;
};

export type OAuthCode = {
  codeHash: string;
  clientId: string;
  redirectUri: string;
  codeChallenge: string;
  resource: string;
  scope: string;
  expiresAt: number;
};

export type OAuthToken = {
  tokenHash: string;
  kind: "access" | "refresh";
  clientId: string;
  resource: string;
  scope: string;
  expiresAt: number;
  /** For refresh tokens: when it was exchanged (rotated). It stays valid only for a short grace period. */
  rotatedAt?: number;
  /**
   * The sign-in this token descends from: every token of one connection, through all its refreshes,
   * shares it. Tokens issued before October 28, 2026 have none until their next refresh.
   */
  grantId?: string;
  /** Set when the token was revoked (reuse of a rotated refresh token, or the admin revoke route). */
  revokedAt?: number;
};
