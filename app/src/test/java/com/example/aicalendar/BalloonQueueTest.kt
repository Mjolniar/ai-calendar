package com.example.aicalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An update prompt stays until it is used or closed; passing balloons wait behind it. */
class BalloonQueueTest {
    private fun message(id: Long, title: String, sticky: Boolean = false) =
        BalloonMessage(id, BalloonKind.INFO, title, actionLabel = if (sticky) "Install" else null, sticky = sticky)

    @Test
    fun aPassingBalloonWaitsBehindAnUpdatePrompt() {
        val queue = BalloonQueue()
        queue.show(message(1, "Update available", sticky = true))
        queue.show(message(2, "Removed from the list"))
        assertEquals("Update available", queue.shown?.title)
        // Closing the prompt shows what waited.
        queue.close(1)
        assertEquals("Removed from the list", queue.shown?.title)
        queue.close(2)
        assertNull(queue.shown)
    }

    @Test
    fun passingBalloonsReplaceEachOtherAndAPromptReplacesAnything() {
        val queue = BalloonQueue()
        queue.show(message(1, "Saved"))
        queue.show(message(2, "Removed from the list"))
        assertEquals("Removed from the list", queue.shown?.title)
        queue.show(message(3, "Allow updates first", sticky = true))
        assertEquals("Allow updates first", queue.shown?.title)
        queue.close(3)
        assertNull("nothing was waiting", queue.shown)
    }

    @Test
    fun onlyTheNewestFewWaitAndAStaleCloseChangesNothing() {
        val queue = BalloonQueue()
        queue.show(message(1, "Update available", sticky = true))
        (2L..7L).forEach { queue.show(message(it, "Info $it")) }
        // A timeout of a balloon that is not on screen any more.
        queue.close(5)
        assertEquals("Update available", queue.shown?.title)
        queue.close(1)
        assertEquals("Info ${8 - BalloonQueue.MAX_WAITING}", queue.shown?.title)
    }
}
