import { describe, expect, it } from "vitest";
import { CanvasSyncService } from "../src/canvas.js";
import { CalendarService } from "../src/service.js";
import { MemoryCalendarStore } from "../src/store.js";
import { NoopNotifier } from "../src/notifier.js";

function canvasResponse(payload: unknown) {
  return new Response(JSON.stringify(payload), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function plannerItem(dueAt: string) {
  return {
    course_id: 1,
    plannable_id: 42,
    plannable_type: "assignment",
    plannable_date: dueAt,
    context_name: "ETH 110",
    html_url: "https://school.instructure.com/courses/1/assignments/42",
    plannable: {
      id: 42,
      title: "Ethics paper",
      due_at: dueAt,
      description: "<p>Submit the final draft.</p>",
      workflow_state: "published",
    },
  };
}

function assignmentDetail(dueAt: string) {
  return {
    id: 42,
    name: "Ethics paper",
    description: "<h2>Instructions</h2><p>Upload the final draft as a PDF.</p>",
    points_possible: 25,
    grading_type: "points",
    submission_types: ["online_upload"],
    due_at: dueAt,
    unlock_at: "2026-09-09T15:00:00-06:00",
    lock_at: dueAt,
    workflow_state: "published",
    published: true,
    locked_for_user: false,
    html_url: "https://school.instructure.com/courses/1/assignments/42",
  };
}

describe("Canvas synchronization", () => {
  it("creates School reminders and updates changed due dates without duplicates", async () => {
    const store = new MemoryCalendarStore();
    const calendar = new CalendarService(store, new NoopNotifier());
    let dueAt = "2026-10-10T23:59:00-06:00";
    const fetcher: typeof fetch = async (input) => {
      const url = String(input);
      if (url.includes("/api/v1/courses?") && !url.includes("planner/items")) {
        return canvasResponse([{ id: 1, name: "Workplace Ethics", course_code: "ETH 110" }]);
      }
      if (url.endsWith("/api/v1/courses/1/assignments/42")) return canvasResponse(assignmentDetail(dueAt));
      return canvasResponse(url.includes("incomplete_items") ? [plannerItem(dueAt)] : []);
    };
    const sync = new CanvasSyncService(calendar, fetcher);
    const config = { baseUrl: "https://school.instructure.com", accessToken: "secret-token" };

    const created = await sync.sync(config, new Date("2026-09-08T12:00:00Z"));
    expect(created.created).toBe(1);
    expect(created.detailsFetched).toBe(1);
    expect(created.detailsFailed).toBe(0);
    let events = await store.listEvents(true);
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({
      domain: "school",
      category: "school",
      source: "canvas",
      courseId: "1",
      courseName: "Workplace Ethics",
      courseCode: "ETH 110",
      courseColorKey: "1",
      schoolItemType: "assignment",
      pointsPossible: 25,
      submissionTypes: ["online_upload"],
    });
    expect(events[0].details).toContain("Upload the final draft as a PDF.");
    expect(events[0].notes).toContain("Instructions:");
    expect(events[0].notes).toContain("Upload the final draft as a PDF.");
    expect(events[0].notes).toContain("Points: 25");
    expect(events[0].notes).toContain("Submission: file upload");

    dueAt = "2026-10-12T23:59:00-06:00";
    const updated = await sync.sync(config, new Date("2026-09-08T12:05:00Z"));
    expect(updated.updated).toBe(1);
    events = await store.listEvents(true);
    expect(events).toHaveLength(1);
    expect(events[0].startAt).toBe("2026-10-13T05:59:00.000Z");

    const unchanged = await sync.sync(config, new Date("2026-09-08T12:10:00Z"));
    expect(unchanged.detailsFetched).toBe(0);
    expect(unchanged.unchanged).toBe(1);
  });

  it("adds quiz and discussion details to the calendar description", async () => {
    const store = new MemoryCalendarStore();
    const calendar = new CalendarService(store, new NoopNotifier());
    const dueAt = "2026-09-20T23:59:00-06:00";
    const quiz = {
      course_id: 2,
      plannable_id: 7,
      plannable_type: "quiz",
      plannable_date: dueAt,
      context_name: "CHM 110L",
      html_url: "https://school.instructure.com/courses/2/quizzes/7",
      plannable: { id: 7, title: "Safety Quiz", due_at: dueAt, workflow_state: "published" },
    };
    const discussion = {
      course_id: 2,
      plannable_id: 8,
      plannable_type: "discussion_topic",
      plannable_date: dueAt,
      context_name: "CHM 110",
      html_url: "https://school.instructure.com/courses/2/discussion_topics/8",
      plannable: { id: 8, title: "Scientific Method Discussion", due_at: dueAt, workflow_state: "published" },
    };
    const fetcher: typeof fetch = async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/courses/2/quizzes/7")) return canvasResponse({
        id: 7,
        title: "Safety Quiz",
        description: "<p>Read the safety documents before starting.</p>",
        points_possible: 10,
        due_at: dueAt,
        lock_at: dueAt,
        question_count: 20,
        time_limit: 45,
        allowed_attempts: -1,
        quiz_type: "assignment",
        require_lockdown_browser: true,
        published: true,
        locked_for_user: false,
      });
      if (url.endsWith("/api/v1/courses/2/discussion_topics/8")) return canvasResponse({
        id: 8,
        title: "Scientific Method Discussion",
        message: "<p>Post one example and reply to two classmates.</p>",
        due_at: dueAt,
        lock_at: dueAt,
        discussion_type: "threaded",
        require_initial_post: true,
        assignment: { points_possible: 15, grading_type: "points", submission_types: ["discussion_topic"] },
        published: true,
        locked_for_user: false,
      });
      return canvasResponse(url.includes("incomplete_items") ? [quiz, discussion] : []);
    };
    const sync = new CanvasSyncService(calendar, fetcher);
    const result = await sync.sync({ baseUrl: "https://school.instructure.com", accessToken: "secret-token" }, new Date("2026-09-08T12:00:00Z"));

    expect(result.detailsFetched).toBe(2);
    const events = await store.listEvents(true);
    const quizEvent = events.find((event) => event.title === "Safety Quiz");
    const discussionEvent = events.find((event) => event.title === "Scientific Method Discussion");
    expect(quizEvent?.notes).toContain("Questions: 20");
    expect(quizEvent?.notes).toContain("Time limit: 45 minutes");
    expect(quizEvent?.notes).toContain("Attempts: unlimited");
    expect(quizEvent?.notes).toContain("Lockdown browser: required");
    expect(discussionEvent?.notes).toContain("Points: 15");
    expect(discussionEvent?.notes).toContain("Discussion: Threaded");
    expect(discussionEvent?.notes).toContain("Initial post: required");
    expect(discussionEvent?.notes).toContain("reply to two classmates");
  });

  it("treats assignment-backed Canvas quizzes as critical quizzes", async () => {
    const store = new MemoryCalendarStore();
    const calendar = new CalendarService(store, new NoopNotifier());
    const dueAt = "2026-09-22T23:59:00-06:00";
    const item = {
      course_id: 2,
      plannable_id: 9,
      plannable_type: "assignment",
      plannable_date: dueAt,
      context_name: "CHM 110",
      plannable: {
        id: 9,
        title: "Chapter knowledge check",
        due_at: dueAt,
        submission_types: ["online_quiz"],
        workflow_state: "published",
      },
    };
    const fetcher: typeof fetch = async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/courses/2/assignments/9")) {
        return canvasResponse(item.plannable);
      }
      return canvasResponse(url.includes("incomplete_items") ? [item] : []);
    };
    const sync = new CanvasSyncService(calendar, fetcher);

    await sync.sync(
      { baseUrl: "https://school.instructure.com", accessToken: "secret-token" },
      new Date("2026-09-08T12:00:00Z"),
    );

    const event = (await store.listEvents(true))[0];
    expect(event.critical).toBe(true);
    expect(event.schoolItemType).toBe("quiz");
    expect(event.reminderOffsetsMinutes).toEqual([4320, 1440, 120, 60, 0]);
  });

  it.each([
    ["Chapter 2 Quiz", "assignment", [4320, 1440, 120, 60, 0]],
    ["Lab 5 Quiz- Flame Colors", "assignment", [4320, 1440, 120, 60, 0]],
    ["Final paper", "assignment", [1440]],
    ["Exam 1", "quiz", [10080, 1440, 120, 60, 0]],
    ["Final", "assignment", [10080, 1440, 120, 60, 0]],
    ["Midterm", "assignment", [10080, 1440, 120, 60, 0]],
    ["Test 2", "assignment", [10080, 1440, 120, 60, 0]],
    ["Final project", "assignment", [1440]],
    ["Exam survey", "quiz", [1440]],
    ["Quiz reflection", "assignment", [1440]],
    ["Reading check", "assignment", [1440]],
  ])("keeps source warnings stable across repeated syncs for %s", async (title, type, offsets) => {
    const store = new MemoryCalendarStore();
    const calendar = new CalendarService(store, new NoopNotifier());
    let dueAt = "2026-10-10T23:59:00-06:00";
    const fetcher: typeof fetch = async (input) => {
      const url = String(input);
      const item = plannerItem(dueAt);
      item.plannable.title = title;
      item.plannable_type = type;
      if (/\/courses\/1\/(assignments|quizzes)\/42$/.test(url)) {
        return canvasResponse({ ...assignmentDetail(dueAt), name: title, title });
      }
      return canvasResponse(url.includes("incomplete_items") ? [item] : []);
    };
    const sync = new CanvasSyncService(calendar, fetcher);
    const config = { baseUrl: "https://school.instructure.com", accessToken: "secret-token" };
    await sync.sync(config, new Date("2026-09-08T12:00:00Z"));
    const original = (await store.listEvents(true))[0];
    expect(original.reminderOffsetsMinutes).toEqual(offsets);
    expect((await sync.sync(config, new Date("2026-09-08T12:05:00Z"))).unchanged).toBe(1);
    dueAt = "2026-10-12T23:59:00-06:00";
    await sync.sync(config, new Date("2026-09-08T12:10:00Z"));
    const events = await store.listEvents(true);
    expect(events).toHaveLength(1);
    expect(events[0].id).toBe(original.id);
    expect(events[0].domain).toBe("school");
    expect(events[0].reminderOffsetsMinutes).toEqual(offsets);
    expect(events[0].startAt).toBe("2026-10-13T05:59:00.000Z");
  });

  it("archives a Canvas reminder after Canvas reports it complete", async () => {
    const store = new MemoryCalendarStore();
    const calendar = new CalendarService(store, new NoopNotifier());
    let complete = false;
    const item = plannerItem("2026-10-10T23:59:00-06:00");
    const fetcher: typeof fetch = async (input) => {
      const url = String(input);
      const filter = new URL(url).searchParams.get("filter");
      return canvasResponse(filter === (complete ? "complete_items" : "incomplete_items") ? [item] : []);
    };
    const sync = new CanvasSyncService(calendar, fetcher);
    const config = { baseUrl: "https://school.instructure.com", accessToken: "secret-token" };

    await sync.sync(config, new Date("2026-09-08T12:00:00Z"));
    complete = true;
    const result = await sync.sync(config, new Date("2026-09-08T12:05:00Z"));

    expect(result.archived).toBe(1);
    expect((await store.listEvents(true))[0].done).toBe(true);
  });

  it("ignores Canvas calendar events while retaining graded assignments", async () => {
    const store = new MemoryCalendarStore();
    const calendar = new CalendarService(store, new NoopNotifier());
    const meeting = {
      plannable_id: 90,
      plannable_type: "calendar_event",
      plannable_date: "2026-09-10T15:00:00-06:00",
      context_name: "ETH 110",
      plannable: { id: 90, title: "ETH 110 class meeting", start_at: "2026-09-10T15:00:00-06:00" },
    };
    const fetcher: typeof fetch = async (input) => {
      const url = String(input);
      return canvasResponse(url.includes("incomplete_items") ? [meeting] : []);
    };
    const sync = new CanvasSyncService(calendar, fetcher);

    const result = await sync.sync(
      { baseUrl: "https://school.instructure.com", accessToken: "secret-token" },
      new Date("2026-09-08T12:00:00Z"),
    );

    expect(result.active).toBe(0);
    expect(result.detailsFetched).toBe(0);
    expect(await store.listEvents(true)).toEqual([]);
  });

  it("does not repeatedly fetch an assignment whose description is blank", async () => {
    const store = new MemoryCalendarStore();
    const calendar = new CalendarService(store, new NoopNotifier());
    const dueAt = "2026-10-10T23:59:00-06:00";
    let detailRequests = 0;
    const fetcher: typeof fetch = async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/courses/1/assignments/42")) {
        detailRequests += 1;
        return canvasResponse({ ...assignmentDetail(dueAt), description: null });
      }
      return canvasResponse(url.includes("incomplete_items") ? [plannerItem(dueAt)] : []);
    };
    const sync = new CanvasSyncService(calendar, fetcher);
    const config = { baseUrl: "https://school.instructure.com", accessToken: "secret-token" };

    await sync.sync(config, new Date("2026-09-08T12:00:00Z"));
    const unchanged = await sync.sync(config, new Date("2026-09-08T12:05:00Z"));

    expect(detailRequests).toBe(1);
    expect(unchanged.detailsFetched).toBe(0);
    expect(unchanged.unchanged).toBe(1);
  });
});
