/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentUris
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.StillCaptureRecovery
import com.librestatic.opencinecam.storage.StillImageSaver
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite and MediaStore. Process-death phases run as separate instrumentations, without an
 * intervening install/clear-data; the host kills only this application's PID after the ready file. */
class StillRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val database get() = File(context.noBackupFilesDir, "still-recovery.sqlite")
    private val stateFile get() = File(context.noBackupFilesDir, "e9-death-state.txt")
    private val readyFile get() = File(context.noBackupFilesDir, "e9-death-ready.txt")
    private val jpeg get() = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(android.graphics.Color.GREEN); check(bitmap.compress(Bitmap.CompressFormat.JPEG, 91, output)) }
        finally { bitmap.recycle() }
        output.toByteArray()
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun read(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
    private fun insert(id: String, metadata: Boolean = false, pending: Boolean = true, name: String = "OCC_$id.${if (metadata) "json" else "jpg"}"): Uri {
        val uri = requireNotNull(resolver.insert(StillCaptureRecovery.collection(metadata), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (metadata) "application/json" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, StillCaptureRecovery.relativePath(id, metadata))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(if (metadata) "{\"complete\":true}".toByteArray() else jpeg) }
        if (!pending) publish(uri)
        return uri
    }
    private fun publish(uri: Uri) { assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)) }
    private fun seed(id: String, committed: Boolean = false) {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO captures(id, committed) VALUES(?, ?)", arrayOf<Any>(id, if (committed) 1 else 0))
        }
    }
    private fun exists(uri: Uri): Boolean {
        val collection = uri.buildUpon().path(uri.pathSegments.dropLast(1).joinToString("/", prefix = "/")).build()
        @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
        return requireNotNull(resolver.query(all, arrayOf("_id"), "_id = ?", arrayOf(ContentUris.parseId(uri).toString()), null)).use { it.moveToFirst() }
    }
    private fun receiptExists(id: String): Boolean = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT id FROM captures WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }
    }
    private fun clean(uris: Collection<Uri>) { uris.forEach { if (exists(it)) resolver.delete(it, null, null) } }

    @Test fun restartDiscardsPendingAndPartiallyPublishedButPreservesCommittedAndUntrackedBytes() {
        StillImageSaver(context).recoverInterrupted()
        val interrupted = UUID.randomUUID().toString(); val committed = UUID.randomUUID().toString(); val foreign = UUID.randomUUID().toString()
        val owned = mutableListOf<Uri>()
        try {
            seed(interrupted); seed(committed, true)
            owned += insert(interrupted, pending = false)
            owned += insert(interrupted, metadata = true)
            owned += insert(committed, pending = false)
            owned += insert(foreign) // Own package, similar prefix, no receipt: must not be scanned.
            val preserved = owned.drop(2).associateWith { hash(read(it)) }
            val report = StillImageSaver(context).recoverInterrupted()
            assertEquals(1, report.discardedGroups); assertEquals(0, report.unresolvedGroups)
            owned.take(2).forEach { assertFalse(exists(it)) }
            preserved.forEach { (uri, digest) -> assertEquals(digest, hash(read(uri))) }
            assertFalse(receiptExists(interrupted)); assertFalse(receiptExists(committed))
            assertEquals(0, StillImageSaver(context).recoverInterrupted().discardedGroups)
        } finally { clean(owned) }
    }

    @Test fun namespaceOwnsProviderRenamedRowsWithoutRelyingOnPlannedDisplayName() {
        StillImageSaver(context).recoverInterrupted()
        val id = UUID.randomUUID().toString(); seed(id)
        val row = insert(id, name = "provider-renamed (1).jpg")
        try {
            assertEquals(1, StillImageSaver(context).recoverInterrupted().discardedGroups)
            assertFalse(exists(row))
        } finally { clean(listOf(row)) }
    }

    @Test fun recoveryWaitsForOldServiceWriterInsteadOfDeletingItsActiveGroup() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val recovering = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2); val owned = mutableListOf<Uri>()
        try {
            val writer = workers.submit<Uri> { StillCaptureRecovery(context).publish { id, _, _ ->
                val row = insert(id); owned += row; entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)); publish(row); row
            } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val recovery = workers.submit<com.librestatic.opencinecam.storage.StillRecoveryReport> {
                recovering.countDown(); StillImageSaver(context).recoverInterrupted()
            }
            assertTrue(recovering.await(5, TimeUnit.SECONDS))
            try { recovery.get(150, TimeUnit.MILLISECONDS); fail("Recovery passed an active writer") }
            catch (_: java.util.concurrent.TimeoutException) { /* The old writer owns the monitor. */ }
            assertTrue(exists(owned.single()))
            release.countDown()
            val row = writer.get(10, TimeUnit.SECONDS)
            assertEquals(0, recovery.get(10, TimeUnit.SECONDS).discardedGroups)
            assertTrue(exists(row)); assertNotNull(BitmapFactory.decodeByteArray(read(row), 0, read(row).size)?.also { it.recycle() })
        } finally { release.countDown(); workers.shutdown(); check(workers.awaitTermination(15, TimeUnit.SECONDS)); clean(owned) }
    }

    @Test fun publisherFailureDiscardsEveryOwnedRowAndNextLegacySaveRemainsDecodable() {
        val rows = mutableListOf<Uri>(); val expected = IllegalStateException("injected writer failure")
        try {
            try { StillCaptureRecovery(context).publish<Unit> { id, _, _ ->
                rows += insert(id, pending = false); rows += insert(id, metadata = true)
                throw expected
            }; fail("Writer failure was swallowed") } catch (failure: IllegalStateException) { assertSame(expected, failure) }
            rows.forEach { assertFalse(exists(it)) }
            val bytes = jpeg; val saved = StillImageSaver(context).saveJpeg(bytes); rows += saved
            assertEquals(hash(bytes), hash(read(saved)))
            assertEquals(0, StillImageSaver(context).recoverInterrupted().discardedGroups)
            assertEquals(hash(bytes), hash(read(saved)))
        } finally { clean(rows) }
    }

    @Test fun unsupportedJournalSchemaIsPreservedAndDoesNotDeleteOrAllocateMedia() {
        StillImageSaver(context).recoverInterrupted()
        val id = UUID.randomUUID().toString(); seed(id); val row = insert(id)
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }
        val original = database.readBytes()
        try {
            try { StillImageSaver(context).saveJpeg(jpeg); fail("Unknown schema accepted") }
            catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("version")) }
            assertTrue(exists(row)); assertTrue(receiptExists(id)); assertArrayEquals(original, database.readBytes())
        } finally {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 1 }
            StillImageSaver(context).recoverInterrupted(); clean(listOf(row))
        }
    }

    @Test fun corruptStateIsNeverCoercedIntoPermissionToDelete() {
        StillImageSaver(context).recoverInterrupted()
        val ids = listOf(4294967296L, 4294967297L, 0.5, "invalid").map { value ->
            val id = UUID.randomUUID().toString(); seed(id)
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("PRAGMA ignore_check_constraints=ON")
                db.execSQL("UPDATE captures SET committed = ? WHERE id = ?", arrayOf<Any>(value, id))
            }
            id to insert(id)
        }
        try {
            val report = StillImageSaver(context).recoverInterrupted()
            assertEquals(0, report.discardedGroups); assertEquals(4, report.unresolvedGroups)
            ids.forEach { (id, uri) -> assertTrue(receiptExists(id)); assertTrue(exists(uri)) }
        } finally {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                ids.forEach { (id, _) -> db.execSQL("UPDATE captures SET committed = 0 WHERE id = ?", arrayOf(id)) }
            }
            StillImageSaver(context).recoverInterrupted(); clean(ids.map { it.second })
        }
    }

    @Test fun overfullNamespaceRetainsReceiptThenRetryDiscardsWithoutTouchingOtherGroups() {
        StillImageSaver(context).recoverInterrupted()
        val id = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString()
        seed(id); seed(other)
        val rows = (1..12).map { insert(id, name = "member_$it.jpg") }
        val otherRow = insert(other)
        try {
            val report = StillImageSaver(context).recoverInterrupted()
            assertEquals(1, report.unresolvedGroups); assertEquals(1, report.discardedGroups)
            assertTrue(receiptExists(id)); rows.forEach { assertTrue(exists(it)) }; assertFalse(exists(otherRow))
            resolver.delete(rows.last(), null, null)
            val retry = StillImageSaver(context).recoverInterrupted()
            assertEquals(0, retry.unresolvedGroups); assertEquals(1, retry.discardedGroups)
            rows.forEach { assertFalse(exists(it)) }; assertFalse(receiptExists(id))
        } finally { clean(rows + otherRow); StillImageSaver(context).recoverInterrupted() }
    }

    @Test fun freshPrivateJournalUsesVersionedSchemaAndCommitsFirstSave() {
        val directory = File(context.noBackupFilesDir, "e9-fresh-journal").apply { check(mkdirs() || isDirectory) }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val rows = mutableListOf<Uri>()
        try {
            val saver = StillImageSaver(isolated)
            assertEquals(0, saver.recoverInterrupted().discardedGroups)
            val row = saver.saveJpeg(jpeg); rows += row
            assertEquals(hash(jpeg), hash(read(row)))
            assertEquals(0, saver.recoverInterrupted().discardedGroups)
        } finally { clean(rows); check(directory.deleteRecursively()) }
    }

    @Test fun killPhase() {
        org.junit.Assume.assumeTrue("Requires explicit host-controlled process-death invocation",
            InstrumentationRegistry.getArguments().getString("class") == "${javaClass.name}#killPhase")
        val boundary = requireNotNull(InstrumentationRegistry.getArguments().getString("e9Boundary"))
        require(boundary in setOf("RESERVED", "INSERTED", "WRITTEN", "PARTIAL", "BEFORE_COMMIT", "COMMITTED"))
        readyFile.delete(); stateFile.delete()
        StillImageSaver(context).recoverInterrupted()
        val stable = StillImageSaver(context).saveJpeg(jpeg)
        val untrackedId = UUID.randomUUID().toString(); val untracked = insert(untrackedId)
        fun pause(stage: String, id: String) {
            if (stage != boundary) return
            val members = mutableListOf<String>()
            if (boundary == "COMMITTED") for (metadata in listOf(false, true)) {
                val collection = StillCaptureRecovery.collection(metadata)
                @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
                requireNotNull(resolver.query(all, arrayOf("_id", "mime_type", "is_pending"), "relative_path = ?", arrayOf(StillCaptureRecovery.relativePath(id, metadata)), null)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        members += listOf(uri.toString(), hash(read(uri)), cursor.getString(1), cursor.getInt(2).toString()).joinToString("|")
                    }
                }
            }
            stateFile.outputStream().use { stream ->
                stream.write((listOf(boundary, id, stable.toString(), hash(read(stable)), untracked.toString(), hash(read(untracked))) + members).joinToString("\n").toByteArray())
                stream.fd.sync()
            }
            readyFile.outputStream().use { it.write("${android.os.Process.myPid()} $boundary".toByteArray()); it.fd.sync() }
            // The host records this PID, kills only it, and runs verifyPhase without reinstall.
            Thread.sleep(120_000)
            error("Host did not kill checkpoint process")
        }
        StillCaptureRecovery(context, ::pause).publish { id, _, _ ->
            val row = requireNotNull(resolver.insert(StillCaptureRecovery.collection(false), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "OCC_$id.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, StillCaptureRecovery.relativePath(id, false))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            pause("INSERTED", id)
            requireNotNull(resolver.openOutputStream(row)).use { it.write(jpeg) }
            pause("WRITTEN", id)
            val metadata = insert(id, metadata = true)
            publish(row); pause("PARTIAL", id); publish(metadata)
        }
        fail("Kill checkpoint was not reached")
    }

    @Test fun verifyPhase() {
        org.junit.Assume.assumeTrue("Requires explicit host-controlled process-death invocation",
            InstrumentationRegistry.getArguments().getString("class") == "${javaClass.name}#verifyPhase")
        val parts = stateFile.readLines(); assertTrue(parts.size in 6..8)
        val boundary = parts[0]; val id = parts[1]; val preserved = mapOf(Uri.parse(parts[2]) to parts[3], Uri.parse(parts[4]) to parts[5])
        assertTrue(receiptExists(id)) // Proves kill did not run catch/finally compensation.
        val oldPid = readyFile.readText().substringBefore(' ').toInt()
        assertNotEquals(oldPid, android.os.Process.myPid())
        val connected = CountDownLatch(1)
        val binder = java.util.concurrent.atomic.AtomicReference<com.librestatic.opencinecam.service.CaptureService.LocalBinder?>()
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
            while (binder.get()?.cameraStates?.value?.stillRecovery == null && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(20)
            requireNotNull(binder.get()?.cameraStates?.value?.stillRecovery) { "Service startup did not expose recovery result" }
        } finally { context.unbindService(connection) }
        assertEquals(if (boundary == "COMMITTED") 0 else 1, report.discardedGroups)
        assertEquals(0, report.unresolvedGroups)
        assertFalse(receiptExists(id))
        var groupRows = 0
        val cleanup = preserved.keys.toMutableList()
        try {
            for (metadata in listOf(false, true)) {
                val collection = StillCaptureRecovery.collection(metadata)
                @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
                requireNotNull(resolver.query(all, arrayOf("_id"), "relative_path = ?", arrayOf(StillCaptureRecovery.relativePath(id, metadata)), null)).use { cursor ->
                    groupRows += cursor.count
                    while (cursor.moveToNext()) cleanup += ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
            }
            assertEquals(if (boundary == "COMMITTED") 2 else 0, groupRows)
            preserved.forEach { (uri, expected) -> assertEquals(expected, hash(read(uri))) }
            if (boundary == "COMMITTED") {
                assertEquals(8, parts.size)
                val members = parts.drop(6).map { it.split('|').also { fields -> assertEquals(4, fields.size) } }
                assertEquals(setOf("image/jpeg", "application/json"), members.map { it[2] }.toSet())
                members.forEach { fields ->
                    val uri = Uri.parse(fields[0]); assertEquals(fields[1], hash(read(uri)))
                    assertEquals("0", fields[3])
                    val metadata = fields[2] == "application/json"
                    assertTrue(uri.toString().startsWith(StillCaptureRecovery.collection(metadata).toString() + "/"))
                    requireNotNull(resolver.query(uri, arrayOf("mime_type", "is_pending", "relative_path"), null, null, null)).use { cursor ->
                        assertTrue(cursor.moveToFirst()); assertEquals(fields[2], cursor.getString(0)); assertEquals(0, cursor.getInt(1))
                        assertEquals(StillCaptureRecovery.relativePath(id, metadata), cursor.getString(2))
                    }
                    if (!metadata) {
                        val bytes = read(uri)
                        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        try { assertEquals(16, bitmap.width); assertEquals(12, bitmap.height) } finally { bitmap.recycle() }
                    }
                }
            }
            assertEquals(0, StillImageSaver(context).recoverInterrupted().discardedGroups)
            android.util.Log.i("StillRecoveryProbe", "boundary=$boundary newPid=${android.os.Process.myPid()} discarded=${report.discardedGroups} groupRows=$groupRows preservedExact=2 committedMembersExact=${if (boundary == "COMMITTED") 2 else 0} idempotent=true")
        } finally { clean(cleanup); stateFile.delete(); readyFile.delete() }
    }
}
