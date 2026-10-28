import { describe, expect, it } from "vitest";
import { classifyAssessment } from "../src/canvas.js";

// Under-warning is the costly mistake: a quiz or exam without its early alerts. Each row is
// [title, Canvas type, submission types, expected].
const cases: Array<[string, string, string[] | undefined, "exam" | "quiz" | null]> = [
  // Canvas says it is a quiz: a word in the title never overrides that.
  ["Reading Quiz 4", "quiz", undefined, "quiz"],
  ["Quiz 3 (Review Ch 1-4)", "quiz", undefined, "quiz"],
  ["Chapter 5 check-in", "assignment", ["online_quiz"], "quiz"],
  ["Weekly reflection", "quiz", undefined, "quiz"],
  ["Lab 5 Quiz- Flame Colors", "assignment", undefined, "quiz"],
  // Exams named as such stay exams whatever else the title says.
  ["Final Exam - Essay Portion", "assignment", undefined, "exam"],
  ["Midterm Review Exam", "quiz", undefined, "exam"],
  ["Midterm", "assignment", undefined, "exam"],
  ["Unit 3 Test", "assignment", undefined, "exam"],
  ["Finals week: Final", "assignment", undefined, "exam"],
  // Not assessments.
  ["Final Draft: Lab Report", "assignment", undefined, null],
  ["Final Presentation", "assignment", undefined, null],
  ["Final Paper", "assignment", undefined, null],
  ["Exam 2 Corrections", "assignment", undefined, null],
  ["Test Your Knowledge (ungraded)", "assignment", undefined, null],
  ["Pretest survey", "assignment", undefined, null],
  ["Practice Quiz", "quiz", undefined, null],
  ["Reading response 4", "assignment", ["online_text_entry"], null],
  ["Essay draft", "assignment", undefined, null],
  ["Quiz reflection", "assignment", undefined, null],
];

describe("Canvas assessment classification", () => {
  it.each(cases)("%s (%s) is %s", (title, type, submissionTypes, expected) => {
    expect(classifyAssessment(title, type, submissionTypes)).toBe(expected);
  });
});
