/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentUris
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.RecordingCaptureRecovery
import com.librestatic.opencinecam.storage.StillImageSaver
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Explicit host-only real AVC/WAV writer death; ordinary discovery never sleeps or kills. */
class RecordingRecoveryProcessDeathTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val stateFile get() = File(context.noBackupFilesDir, "e9-recording-death-state.txt")
    private val readyFile get() = File(context.noBackupFilesDir, "e9-recording-death-ready.txt")
    private val database get() = File(context.noBackupFilesDir, "recording-recovery.sqlite")
    private fun read(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
    private fun hash(uri: Uri) = MessageDigest.getInstance("SHA-256").digest(read(uri)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun receiptExists(id: String) = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT id FROM captures WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }
    }
    private fun rows(id: String): JSONArray = JSONArray().also { result ->
        for (root in listOf("DCIM", "Music", "Download")) {
            val collection = RecordingCaptureRecovery.collection(root)
            @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
            requireNotNull(resolver.query(all, arrayOf("_id", "mime_type", "is_pending", "_display_name"),
                "relative_path = ? AND owner_package_name = ?", arrayOf(RecordingCaptureRecovery.path(id, root), context.packageName), null)).use { cursor ->
                while (cursor.moveToNext()) result.put(JSONObject().put("uri", ContentUris.withAppendedId(collection, cursor.getLong(0)).toString())
                    .put("mime", cursor.getString(1)).put("pending", cursor.getInt(2)).put("name", cursor.getString(3)).put("root", root))
            }
        }
    }
    private fun entries(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)
    private fun clean(uris: Collection<Uri>) {
        for (uri in uris.distinct()) {
            val collection = uri.buildUpon().path(uri.pathSegments.dropLast(1).joinToString("/", prefix = "/")).build()
            @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
            val exists = requireNotNull(resolver.query(all, arrayOf("_id"), "_id = ? AND owner_package_name = ?",
                arrayOf(ContentUris.parseId(uri).toString(), context.packageName), null)).use { it.moveToFirst() }
            if (exists) assertEquals(1, resolver.delete(uri, null, null))
        }
    }

    @Test fun killPhase() {
        org.junit.Assume.assumeTrue("Requires explicit host-controlled process-death invocation",
            InstrumentationRegistry.getArguments().getString("class") == "${javaClass.name}#killPhase")
        val boundary = requireNotNull(InstrumentationRegistry.getArguments().getString("e9Boundary"))
        require(boundary in setOf("WRITING", "PREPARED", "PARTIAL", "BEFORE_COMMIT", "COMMITTED"))
        readyFile.delete(); stateFile.delete()
        RecordingCaptureRecovery(context).recover()
        val jpeg = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(android.graphics.Color.BLUE); check(bitmap.compress(Bitmap.CompressFormat.JPEG, 91, output)) }
            finally { bitmap.recycle() }
            output.toByteArray()
        }
        val stable = StillImageSaver(context).saveJpeg(jpeg)
        val untrackedId = UUID.randomUUID().toString()
        val untracked = requireNotNull(resolver.insert(RecordingCaptureRecovery.collection("Download"), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "untracked.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RecordingCaptureRecovery.path(untrackedId, "Download"))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(untracked)).use { it.write("{\"untracked\":true}".toByteArray()) }
        PreparedCaptureBundleDeviceTest().pauseForProcessDeath(boundary) { id ->
            val members = rows(id)
            assertEquals(if (boundary == "WRITING") 2 else 4, members.length())
            assertEquals(boundary != "COMMITTED", receiptExists(id))
            if (boundary == "COMMITTED") entries(members).forEach {
                assertEquals(0, it.getInt("pending")); it.put("sha256", hash(Uri.parse(it.getString("uri"))))
            }
            val state = JSONObject().put("boundary", boundary).put("id", id).put("members", members)
                .put("stable", stable.toString()).put("stableHash", hash(stable))
                .put("untracked", untracked.toString()).put("untrackedHash", hash(untracked))
            stateFile.outputStream().use { it.write(state.toString().toByteArray()); it.fd.sync() }
            readyFile.outputStream().use { it.write("${android.os.Process.myPid()} $boundary".toByteArray()); it.fd.sync() }
            Thread.sleep(120_000)
            error("Host did not kill checkpoint process")
        }
        fail("Kill checkpoint was not reached")
    }

    @Test fun verifyPhase() {
        org.junit.Assume.assumeTrue("Requires explicit host-controlled process-death invocation",
            InstrumentationRegistry.getArguments().getString("class") == "${javaClass.name}#verifyPhase")
        val state = JSONObject(stateFile.readText()); val id = state.getString("id"); val boundary = state.getString("boundary")
        assertEquals(boundary != "COMMITTED", receiptExists(id))
        val oldPid = readyFile.readText().substringBefore(' ').toInt()
        assertNotEquals(oldPid, android.os.Process.myPid())
        val connected = CountDownLatch(1)
        val binder = java.util.concurrent.atomic.AtomicReference<com.librestatic.opencinecam.service.CaptureService.LocalBinder>()
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                binder.set(service as com.librestatic.opencinecam.service.CaptureService.LocalBinder); connected.countDown()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) { binder.set(null) }
        }
        assertTrue(context.bindService(android.content.Intent(context, com.librestatic.opencinecam.service.CaptureService::class.java), connection, android.content.Context.BIND_AUTO_CREATE))
        val report = try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val until = android.os.SystemClock.uptimeMillis() + 10_000
            while (binder.get()?.cameraStates?.value?.recordingRecovery == null && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(20)
            requireNotNull(binder.get()?.cameraStates?.value?.recordingRecovery) { "Service startup did not expose take recovery" }
        } finally { context.unbindService(connection) }
        assertEquals(if (boundary == "COMMITTED") 0 else 1, report.discardedGroups)
        assertEquals(0, report.unresolvedGroups); assertFalse(receiptExists(id))
        val actual = rows(id)
        val stable = Uri.parse(state.getString("stable")); val untracked = Uri.parse(state.getString("untracked"))
        val cleanup = listOf(stable, untracked) + entries(actual).map { Uri.parse(it.getString("uri")) }
        try {
            assertEquals(if (boundary == "COMMITTED") 4 else 0, actual.length())
            assertEquals(state.getString("stableHash"), hash(stable)); assertEquals(state.getString("untrackedHash"), hash(untracked))
            if (boundary == "COMMITTED") {
                val expected = entries(state.getJSONArray("members")).associateBy { it.getString("uri") }
                assertEquals(expected.keys, entries(actual).map { it.getString("uri") }.toSet())
                entries(actual).forEach { row ->
                    val original = requireNotNull(expected[row.getString("uri")])
                    assertEquals(original.getString("sha256"), hash(Uri.parse(row.getString("uri"))))
                    assertEquals(original.getString("mime"), row.getString("mime"))
                    assertEquals(original.getString("name"), row.getString("name")); assertEquals(0, row.getInt("pending"))
                }
                val video = Uri.parse(entries(actual).single { it.getString("root") == "DCIM" }.getString("uri"))
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, video)
                    val frame = requireNotNull(retriever.getFrameAtTime(0))
                    try { assertEquals(128, frame.width); assertEquals(96, frame.height) } finally { frame.recycle() }
                } finally { retriever.release() }
                val audio = Uri.parse(entries(actual).single { it.getString("root") == "Music" }.getString("uri"))
                val wav = read(audio); assertTrue(wav.size > 44)
                assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII)); assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
            }
            assertEquals(0, RecordingCaptureRecovery(context).recover().discardedGroups)
            android.util.Log.i("RecordingRecoveryProbe", "boundary=$boundary newPid=${android.os.Process.myPid()} discarded=${report.discardedGroups} groupRows=${actual.length()} preservedExact=2 committedMembersExact=${if (boundary == "COMMITTED") 4 else 0} idempotent=true")
        } finally { clean(cleanup); stateFile.delete(); readyFile.delete() }
    }
}
