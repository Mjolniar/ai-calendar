import { htmlToText } from "html-to-text";
import type { CalendarService } from "./service.js";
import type { ReminderEvent, TaskReminderInput } from "./types.js";

export type CanvasConfig = {
  baseUrl: string;
  accessToken: string;
};

export type CanvasSyncResult = {
  fetched: number;
  active: number;
  completed: number;
  detailsFetched: number;
  detailsFailed: number;
  coursesFound: number;
  routineMeetingsArchived: number;
  created: number;
  updated: number;
  unchanged: number;
  archived: number;
  notified: number;
};

type CanvasPlannerItem = {
  course_id?: string | number;
  plannable_id?: string | number;
  plannable_type?: string;
  plannable_date?: string;
  submissions?: CanvasSubmissionSummary | null;
  context_name?: string;
  html_url?: string;
  detail?: CanvasDetail;
  plannable?: {
    id?: string | number;
    title?: string;
    name?: string;
    description?: string;
    details?: string;
    due_at?: string;
    todo_date?: string;
    start_at?: string;
    workflow_state?: string;
    html_url?: string;
    course_id?: string | number;
    points_possible?: number | null;
    submission_types?: string[] | null;
    lock_at?: string | null;
    unlock_at?: string | null;
    published?: boolean;
    locked_for_user?: boolean;
  };
};

type CanvasSubmissionSummary = {
  submitted?: boolean;
  excused?: boolean;
  graded?: boolean;
  late?: boolean;
  missing?: boolean;
  needs_grading?: boolean;
  has_feedback?: boolean;
  redo_request?: boolean;
};

type CanvasCourse = {
  id?: string | number;
  name?: string;
  course_code?: string;
  workflow_state?: string;
};

type CanvasDetail = {
  id?: string | number;
  title?: string;
  name?: string;
  description?: string | null;
  message?: string | null;
  details?: string | null;
  due_at?: string | null;
  todo_date?: string | null;
  start_at?: string | null;
  lock_at?: string | null;
  unlock_at?: string | null;
  points_possible?: number | null;
  grading_type?: string | null;
  submission_types?: string[] | null;
  allowed_attempts?: number | null;
  time_limit?: number | null;
  question_count?: number | null;
  quiz_type?: string | null;
  question_types?: string[] | null;
  discussion_type?: string | null;
  require_initial_post?: boolean | null;
  require_lockdown_browser?: boolean | null;
  one_question_at_a_time?: boolean | null;
  cant_go_back?: boolean | null;
  published?: boolean | null;
  locked_for_user?: boolean | null;
  workflow_state?: string | null;
  html_url?: string | null;
  course_id?: string | number | null;
  assignment?: CanvasDetail | null;
};

/** Guards against a Canvas pagination loop (e.g. a repeated rel="next" link). */
const MAX_PAGES = 50;

const INCLUDED_TYPES = new Set([
  "assignment",
  "quiz",
  "discussion_topic",
  "sub_assignment",
  "peer_review_sub_assignment",
]);

export class CanvasSyncService {
  constructor(
    private readonly calendar: CalendarService,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async sync(config: CanvasConfig, now = new Date()): Promise<CanvasSyncResult> {
    const normalized = normalizeConfig(config);
    const start = new Date(now);
    start.setUTCDate(start.getUTCDate() - 7);
    const end = new Date(now);
    end.setUTCMonth(end.getUTCMonth() + 6);

    const [activeItems, completedItems, courses] = await Promise.all([
      this.fetchPlannerItems(normalized, "incomplete_items", start, end),
      this.fetchPlannerItems(normalized, "complete_items", start, end),
      this.fetchCourses(normalized),
    ]);
    const existingByKey = new Map((await this.calendar.list(true))
      .filter((event) => event.sourceTaskKey === "canvas" && event.sourceItemKey)
      .map((event) => [event.sourceItemKey!, event]));
    const enrichment = await this.enrichPlannerItems(normalized, activeItems, existingByKey);
    const courseIndex = new Map(courses
      .filter((course) => course.id !== undefined)
      .map((course) => [String(course.id), course]));
    const reminders = enrichment.items
      .map((item) => toReminder(item, courseIndex, existingByKey.get(itemKey(item) ?? "")))
      .filter((item): item is TaskReminderInput => item !== null);
    const completedKeys = new Set(completedItems.map(itemKey).filter((key): key is string => Boolean(key)));
    const published = reminders.length > 0
      ? await this.calendar.publishTaskReminders({
        taskKey: "canvas",
        runId: now.toISOString(),
        reminders,
      })
      : { created: 0, updated: 0, unchanged: 0, notified: 0 };
    const archived = await this.calendar.setTaskItemsDone("canvas", completedKeys);
    const routineMeetings = await this.calendar.archiveRoutineSchoolMeetings();

    return {
      fetched: activeItems.length + completedItems.length,
      active: reminders.length,
      completed: completedKeys.size,
      detailsFetched: enrichment.detailsFetched,
      detailsFailed: enrichment.detailsFailed,
      coursesFound: courses.length,
      routineMeetingsArchived: routineMeetings.updated,
      created: published.created,
      updated: published.updated,
      unchanged: published.unchanged,
      archived: archived.updated,
      notified: published.notified + archived.notified + routineMeetings.notified,
    };
  }

  private async fetchCourses(config: CanvasConfig): Promise<CanvasCourse[]> {
    const origin = new URL(config.baseUrl).origin;
    let nextUrl: string | null = `${config.baseUrl}/api/v1/courses?${new URLSearchParams({
      enrollment_state: "active",
      per_page: "100",
    })}`;
    const courses: CanvasCourse[] = [];
    let pages = 0;
    try {
      while (nextUrl) {
        if (++pages > MAX_PAGES) throw new Error("Canvas course pagination exceeded the page limit");
        if (new URL(nextUrl).origin !== origin) throw new Error("Canvas pagination left the configured host");
        const response = await this.fetchImpl(nextUrl, {
          headers: {
            Accept: "application/json",
            Authorization: `Bearer ${config.accessToken}`,
          },
          signal: AbortSignal.timeout(20_000),
        });
        if (!response.ok) throw new Error(`Canvas returned HTTP ${response.status}`);
        const page = await response.json() as unknown;
        if (!Array.isArray(page)) throw new Error("Canvas returned an invalid courses response");
        courses.push(...page as CanvasCourse[]);
        nextUrl = nextLink(response.headers.get("link"));
      }
    } catch (error) {
      console.warn("Canvas course lookup failed; using planner course labels", { error });
      return [];
    }
    return courses;
  }

  private async enrichPlannerItems(
    config: CanvasConfig,
    items: CanvasPlannerItem[],
    existingByKey: Map<string, ReminderEvent>,
  ): Promise<{ items: CanvasPlannerItem[]; detailsFetched: number; detailsFailed: number }> {
    const results = [...items];
    const requests = new Map<string, Promise<CanvasDetail | null>>();
    const eligible = items.filter((item) => {
      const key = itemKey(item);
      return detailUrl(config, item) !== null && needsDetail(item, key ? existingByKey.get(key) : undefined);
    });
    const eligibleKeys = new Set(eligible.map((item) => itemKey(item)).filter(Boolean));
    let cursor = 0;

    const worker = async () => {
      while (true) {
        const index = cursor++;
        if (index >= results.length) return;
        if (!eligibleKeys.has(itemKey(results[index]))) continue;
        const url = detailUrl(config, results[index]);
        if (!url) continue;
        let request = requests.get(url);
        if (!request) {
          request = this.fetchDetail(url, config.accessToken);
          requests.set(url, request);
        }
        const detail = await request;
        if (detail) results[index] = { ...results[index], detail };
      }
    };

    await Promise.all(Array.from({ length: Math.min(6, Math.max(1, results.length)) }, () => worker()));
    const detailsFetched = results.filter((item) => item.detail).length;
    return {
      items: results,
      detailsFetched,
      detailsFailed: eligible.length - detailsFetched,
    };
  }

  private async fetchDetail(url: string, accessToken: string): Promise<CanvasDetail | null> {
    try {
      const response = await this.fetchImpl(url, {
        headers: {
          Accept: "application/json",
          Authorization: `Bearer ${accessToken}`,
        },
        signal: AbortSignal.timeout(20_000),
      });
      if (!response.ok) {
        console.warn("Canvas detail request failed", { url, status: response.status });
        return null;
      }
      const payload = await response.json() as unknown;
      return isRecord(payload) ? payload as CanvasDetail : null;
    } catch (error) {
      console.warn("Canvas detail request failed", { url, error });
      return null;
    }
  }

  private async fetchPlannerItems(
    config: CanvasConfig,
    filter: "incomplete_items" | "complete_items",
    start: Date,
    end: Date,
  ): Promise<CanvasPlannerItem[]> {
    const origin = new URL(config.baseUrl).origin;
    let nextUrl: string | null = `${config.baseUrl}/api/v1/planner/items?${new URLSearchParams({
      start_date: start.toISOString(),
      end_date: end.toISOString(),
      filter,
      per_page: "100",
    })}`;
    const items: CanvasPlannerItem[] = [];
    let pages = 0;
    while (nextUrl) {
      if (++pages > MAX_PAGES) throw new Error("Canvas planner pagination exceeded the page limit");
      if (new URL(nextUrl).origin !== origin) throw new Error("Canvas pagination left the configured host");
      const response = await this.fetchImpl(nextUrl, {
        headers: {
          Accept: "application/json",
          Authorization: `Bearer ${config.accessToken}`,
        },
        signal: AbortSignal.timeout(20_000),
      });
      if (!response.ok) throw new Error(`Canvas returned HTTP ${response.status}`);
      const page = await response.json() as unknown;
      if (!Array.isArray(page)) throw new Error("Canvas returned an invalid planner response");
      items.push(...page as CanvasPlannerItem[]);
      nextUrl = nextLink(response.headers.get("link"));
    }
    return items;
  }
}

function normalizeConfig(config: CanvasConfig): CanvasConfig {
  const baseUrl = config.baseUrl.trim().replace(/\/$/, "");
  const url = new URL(baseUrl);
  if (url.protocol !== "https:") throw new Error("Canvas base URL must use HTTPS");
  if (!config.accessToken.trim()) throw new Error("Canvas access token is not configured");
  return { baseUrl, accessToken: config.accessToken.trim() };
}

function toReminder(
  item: CanvasPlannerItem,
  courses: Map<string, CanvasCourse>,
  existing?: ReminderEvent,
): TaskReminderInput | null {
  const key = itemKey(item);
  const type = item.plannable_type?.toLowerCase() ?? "";
  const nestedAssignment = assignmentDetail(item.detail);
  const title = firstText(item.detail?.title, item.detail?.name, item.plannable?.title, item.plannable?.name);
  const dueValue = firstText(
    item.detail?.due_at,
    nestedAssignment?.due_at,
    item.plannable?.due_at,
    item.plannable_date,
    item.detail?.todo_date,
    item.plannable?.todo_date,
    item.detail?.start_at,
    item.plannable?.start_at,
  );
  const startAt = normalizeDate(dueValue);
  if (!key || !INCLUDED_TYPES.has(type) || !title || !startAt || item.detail?.workflow_state === "deleted" || item.plannable?.workflow_state === "deleted") return null;
  const submissionTypes = firstArray(item.detail?.submission_types, nestedAssignment?.submission_types, item.plannable?.submission_types)
    ?? existing?.submissionTypes;
  const nonAssessment = /\b(survey|reflection|project|essay|review|practice|reading)\b|\bfinal\s+paper\b/i.test(title);
  const exam = !nonAssessment && /\b(exams?|finals?|midterms?|tests?)\b/i.test(title);
  const quiz = !nonAssessment && (/\bquizzes?\b|\bquiz\b/i.test(title)
    || type === "quiz"
    || submissionTypes?.some((submissionType) => submissionType.toLowerCase() === "online_quiz") === true);
  const critical = exam || quiz;
  const sourceUrl = item.html_url ?? item.detail?.html_url ?? item.plannable?.html_url;
  const notes = item.detail || !existing
    ? buildNotes(item, type, title, startAt, dueValue, nestedAssignment, sourceUrl)
    : existing.notes;
  const courseId = courseIdFor(item);
  const course = courseId ? courses.get(courseId) : undefined;
  const courseName = firstText(course?.name, item.context_name);
  const courseCode = firstText(course?.course_code, courseCodeFromContext(item.context_name));
  const details = item.detail
    ? readableDetails(item, nestedAssignment)
    : existing?.details || readableDetails(item, nestedAssignment);
  const pointsPossible = firstNumber(item.detail?.points_possible, nestedAssignment?.points_possible, item.plannable?.points_possible);
  const unlockAt = normalizeOptionalDate(firstText(item.detail?.unlock_at, nestedAssignment?.unlock_at, item.plannable?.unlock_at));
  const lockAt = normalizeOptionalDate(firstText(item.detail?.lock_at, nestedAssignment?.lock_at, item.plannable?.lock_at));
  const allowedAttempts = firstNumber(item.detail?.allowed_attempts, nestedAssignment?.allowed_attempts);
  return {
    itemKey: key,
    title,
    startAt,
    timeZone: "America/Denver",
    notes,
    critical,
    reminderOffsetsMinutes: exam ? [10080, 1440, 120, 60, 0]
      : quiz ? [4320, 1440, 120, 60, 0] : [1440],
    domain: "school",
    category: "school",
    source: "canvas",
    ...(sourceUrl ? { sourceUrl } : {}),
    ...(courseId ? { courseId } : {}),
    ...(courseName ? { courseName } : {}),
    ...(courseCode ? { courseCode } : {}),
    ...((courseId || courseName) ? { courseColorKey: courseId || courseName } : {}),
    schoolItemType: schoolItemType(type, submissionTypes),
    ...(details ? { details } : {}),
    ...(pointsPossible !== undefined ? { pointsPossible } : {}),
    ...(submissionTypes?.length ? { submissionTypes } : {}),
    ...(unlockAt ? { unlockAt } : {}),
    ...(lockAt ? { lockAt } : {}),
    ...(allowedAttempts !== undefined ? { allowedAttempts } : {}),
  };
}

function needsDetail(item: CanvasPlannerItem, existing: ReminderEvent | undefined): boolean {
  if (!existing || !existing.schoolItemType) return true;
  const title = firstText(item.plannable?.title, item.plannable?.name);
  const due = normalizeDate(firstText(item.plannable?.due_at, item.plannable_date, item.plannable?.todo_date, item.plannable?.start_at));
  return (title !== undefined && title !== existing.title) || (due !== null && due !== existing.startAt);
}

function courseIdFor(item: CanvasPlannerItem): string | undefined {
  const value = item.course_id ?? item.detail?.course_id ?? item.plannable?.course_id;
  return value === undefined || value === null ? undefined : String(value);
}

function courseCodeFromContext(context: string | undefined): string | undefined {
  if (!context) return undefined;
  const segments = context.split(" - ").map((value) => value.trim()).filter(Boolean);
  return segments.length >= 2 ? segments[1] : segments[0];
}

function schoolItemType(type: string, submissionTypes?: string[]): TaskReminderInput["schoolItemType"] {
  if (type === "quiz" || submissionTypes?.some((value) => value.toLowerCase() === "online_quiz")) return "quiz";
  if (type === "discussion_topic") return "discussion";
  if (type === "peer_review_sub_assignment") return "peer_review";
  if (type === "assignment" || type === "sub_assignment") return "assignment";
  return "other";
}

function readableDetails(item: CanvasPlannerItem, nestedAssignment: CanvasDetail | undefined): string {
  const raw = firstText(
    item.detail?.description,
    item.detail?.message,
    item.detail?.details,
    nestedAssignment?.description,
    item.plannable?.description,
    item.plannable?.details,
  );
  return raw ? htmlToText(raw, {
    wordwrap: false,
    selectors: [{ selector: "a", options: { ignoreHref: false } }],
  }).trim().slice(0, 6000) : "";
}

function normalizeOptionalDate(value: string | undefined): string | undefined {
  if (!value) return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? undefined : parsed.toISOString();
}

function buildNotes(
  item: CanvasPlannerItem,
  type: string,
  title: string,
  startAt: string,
  dueValue: string | undefined,
  nestedAssignment: CanvasDetail | undefined,
  sourceUrl: string | undefined,
): string {
  const detail = item.detail;
  const points = firstNumber(detail?.points_possible, nestedAssignment?.points_possible, item.plannable?.points_possible);
  const gradingType = firstText(detail?.grading_type, nestedAssignment?.grading_type);
  const submissionTypes = firstArray(detail?.submission_types, nestedAssignment?.submission_types);
  const unlockAt = firstText(detail?.unlock_at, nestedAssignment?.unlock_at, item.plannable?.unlock_at);
  const lockAt = firstText(detail?.lock_at, nestedAssignment?.lock_at, item.plannable?.lock_at);
  const description = firstText(detail?.description, detail?.message, detail?.details, nestedAssignment?.description, item.plannable?.description, item.plannable?.details);
  const readableDescription = description ? htmlToText(description, {
    wordwrap: false,
    selectors: [{ selector: "a", options: { ignoreHref: true } }],
  }).trim() : "";
  const metadata = [
    item.context_name?.trim(),
    `Type: ${readableType(type)}`,
    `Due: ${formatDue(startAt, dueValue)}`,
    points !== undefined ? `Points: ${formatNumber(points)}` : "",
    gradingType ? `Grading: ${readableValue(gradingType)}` : "",
    submissionTypes?.length ? `Submission: ${submissionTypes.map(readableSubmissionType).join(", ")}` : "",
    unlockAt ? `Available: ${formatDueValue(unlockAt)}` : "",
    lockAt ? `Locks: ${formatDueValue(lockAt)}` : "",
    detail?.published === false ? "Status: unpublished" : detail?.locked_for_user ? "Status: locked for this user" : "Status: published/open",
    item.submissions ? submissionSummary(item.submissions) : "",
  ].filter(Boolean);

  if (type === "quiz") {
    const questionCount = firstNumber(detail?.question_count);
    const timeLimit = firstNumber(detail?.time_limit);
    const attempts = firstNumber(detail?.allowed_attempts);
    if (questionCount !== undefined) metadata.push(`Questions: ${formatNumber(questionCount)}`);
    if (timeLimit !== undefined) metadata.push(`Time limit: ${formatNumber(timeLimit)} minutes`);
    if (attempts !== undefined) metadata.push(`Attempts: ${attempts < 0 ? "unlimited" : formatNumber(attempts)}`);
    if (detail?.quiz_type) metadata.push(`Quiz type: ${readableValue(detail.quiz_type)}`);
    if (detail?.require_lockdown_browser) metadata.push("Lockdown browser: required");
    if (detail?.one_question_at_a_time) metadata.push("Questions shown one at a time");
    if (detail?.cant_go_back) metadata.push("Cannot return to previous questions");
  }
  if (type === "discussion_topic") {
    if (detail?.discussion_type) metadata.push(`Discussion: ${readableValue(detail.discussion_type)}`);
    if (detail?.require_initial_post === true) metadata.push("Initial post: required");
  }

  const prefix = metadata.join("\n");
  const suffix = sourceUrl ? `Canvas: ${sourceUrl}` : "";
  const instructionPrefix = readableDescription ? "Instructions:\n" : "";
  const separatorLength = [prefix, suffix].filter(Boolean).length > 0 ? 4 : 0;
  const availableDescription = Math.max(0, 4000 - prefix.length - suffix.length - instructionPrefix.length - separatorLength);
  const instructions = readableDescription
    ? `${instructionPrefix}${readableDescription.slice(0, availableDescription)}`
    : "";
  return [prefix, instructions, suffix].filter(Boolean).join("\n\n").slice(0, 4000);
}

function detailUrl(config: CanvasConfig, item: CanvasPlannerItem): string | null {
  const type = item.plannable_type?.toLowerCase() ?? "";
  if (!INCLUDED_TYPES.has(type)) return null;
  const courseId = item.course_id ?? item.plannable?.course_id;
  const id = item.plannable_id ?? item.plannable?.id;
  if (courseId === undefined || id === undefined) return null;
  const resource = type === "quiz" ? "quizzes" : type === "discussion_topic" ? "discussion_topics" : "assignments";
  return new URL(`/api/v1/courses/${encodeURIComponent(String(courseId))}/${resource}/${encodeURIComponent(String(id))}`, `${config.baseUrl}/`).toString();
}

function assignmentDetail(detail: CanvasDetail | undefined): CanvasDetail | undefined {
  return detail?.assignment && isRecord(detail.assignment) ? detail.assignment : undefined;
}

function firstText(...values: Array<string | null | undefined>): string | undefined {
  return values.find((value) => typeof value === "string" && value.trim().length > 0)?.trim();
}

function firstNumber(...values: Array<number | null | undefined>): number | undefined {
  const value = values.find((candidate) => typeof candidate === "number" && Number.isFinite(candidate));
  return value ?? undefined;
}

function firstArray(...values: Array<string[] | null | undefined>): string[] | undefined {
  const value = values.find((candidate) => Array.isArray(candidate) && candidate.length > 0);
  return value ?? undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : String(value);
}

function formatDue(startAt: string, originalValue: string | undefined): string {
  const formatted = formatDueValue(startAt);
  return originalValue && /^\d{4}-\d{2}-\d{2}$/.test(originalValue)
    ? `${formatted} (time unknown; 9 AM placeholder)`
    : formatted;
}

function formatDueValue(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.valueOf())) return value;
  return new Intl.DateTimeFormat("en-US", {
    timeZone: "America/Denver",
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
    timeZoneName: "short",
  }).format(parsed);
}

function readableValue(value: string): string {
  return value.replace(/[_-]+/g, " ").replace(/\b\w/g, (character) => character.toUpperCase());
}

function readableSubmissionType(value: string): string {
  const labels: Record<string, string> = {
    discussion_topic: "discussion",
    external_tool: "external tool",
    none: "no submission",
    online_quiz: "Canvas quiz",
    online_text_entry: "text entry",
    online_upload: "file upload",
    on_paper: "in person",
    media_recording: "media recording",
  };
  return labels[value] ?? readableValue(value);
}

function submissionSummary(summary: CanvasSubmissionSummary): string {
  if (summary.missing) return "Submission: missing";
  if (summary.excused) return "Submission: excused";
  if (summary.submitted) return summary.late ? "Submission: submitted late" : "Submission: submitted";
  if (summary.graded) return "Submission: graded";
  return "Submission: not submitted";
}

function itemKey(item: CanvasPlannerItem): string | null {
  const type = item.plannable_type?.toLowerCase();
  const id = item.plannable_id ?? item.plannable?.id;
  return type && id !== undefined ? `${type}:${id}` : null;
}

function normalizeDate(value: string | undefined): string | null {
  if (!value) return null;
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const offset = denverOffset(value);
    return new Date(`${value}T09:00:00${offset}`).toISOString();
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? null : parsed.toISOString();
}

function denverOffset(date: string): string {
  const zoneName = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/Denver",
    timeZoneName: "longOffset",
  }).formatToParts(new Date(`${date}T12:00:00Z`)).find((part) => part.type === "timeZoneName")?.value;
  const match = zoneName?.match(/GMT([+-]\d{2}:\d{2})/);
  return match?.[1] ?? "-07:00";
}

function readableType(value: string): string {
  return value.split("_").map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join(" ");
}

function nextLink(header: string | null): string | null {
  if (!header) return null;
  for (const part of header.split(",")) {
    const match = part.match(/<([^>]+)>;\s*rel="([^"]+)"/);
    if (match?.[2] === "next") return match[1];
  }
  return null;
}
