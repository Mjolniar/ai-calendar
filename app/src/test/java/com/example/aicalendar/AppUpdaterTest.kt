package com.example.aicalendar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AppUpdaterTest {
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun releaseParsesOnlyWhenEveryFieldIsSound() {
        val good = JSONObject("""{"versionCode":27,"versionName":"0.15.0","sha256":"${"a".repeat(64)}","size":14445935,"publishedAt":"x"}""")
        assertEquals(AppRelease(27, "0.15.0", "a".repeat(64), 14_445_935), AppRelease.fromJson(good))
        assertNull(AppRelease.fromJson(JSONObject(good.toString()).put("sha256", "not-a-hash")))
        assertNull(AppRelease.fromJson(JSONObject(good.toString()).put("size", 0)))
        assertNull(AppRelease.fromJson(JSONObject(good.toString()).put("size", AppRelease.MAX_APK_BYTES + 1)))
        assertNull(AppRelease.fromJson(JSONObject("""{"versionName":"0.15.0"}""")))
    }

    @Test
    fun anInstallWaitingForThePermissionIsKeptForHalfAnHour() {
        val release = AppRelease(30, "0.18.0", "b".repeat(64), 15_000_000)
        val asked = 1_000_000L
        val stored = AppUpdater.pendingJson(release, asked)
        assertEquals(release, AppUpdater.pendingFrom(stored, asked + 60_000))
        assertEquals(release, AppUpdater.pendingFrom(stored, asked + AppUpdater.PENDING_INSTALL_MS))
        assertNull("forgotten after half an hour", AppUpdater.pendingFrom(stored, asked + AppUpdater.PENDING_INSTALL_MS + 1))
        assertNull(AppUpdater.pendingFrom(null, asked))
        assertNull(AppUpdater.pendingFrom("{broken", asked))
        assertEquals(release, AppRelease.fromJson(release.toJson()))
    }

    @Test
    fun onlyTheExactPublishedBytesPassVerification() {
        val bytes = "a build".toByteArray()
        val file = File.createTempFile("update", ".apk").apply { writeBytes(bytes); deleteOnExit() }
        val release = AppRelease(27, "0.15.0", sha256(bytes), bytes.size.toLong())
        assertTrue(AppUpdater.verify(file, release))
        assertFalse(AppUpdater.verify(file, release.copy(sha256 = sha256("another build".toByteArray()))))
        assertFalse(AppUpdater.verify(file, release.copy(size = release.size + 1)))
        file.writeBytes("a bUild".toByteArray())
        assertFalse(AppUpdater.verify(file, release))
        assertFalse(AppUpdater.verify(File(file.path + ".missing"), release))
    }
}
