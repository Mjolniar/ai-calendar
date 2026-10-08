package com.example.aicalendar

enum class AssessmentKind(val label: String) {
    QUIZ("Quiz"),
    TEST("Test"),
    EXAM("Exam"),
}

private val quizPattern = Regex("\\bquiz(?:zes)?\\b", RegexOption.IGNORE_CASE)
private val testPattern = Regex("\\btests?\\b", RegexOption.IGNORE_CASE)
private val examPattern = Regex(
    "\\b(?:exam(?:ination)?s?|midterms?)\\b|\\bfinals?\\b(?!\\s+(?:project|paper|presentation|assignment|draft))",
    RegexOption.IGNORE_CASE,
)

fun PlannerEvent.assessmentKind(): AssessmentKind? {
    if (schoolItemType.equals("quiz", ignoreCase = true) ||
        submissionTypes.any { it.equals("online_quiz", ignoreCase = true) }
    ) {
        return AssessmentKind.QUIZ
    }
    if (resolvedDomain() != EventDomain.SCHOOL) return null
    return when {
        quizPattern.containsMatchIn(title) -> AssessmentKind.QUIZ
        examPattern.containsMatchIn(title) -> AssessmentKind.EXAM
        testPattern.containsMatchIn(title) -> AssessmentKind.TEST
        else -> null
    }
}
