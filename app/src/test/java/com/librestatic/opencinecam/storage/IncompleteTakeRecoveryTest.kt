/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.media.storage.ClipRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncompleteTakeRecoveryTest {
    private val id = "11111111-2222-3333-4444-555555555555"

    private class Rows : ClipRows<String> {
        val events = mutableListOf<String>()
        val written = mutableMapOf<String, String>()
        override fun delete(uri: String) { events += "delete:$uri" }
        override fun publish(uri: String) { events += "publish:$uri" }
        override fun writeSidecar(uri: String, bytes: ByteArray) { written[uri] = String(bytes); events += "sidecar:$uri" }
    }

    private class Arr(private val array: kotlinx.serialization.json.JsonArray) {
        fun getString(index: Int) = array[index].jsonPrimitive.content
    }
    private class Doc(private val json: JsonObject) {
        fun getString(key: String) = json.getValue(key).jsonPrimitive.content
        fun getJSONArray(key: String) = Arr(json.getValue(key).jsonArray)
    }
    private fun parse(text: String) = Doc(Json.parseToJsonElement(text).jsonObject)

    private fun row(root: String, handle: String, name: String, mime: String, bytes: Long) =
        RecoveredRow(root, handle, name, mime, bytes, "content://media/external_primary/$handle")

    private fun recover(rows: List<RecoveredRow<String>>, text: Map<String, String> = emptyMap(), fake: Rows = Rows(),
        inserted: MutableList<String> = mutableListOf()): Triple<Int, Rows, List<String>> {
        val published = recoverIncompleteTake(id, rows, fake, { name -> inserted += name; "new:$name" }, { text[it] })
        return Triple(published, fake, inserted)
    }

    @Test fun videoWithBytesIsPublishedWithANewIncompleteSidecar() {
        val (published, rows, inserted) = recover(listOf(row("DCIM", "v", "take.mp4", "video/mp4", 4096)))
        assertEquals(1, published)
        assertEquals(listOf("take.incomplete.json"), inserted)
        assertEquals(listOf("sidecar:new:take.incomplete.json", "publish:new:take.incomplete.json", "publish:v"), rows.events)
        val json = parse(rows.written.getValue("new:take.incomplete.json"))
        assertEquals("incomplete", json.getString("takeStatus"))
        assertEquals("process-death", json.getJSONArray("failureReasons").getString(0))
        assertEquals("opencinecam.recording.v1", json.getString("schema"))
        assertEquals(id, json.getString("bundleId"))
        assertEquals("content://media/external_primary/v", json.getString("videoUri"))
        assertTrue(json.getString("incompleteNote").contains("repair"))
    }

    @Test fun anExistingPendingSidecarIsUpdatedNotDuplicated() {
        val sidecar = row("Download", "s", "take.timing.json", "application/json", 90)
        val existing = """{"schema":"opencinecam.av-timing.v1","videoUri":"content://media/external_primary/v","bundleId":"$id"}"""
        val (published, rows, inserted) = recover(
            listOf(row("DCIM", "v", "take.mp4", "video/mp4", 10), sidecar), mapOf("s" to existing))
        assertEquals(1, published)
        assertTrue(inserted.isEmpty())
        val json = parse(rows.written.getValue("s"))
        assertEquals("opencinecam.av-timing.v1", json.getString("schema"))
        assertEquals("incomplete", json.getString("takeStatus"))
        assertEquals(listOf("sidecar:s", "publish:s", "publish:v"), rows.events)
    }

    @Test fun zeroByteMediaAndItsSidecarAreDeletedAndNothingIsPublished() {
        val (published, rows, inserted) = recover(listOf(
            row("DCIM", "v", "take.mp4", "video/mp4", 0), row("Download", "s", "take.timing.json", "application/json", 12)))
        assertEquals(0, published)
        assertTrue(inserted.isEmpty())
        assertEquals(listOf("delete:v", "delete:s"), rows.events)
    }

    @Test fun emptyAudioIsDeletedWhileVideoWithBytesAndItsAudioSidecarRulesStaySeparate() {
        val (published, rows, _) = recover(listOf(
            row("DCIM", "v", "take.mp4", "video/mp4", 100),
            row("Music", "a", "take.wav", "audio/x-wav", 0),
            row("Download", "as", "take.audio.json", "application/json", 30),
            row("Download", "vs", "take.timing.json", "application/json", 30),
        ))
        assertEquals(1, published)
        assertTrue("delete:a" in rows.events && "delete:as" in rows.events)
        assertFalse("delete:vs" in rows.events)
        assertTrue("publish:v" in rows.events && "publish:vs" in rows.events)
        assertFalse("publish:a" in rows.events)
    }

    @Test fun partialAudioWithBytesGetsItsOwnIncompleteAudioSidecar() {
        val (published, rows, inserted) = recover(listOf(row("Music", "a", "take.flac", "audio/flac", 777)))
        assertEquals(1, published)
        assertEquals(listOf("take.audio.incomplete.json"), inserted)
        val json = parse(rows.written.getValue("new:take.audio.incomplete.json"))
        assertEquals("opencinecam-audio-sidecar-v1", json.getString("schema"))
        assertEquals("content://media/external_primary/a", json.getString("audioUri"))
        assertEquals("incomplete", json.getString("takeStatus"))
    }

    @Test fun motionLogTravelsWithItsVideoAndAnEmptyOneIsDropped() {
        val (_, kept, _) = recover(listOf(
            row("DCIM", "v", "take.mp4", "video/mp4", 5), row("Download", "g", "take.gcsv", "text/plain", 50)))
        assertTrue("publish:g" in kept.events)
        val (_, dropped, _) = recover(listOf(
            row("DCIM", "v", "take.mp4", "video/mp4", 5), row("Download", "g", "take.gcsv", "text/plain", 0)))
        assertTrue("delete:g" in dropped.events)
        assertFalse("publish:g" in dropped.events)
    }

    @Test fun unreadableSizeNeverDeletesMediaAndGarbageSidecarsAreReplaced() {
        val (published, rows, _) = recover(
            listOf(row("DCIM", "v", "take.mp4", "video/mp4", -1), row("Download", "s", "take.timing.json", "application/json", 5)),
            mapOf("s" to "not json{"))
        assertEquals(1, published)
        assertFalse("delete:v" in rows.events)
        assertEquals("incomplete", parse(rows.written.getValue("s")).getString("takeStatus"))
    }

    @Test fun clipSidecarIsNotTakenForTheTimingSidecarAndIsMarkedAndPublishedWithTheVideo() {
        val clipText = """{"schemaVersion":"1.0.0","recordingId":"$id","events":[]}"""
        val (published, rows, inserted) = recover(listOf(
            row("DCIM", "v", "take.mp4", "video/mp4", 100),
            row("Download", "c", "take.clip.json", "application/json", 60),
        ), mapOf("c" to clipText))
        assertEquals(1, published)
        // The clip is not the timing sidecar, so a separate incomplete sidecar is still created.
        assertEquals(listOf("take.incomplete.json"), inserted)
        val json = parse(rows.written.getValue("c"))
        assertEquals("incomplete", json.getString("takeStatus"))
        assertEquals("process-death", json.getJSONArray("failureReasons").getString(0))
        assertEquals(id, json.getString("recordingId"))
        assertTrue("publish:c" in rows.events && "publish:v" in rows.events)
        assertTrue(rows.events.indexOf("publish:c") < rows.events.indexOf("publish:v"))
    }

    @Test fun clipSidecarOfAnEmptyVideoIsDeleted() {
        val (published, rows, _) = recover(listOf(
            row("DCIM", "v", "take.mp4", "video/mp4", 0),
            row("Download", "c", "take.clip.json", "application/json", 60),
        ))
        assertEquals(0, published)
        assertTrue("delete:c" in rows.events)
        assertFalse("publish:c" in rows.events)
    }

    @Test fun clipSidecarAlreadyMarkedIncompleteIsLeftAsWritten() {
        val marked = IncompleteTake.mark("""{"recordingId":"$id"}""", listOf("encoder-failed"), null)
        val (_, rows, _) = recover(listOf(
            row("DCIM", "v", "take.mp4", "video/mp4", 100),
            row("Download", "c", "take.clip.json", "application/json", 60),
        ), mapOf("c" to marked))
        assertFalse("c" in rows.written)
        assertTrue("publish:c" in rows.events)
    }

    @Test fun reportKeepsItsPreviousShapeWhenNothingWasKept() {
        assertEquals(RecordingRecoveryReport(discardedGroups = 1), RecordingRecoveryReport(1, 0, 0))
        assertEquals(1, RecordingRecoveryReport(incompleteGroups = 1).incompleteGroups)
    }
}
