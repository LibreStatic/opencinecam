/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual MediaStore rows, sidecar bytes and read-only recovery receipts; never a fake gallery. */
class LocalMediaCatalogDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val photos = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val audio = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Test fun savedNestedPhotoExposesItsRealSlateAndRelationshipWithoutChangingBytes() = fixture { f ->
        val slate = ProductionSlateSettings(project = f.token, scene = "Scene Ñ", takeNumber = 17, goodTake = true)
        val original = StillImageSaver(context).saveJpeg(f.jpeg, slate)
        f.uris += original
        val path = resolver.query(original, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        val metadata = resolver.query(downloads, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
            arrayOf(path.replaceFirst("DCIM/", "Download/"), context.packageName), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); ContentUris.withAppendedId(downloads,it.getLong(0))
        }
        f.uris += metadata
        val before = f.uris.associateWith(::bytes)
        val take = f.all(query = f.token).single()
        assertEquals(original.toString(), take.primary.uri)
        assertEquals(slate, take.slate)
        assertEquals(LocalMediaKind.PHOTO, take.kind)
        assertEquals(LocalMediaRelationStatus.DECLARED, take.relationStatus)
        assertEquals(listOf(original.toString()), take.originals.map { it.uri })
        assertEquals(listOf(metadata.toString()), take.metadata.map { it.uri })
        val thumbnail = f.repo.thumbnail(take.primary)
        assertNotNull(thumbnail);thumbnail?.recycle()
        assertEquals(1, f.repo.recent(1).size)
        for ((uri, expected) in before) assertArrayEquals(expected, bytes(uri))
    }

    @Test fun paginationFindsBeyondSixtyAndSearchDoesNotOnlyFilterTheFirstPage() = fixture { f ->
        val expected = (0..64).map { index ->
            f.insert(photos, "DCIM/OpenCineCam/", "${f.token}_${index.toString().padStart(3,'0')}.jpg", "image/jpeg", f.jpeg)
        }.map(Uri::toString).toSet()
        val newest = f.all(query = f.token, limit = 7)
        assertEquals(65, newest.size)
        assertEquals(expected, newest.map { it.primary.uri }.toSet())
        assertEquals(65, newest.map { it.id }.toSet().size)
        assertTrue(newest.all { it.relationStatus == LocalMediaRelationStatus.LEGACY })
        val oldest = f.all(GallerySettings(newestFirst = false), f.token, limit = 9)
        assertEquals(newest.sortedWith(compareBy<LocalMediaTake> { it.primary.modifiedSeconds }
            .thenBy { ContentUris.parseId(Uri.parse(it.primary.uri)) }).map { it.primary.uri }, oldest.map { it.primary.uri })
        assertEquals(newest.sortedWith(compareByDescending<LocalMediaTake> { it.primary.modifiedSeconds }
            .thenBy { ContentUris.parseId(Uri.parse(it.primary.uri)) }), newest)
        assertEquals("${f.token}_000.jpg", f.all(query = "${f.token}_000").single().primary.name)
        val first = f.repo.page(GallerySettings(), f.token, limit = 1)
        assertNotNull(first.next)
        try { f.repo.page(GallerySettings(newestFirst = false), f.token, first.next, 1); fail("A cursor from another ordering must reject") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun pendingAndLookalikePathsNeverBecomeGalleryItems() = fixture { f ->
        val visible = f.insert(photos,"DCIM/OpenCineCam/","${f.token}_visible.jpg","image/jpeg",f.jpeg)
        f.insert(photos,"DCIM/OpenCineCam/","${f.token}_pending.jpg","image/jpeg",f.jpeg,pending = true)
        f.insert(photos,"DCIM/OpenCineCamera/","${f.token}_other.jpg","image/jpeg",f.jpeg)
        f.insert(photos,"DCIM/OpenCineCamElse/","${f.token}_prefix.jpg","image/jpeg",f.jpeg)
        assertEquals(listOf(visible.toString()), f.all(query = f.token).map { it.primary.uri })
        assertTrue(f.all(GallerySettings(kind = GalleryMediaKind.AUDIO), f.token).isEmpty())
        assertTrue(f.all(GallerySettings(goodTakesOnly = true), f.token).isEmpty())
    }

    @Test fun recordingGroupUsesVideoAnchorAndReportsConflictingSlate() = fixture { f ->
        val id = UUID.randomUUID().toString()
        val folder = "OpenCineCam/OCC_TAKE_$id/"
        val video = f.insert(videos,"DCIM/$folder","${f.token}.mp4","video/mp4", byteArrayOf(1,2,3))
        val sound = f.insert(audio,"Music/$folder","${f.token}.wav","audio/wav",byteArrayOf(4,5,6))
        val slate = ProductionSlateSettings(project = f.token, goodTake = true)
        val vm = f.insert(downloads,"Download/$folder","${f.token}.timing.json","application/json",buildJsonObject {
            put("schema","opencinecam.recording.v1");put("bundleId",id);put("videoUri",video.toString());put("productionSlate",productionSlateJson(slate))
        }.toString().toByteArray())
        val am = f.insert(downloads,"Download/$folder","${f.token}.wav.json","application/json",buildJsonObject {
            put("schema","opencinecam-audio-sidecar-v1");put("audioUri",sound.toString());put("file","${f.token}.wav");put("productionSlate",productionSlateJson(slate))
        }.toString().toByteArray())
        val take = f.all(query = f.token, limit = 1).single()
        assertEquals(LocalMediaKind.VIDEO,take.kind);assertEquals(video.toString(),take.primary.uri)
        assertEquals(setOf(video.toString(),sound.toString()),take.originals.map { it.uri }.toSet())
        assertEquals(setOf(vm.toString(),am.toString()),take.metadata.map { it.uri }.toSet())
        assertEquals(slate,take.slate);assertEquals(LocalMediaRelationStatus.DECLARED,take.relationStatus)
        assertEquals(1,f.all(GallerySettings(goodTakesOnly = true),f.token).size)
        resolver.openOutputStream(am,"wt")!!.use { it.write(buildJsonObject {
            put("schema","opencinecam-audio-sidecar-v1");put("audioUri",sound.toString());put("file","${f.token}.wav")
            put("productionSlate",productionSlateJson(slate.copy(scene = "conflict")))
        }.toString().toByteArray()) }
        val conflict = f.all(query = f.token).single()
        assertNull(conflict.slate);assertEquals(LocalMediaRelationStatus.INVALID_METADATA,conflict.relationStatus)
        resolver.openOutputStream(am,"wt")!!.use { it.write(buildJsonObject {
            put("schema","opencinecam-audio-sidecar-v1");put("audioUri",sound.toString());put("productionSlate",productionSlateJson(slate))
        }.toString().toByteArray()) }
        assertEquals(1,resolver.delete(sound,null,null));assertTrue(f.uris.remove(sound))
        assertEquals(LocalMediaRelationStatus.INCOMPLETE,f.all(query = f.token).single().relationStatus)
    }

    @Test fun corruptMissingAndCrossNamespaceReferencesStayExplicit() = fixture { f ->
        val other = f.insert(photos,"DCIM/OpenCineCam/","${f.token}_other.jpg","image/jpeg",f.jpeg)
        val id = UUID.randomUUID().toString();val folder = "OpenCineCam/OCC_$id/"
        val original = f.insert(photos,"DCIM/$folder","${f.token}_main.jpg","image/jpeg",f.jpeg)
        assertEquals(LocalMediaRelationStatus.MISSING_METADATA,f.all(query = "${f.token}_main").single().relationStatus)
        val metadata = f.insert(downloads,"Download/$folder","${f.token}.json","application/json","{bad".toByteArray())
        assertEquals(LocalMediaRelationStatus.INVALID_METADATA,f.all(query = "${f.token}_main").single().relationStatus)
        resolver.openOutputStream(metadata,"wt")!!.use { it.write(buildJsonObject {
            put("schemaVersion",1);put("bundleId",id);putJsonArray("images") { add(buildJsonObject {
                put("uri",other.toString());put("displayName","${f.token}_other.jpg");put("bytes",f.jpeg.size);put("sha256",sha(f.jpeg))
            }) }
        }.toString().toByteArray()) }
        val take = f.all(query = "${f.token}_main").single()
        assertNotEquals(LocalMediaRelationStatus.DECLARED,take.relationStatus)
        assertEquals(listOf(original.toString()),take.originals.map { it.uri })
        assertArrayEquals(f.jpeg,bytes(other))
    }

    @Test fun visibleUncommittedWriterIsExcludedWithoutBlockingOrMutatingItsJournal() = fixture { f ->
        val entered = CountDownLatch(1);val release = CountDownLatch(1)
        val done = CompletableFuture<Uri>()
        Thread {
            try {
                val original = StillCaptureRecovery(context, checkpoint = { phase,_ -> if (phase == "BEFORE_COMMIT") {
                    entered.countDown();check(release.await(20,TimeUnit.SECONDS))
                } }).publish { _,path,_ -> f.insert(photos,path,"${f.token}.jpg","image/jpeg",f.jpeg) }
                done.complete(original)
            } catch (failure:Throwable) { done.completeExceptionally(failure) }
        }.start()
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            val journal = File(context.noBackupFilesDir,"still-recovery.sqlite")
            val before = journal.readBytes()
            val read = CompletableFuture.supplyAsync { f.all(query = f.token) }
            assertTrue(read.get(5,TimeUnit.SECONDS).isEmpty())
            assertArrayEquals("Gallery must not prune or compensate receipts",before,journal.readBytes())
            assertEquals(1,f.uris.size);assertArrayEquals(f.jpeg,bytes(f.uris.single()))
        } finally { release.countDown();done.get(10,TimeUnit.SECONDS) }
        assertEquals(done.get(10,TimeUnit.SECONDS).toString(),f.all(query = f.token).single().primary.uri)
    }

    @Test fun corruptAndUnsupportedJournalsFailReadOnlyInsteadOfReturningAnEmptySuccess() = fixture { f ->
        f.insert(photos,"DCIM/OpenCineCam/","${f.token}.jpg","image/jpeg",f.jpeg)
        val dir = File(context.cacheDir,"gallery-journal-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object:ContextWrapper(context) {
            override fun getApplicationContext():Context = this
            override fun getNoBackupFilesDir():File = dir
        }
        val file = File(dir,"still-recovery.sqlite")
        try {
            file.writeText("deliberately corrupt receipt bytes")
            fun rejectsPreservingBytes() {
                val before = file.readBytes()
                try { LocalMediaRepository(isolated).page(GallerySettings(),f.token);fail("Journal failure must be observable") }
                catch (_:IllegalStateException) { }
                assertArrayEquals(before,file.readBytes())
            }
            rejectsPreservingBytes();assertTrue(file.delete())
            SQLiteDatabase.openOrCreateDatabase(file,null).use { it.execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY, committed)");it.version=2 }
            rejectsPreservingBytes()
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use {
                it.version=1;it.execSQL("INSERT INTO captures VALUES(?,?)",arrayOf(UUID.randomUUID().toString(),"invalid"))
            }
            rejectsPreservingBytes()
        } finally { dir.deleteRecursively() }
    }

    private inner class Fixture {
        val token = "CAT_${UUID.randomUUID()}"
        val uris = java.util.Collections.synchronizedList(mutableListOf<Uri>())
        val repo = LocalMediaRepository(context)
        val jpeg = Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).let { bitmap ->
            try { java.io.ByteArrayOutputStream().use { out -> bitmap.eraseColor(android.graphics.Color.GREEN);check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out));out.toByteArray() } }
            finally { bitmap.recycle() }
        }
        fun insert(collection:Uri,path:String,name:String,mime:String,data:ByteArray,pending:Boolean=false):Uri {
            val uri = requireNotNull(resolver.insert(collection,ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH,path);put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.IS_PENDING,1)
            }));uris += uri
            resolver.openOutputStream(uri,"w")!!.use { it.write(data) }
            if (!pending) assertEquals(1,resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null))
            return uri
        }
        fun all(settings:GallerySettings=GallerySettings(),query:String="",limit:Int=60):List<LocalMediaTake> {
            val result=mutableListOf<LocalMediaTake>();var cursor:LocalMediaCursor?=null;var count=0
            do {
                check(++count<500) { "Nonterminating cursor" }
                val page=repo.page(settings,query,cursor,limit);result+=page.takes;cursor=page.next
            } while(cursor!=null)
            return result
        }
    }
    private fun fixture(action:(Fixture)->Unit) { val f=Fixture();try { action(f) } finally { f.uris.reversed().forEach { resolver.delete(it,null,null) } } }
    private fun bytes(uri:Uri)=resolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun sha(data:ByteArray)=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
