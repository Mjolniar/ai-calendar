package com.example.aicalendar

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** HARNESS ONLY: a reminder made on the phone becomes the cloud reminder, under the same id. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadFlows {
    private lateinit var context: Context
    private val repo get() = EventRepository(context)

    private val phoneMade = PlannerEvent(id = "3f2b7c1e-8a4d-4f6b-9c2e-1d5a7b9c0e12", title = "Fill holes", startAtMillis = 2_000_000_000_000L, source = "manual")
    private val cloud = PlannerEvent(id = "cloud-1", title = "Dentist", startAtMillis = 2_000_000_100_000L, remote = true, source = "chatgpt")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repo.saveEvents(listOf(phoneMade, cloud))
    }

    @Test
    fun onlyPhoneMadeRemindersAreOfferedForUpload() {
        assertEquals(listOf(phoneMade.id), repo.localOnly().map { it.id })
        repo.upsert(phoneMade.copy(id = "too-long", title = "x".repeat(501)))
        assertEquals(listOf(phoneMade.id), repo.localOnly().map { it.id })
    }

    @Test
    fun anAcceptedUploadTurnsThePhoneCopyIntoTheCloudReminder() {
        repo.setArchived(phoneMade.id, true)
        assertEquals(UploadOutcome.Adopted, repo.adoptUploaded(phoneMade.copy(archived = true)))
        val adopted = repo.find(phoneMade.id)!!
        assertTrue(adopted.remote)
        assertTrue("archive state carries over", adopted.archived)
        assertTrue(repo.localOnly().isEmpty())
    }

    @Test
    fun anEditOrADeleteDuringTheUploadIsReported() {
        repo.upsert(phoneMade.copy(title = "Fill holes and paint"))
        val edited = repo.adoptUploaded(phoneMade)
        assertTrue(edited is UploadOutcome.EditedMeanwhile)
        assertEquals("Fill holes and paint", (edited as UploadOutcome.EditedMeanwhile).latest.title)

        repo.delete(phoneMade.id)
        assertEquals(UploadOutcome.DeletedMeanwhile, repo.adoptUploaded(phoneMade))
        assertTrue(repo.find(phoneMade.id) == null)
    }

    @Test
    fun theServerListingTheSameIdReplacesThePhoneCopyWithoutADuplicate() {
        val fromServer = phoneMade.copy(remote = true, title = "Fill holes")
        val merge = repo.replaceRemote(RemoteSnapshot(listOf(cloud, fromServer)))
        assertEquals(listOf(phoneMade.id, cloud.id).sorted(), merge.events.map { it.id }.sorted())
        assertTrue(merge.events.single { it.id == phoneMade.id }.remote)
    }

    @Test
    fun aPhoneOnlyReminderTheServerDoesNotListYetStays() {
        val merge = repo.replaceRemote(RemoteSnapshot(listOf(cloud)))
        assertTrue(merge.events.any { it.id == phoneMade.id && !it.remote })
    }
}
