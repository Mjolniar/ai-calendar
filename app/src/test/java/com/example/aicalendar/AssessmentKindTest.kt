package com.example.aicalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssessmentKindTest {
    @Test
    fun recognizesStructuredAndNamedAssessments() {
        assertEquals(AssessmentKind.QUIZ, schoolEvent("Knowledge check", schoolItemType = "quiz").assessmentKind())
        assertEquals(AssessmentKind.QUIZ, schoolEvent("Chapter review", submissionTypes = listOf("online_quiz")).assessmentKind())
        assertEquals(AssessmentKind.TEST, schoolEvent("Unit 4 test").assessmentKind())
        assertEquals(AssessmentKind.EXAM, schoolEvent("Final examination").assessmentKind())
        assertEquals(AssessmentKind.EXAM, schoolEvent("Midterm").assessmentKind())
    }

    @Test
    fun avoidsIncidentalAndPartialMatches() {
        assertNull(PlannerEvent(title = "Medical test results", startAtMillis = 1L).assessmentKind())
        assertNull(schoolEvent("Contest entry").assessmentKind())
        assertNull(schoolEvent("Final project").assessmentKind())
        assertNull(schoolEvent("Weekly assignment", schoolItemType = "assignment").assessmentKind())
    }

    private fun schoolEvent(
        title: String,
        schoolItemType: String = "",
        submissionTypes: List<String> = emptyList(),
    ) = PlannerEvent(
        title = title,
        startAtMillis = 1L,
        domain = EventDomain.SCHOOL,
        schoolItemType = schoolItemType,
        submissionTypes = submissionTypes,
    )
}
