/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.LutSignalDomain
import com.librestatic.opencinecam.camera.LutTransformKind
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real provider rows/FileProvider/SQLite and an independent recipient UID, not a mocked intent. */
class MediaSharingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val photos = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Test fun completeTakeReachesAnotherUidWithAllReadOnlyGrantsAndNoMixedProductionMetadata() = fixture { f ->
        val take = f.photo()
        val before = (take.originals + take.metadata).associate { it.uri to bytes(it.uri) }
        val share = f.prepare(take)
        assertEquals(setOf(MediaShareRole.ORIGINAL,MediaShareRole.RELATIONSHIP,MediaShareRole.PRODUCTION,MediaShareRole.TECHNICAL),share.files.map { it.role }.toSet())
        assertEquals(4,share.files.size)
        assertEquals(take.primary.uri,share.files.single { it.role == MediaShareRole.ORIGINAL }.uri)
        assertTrue(share.files.none { it.uri in take.metadata.map { m -> m.uri } })
        val production = json(share,MediaShareRole.PRODUCTION)
        assertEquals(setOf("schema","takeId","files","productionSlate"),production.keys)
        assertEquals(take.slate,parseProductionSlateJson(production.getValue("productionSlate").jsonObject))
        val technical = json(share,MediaShareRole.TECHNICAL)
        assertFalse(technical.toString().contains(f.token));assertFalse(hasProductionNode(technical))
        assertTrue(technical.toString().contains("LEGACY_ENCODED_BYTES_ONLY"))
        val manifest = json(share,MediaShareRole.RELATIONSHIP)
        assertEquals(take.id,manifest.getValue("takeId").jsonPrimitive.content)
        assertEquals("NOT_CHECKED",manifest.getValue("originalHashIntegrity").jsonPrimitive.content)
        assertEquals("PREPARED_NOT_DELIVERY_RECEIPT",manifest.getValue("preparation").jsonPrimitive.content)
        assertTrue(manifest.getValue("sourceMetadata").jsonArray.all { !it.jsonObject.getValue("includedRaw").jsonPrimitive.boolean })
        val ungranted = f.ungrantedSibling()
        val expected = share.files.map { bytes(it.uri) }
        share.markPublished();share.discard() // Handoff retention must not disappear through cancel cleanup.
        val result = receive(share,ungranted)
        for (index in expected.indices) assertArrayEquals(expected[index],result.getByteArray("bytes-$index"))
        for ((uri,data) in before) assertArrayEquals(data,bytes(uri))
        assertTrue(share.expiresAtEpochMs > System.currentTimeMillis())
    }

    @Test fun metadataOnlyExportsDoNotGrantAnyOriginalAndTechnicalContainsNoSlate() = fixture { f ->
        val take = f.photo()
        for (mode in listOf(MediaShareMetadata.PRODUCTION,MediaShareMetadata.TECHNICAL)) {
            val share = f.prepare(take,MediaSharingSettings(MediaShareContent.METADATA_ONLY,mode))
            assertEquals(2,share.files.size);assertTrue(share.files.none { it.role==MediaShareRole.ORIGINAL })
            assertEquals(setOf(MediaShareRole.RELATIONSHIP,if(mode==MediaShareMetadata.PRODUCTION) MediaShareRole.PRODUCTION else MediaShareRole.TECHNICAL),share.files.map { it.role }.toSet())
            if(mode==MediaShareMetadata.TECHNICAL) assertTrue(share.files.all { !bytes(it.uri).toString(Charsets.UTF_8).contains(f.token) })
            share.markPublished();receive(share,take.primary.uri.toUri())
        }
    }

    @Test fun abandonedPreparationDiscardsOnlyItsDerivedFilesAndNeverOriginals() = fixture { f ->
        val take=f.photo();val original=bytes(take.primary.uri)
        val share=f.prepare(take);val derived=share.files.filter { it.role!=MediaShareRole.ORIGINAL }.map { it.uri }
        share.discard();share.discard()
        for(uri in derived) try { resolver.openInputStream(uri.toUri())?.close();fail("Abandoned generated file remains") } catch (_:java.io.FileNotFoundException) { }
        try { share.buildIntent();fail("Discarded preparation must not launch") } catch (_:IllegalStateException) { }
        assertArrayEquals(original,bytes(take.primary.uri));assertTrue(take.metadata.all { bytes(it.uri).isNotEmpty() })
    }

    @Test fun changedSelectionAndPendingOriginalRejectBeforeCreatingAnExport() = fixture { f ->
        val take=f.photo();val original=take.primary.uri.toUri()
        assertEquals(1,resolver.update(original,ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,"changed-${f.token}.jpg") },null,null))
        expectRejected { f.prepare(take) }
        assertEquals(1,resolver.update(original,ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,take.primary.name);put(MediaStore.MediaColumns.IS_PENDING,1) },null,null))
        try { expectRejected { f.prepare(take) } }
        finally { assertEquals(1,resolver.update(original,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)) }
        assertArrayEquals(f.jpeg,bytes(take.primary.uri))
    }

    @Test fun missingMetadataRequiresExplicitOriginalsOnlyAndMarksTheRelationshipStatus() = fixture { f ->
        val image=StillImageSaver(context).saveJpeg(f.jpeg)
        f.uris+=image
        val catalog=LocalMediaRepository(context).page(GallerySettings()).takes.first { it.primary.uri==image.toString() }
        assertEquals(LocalMediaRelationStatus.MISSING_METADATA,catalog.relationStatus)
        expectRejected { f.prepare(catalog) }
        val share=f.prepare(catalog,MediaSharingSettings(content=MediaShareContent.ORIGINALS_ONLY))
        assertEquals(setOf(MediaShareRole.ORIGINAL,MediaShareRole.RELATIONSHIP),share.files.map { it.role }.toSet())
        assertEquals("MISSING_METADATA",json(share,MediaShareRole.RELATIONSHIP).getValue("relationStatus").jsonPrimitive.content)
        assertArrayEquals(f.jpeg,bytes(image.toString()))
    }

    @Test fun uncommittedReceiptInReadOnlySnapshotRejectsWithoutPruningOrModifyingMedia() = fixture { f ->
        val take=f.photo();val (isolated,dir)=f.isolatedContext()
        val dbFile=File(dir,"still-recovery.sqlite")
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use {
            it.execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY NOT NULL, committed INTEGER NOT NULL)")
            it.version=1;it.execSQL("INSERT INTO captures VALUES(?,0)",arrayOf(take.id.removePrefix("still:")))
        }
        val before=dbFile.readBytes();val media=bytes(take.primary.uri)
        expectRejected { MediaShareExporter(isolated).prepare(take,MediaSharingSettings()) }
        assertArrayEquals(before,dbFile.readBytes());assertArrayEquals(media,bytes(take.primary.uri))
    }

    @Test fun referencedLutExportsExactOriginalAndMissingLutNeedsExplicitExclusion() = fixture { f ->
        val (isolated,dir)=f.isolatedContext()
        val cube="""TITLE "Share fixture"
LUT_3D_SIZE 2
0 0 0
1 0 0
0 1 0
1 1 0
0 0 1
1 0 1
0 1 1
1 1 1
""".toByteArray()
        val library=LutLibrary(isolated)
        val entry=library.importLut(cube,"Share fixture",LutTransformKind.CREATIVE,LutSignalDomain.SDR_BT709_CODE)
        val id=UUID.randomUUID().toString();val folder="OpenCineCam/OCC_TAKE_$id/"
        val original=f.insert(videos,"DCIM/$folder","${f.token}.mp4","video/mp4",byteArrayOf(1,2,3))
        f.insert(downloads,"Download/$folder","${f.token}.timing.json","application/json",buildJsonObject {
            put("schema","opencinecam.recording.v1");put("bundleId",id);put("videoUri",original.toString())
            put("productionSlate",productionSlateJson(ProductionSlateSettings(project=f.token)))
            putJsonObject("recordingLut") { put("baked",true);put("reapplyInEditor",false);put("originalCubeSha256",entry.hash) }
        }.toString().toByteArray())
        val take=LocalMediaRepository(context).page(GallerySettings(),f.token).takes.single()
        val libraryFile=File(dir,"operator-lut-library.bin");val before=libraryFile.readBytes()
        val exporter=MediaShareExporter(isolated)
        val share=exporter.prepare(take,MediaSharingSettings()).also { f.shares+=it }
        assertArrayEquals(cube,bytes(share.files.single { it.role==MediaShareRole.LUT }.uri))
        assertArrayEquals(before,libraryFile.readBytes())
        val lut=json(share,MediaShareRole.RELATIONSHIP).getValue("recordingLuts").jsonArray.single().jsonObject
        assertTrue(lut.getValue("included").jsonPrimitive.boolean);assertTrue(lut.getValue("sha256Verified").jsonPrimitive.boolean)
        assertFalse(lut.getValue("reapplyInEditor").jsonPrimitive.boolean)
        library.delete(entry.hash)
        expectRejected { exporter.prepare(take,MediaSharingSettings()) }
        val excluded=exporter.prepare(take,MediaSharingSettings(includeReferencedLut=false)).also { f.shares+=it }
        assertTrue(excluded.files.none { it.role==MediaShareRole.LUT })
        assertFalse(json(excluded,MediaShareRole.RELATIONSHIP).getValue("recordingLuts").jsonArray.single().jsonObject.getValue("included").jsonPrimitive.boolean)
        assertArrayEquals(byteArrayOf(1,2,3),bytes(original.toString()))
    }

    @Test fun expiredDerivedSessionIsReclaimedWhileRecentHandoffAndOriginalStayIntact() = fixture { f ->
        val take=f.photo();val original=bytes(take.primary.uri)
        val expired=f.prepare(take);expired.markPublished()
        val recent=f.prepare(take);recent.markPublished()
        val expiredUri=expired.files.first { it.role==MediaShareRole.RELATIONSHIP }.uri
        val oldId=expiredUri.toUri().pathSegments[1]
        assertTrue(File(context.cacheDir,"media-exports/$oldId").setLastModified(System.currentTimeMillis()-25L*60*60*1000))
        val next=f.prepare(take)
        try { resolver.openInputStream(expiredUri.toUri())?.close();fail("Expired export still exists") } catch (_:java.io.FileNotFoundException) { }
        assertTrue(recent.files.all { bytes(it.uri).isNotEmpty() });assertTrue(next.files.all { bytes(it.uri).isNotEmpty() })
        assertArrayEquals(original,bytes(take.primary.uri))
    }

    private fun receive(share:PreparedMediaShare,ungranted:Uri):Bundle {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        val intent=share.buildIntent()
        assertEquals(Intent.ACTION_SEND_MULTIPLE,intent.action)
        val grantMask=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,intent.flags and grantMask)
        val done=CountDownLatch(1);val reply=AtomicReference<Bundle>()
        intent.component=ComponentName(instrumentation.context.packageName,MediaShareReceiverActivity::class.java.name)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra("reply",object:ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode:Int,resultData:Bundle) { reply.set(resultData);done.countDown() }
        });intent.putExtra("ungranted",ungranted)
        instrumentation.runOnMainSync { context.startActivity(intent) }
        assertTrue("External recipient did not return",done.await(15,TimeUnit.SECONDS))
        val result=reply.get();assertTrue(result.getString("failure"),result.getBoolean("success"))
        assertNotEquals(Process.myUid(),result.getInt("uid"));assertEquals(instrumentation.context.applicationInfo.uid,result.getInt("uid"))
        assertEquals(share.files.size,result.getInt("count"))
        for(index in share.files.indices) assertTrue("Writable external URI ${share.files[index].uri}",result.getBoolean("writeDenied-$index"))
        assertTrue("Unselected URI received a grant",result.getBoolean("ungrantedDenied"))
        instrumentation.waitForIdleSync()
        return result
    }
    private fun json(share:PreparedMediaShare,role:MediaShareRole)=Json.parseToJsonElement(bytes(share.files.single { it.role==role }.uri).toString(Charsets.UTF_8)).jsonObject
    private fun hasProductionNode(value:JsonElement):Boolean=when(value) {
        is JsonObject -> "productionSlate" in value || value.values.any(::hasProductionNode)
        is JsonArray -> value.any(::hasProductionNode)
        else -> false
    }
    private fun bytes(uri:String)=resolver.openInputStream(uri.toUri())!!.use { it.readBytes() }
    private fun expectRejected(action:()->Unit) { try { action();fail("Invalid/stale selection must reject") } catch (_:IllegalStateException) { } catch (_:IllegalArgumentException) { } }
    private inner class Fixture {
        val token="SHARE_${UUID.randomUUID()}"
        val uris=mutableListOf<Uri>();val shares=mutableListOf<PreparedMediaShare>();val privateDirs=mutableListOf<File>()
        val jpeg=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).let { bitmap ->
            try { java.io.ByteArrayOutputStream().use { out -> bitmap.eraseColor(android.graphics.Color.GREEN);check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out));out.toByteArray() } } finally { bitmap.recycle() }
        }
        fun photo():LocalMediaTake {
            val image=StillImageSaver(context).saveJpeg(jpeg,ProductionSlateSettings(project=token,scene="Private scene",takeNumber=9,goodTake=true));uris+=image
            val take=LocalMediaRepository(context).page(GallerySettings(),token).takes.single()
            uris+=take.metadata.map { it.uri.toUri() };return take
        }
        fun prepare(take:LocalMediaTake,settings:MediaSharingSettings=MediaSharingSettings())=MediaShareExporter(context).prepare(take,settings).also { shares+=it }
        fun insert(collection:Uri,path:String,name:String,mime:String,data:ByteArray):Uri {
            val uri=resolver.insert(collection,ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH,path);put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.IS_PENDING,1)
            })!!;uris+=uri;resolver.openOutputStream(uri,"w")!!.use { it.write(data) }
            assertEquals(1,resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null));return uri
        }
        fun isolatedContext():Pair<Context,File> {
            val dir=File(context.cacheDir,"share-private-${UUID.randomUUID()}").apply { mkdirs() };privateDirs+=dir
            return object:ContextWrapper(context) { override fun getApplicationContext():Context=this;override fun getNoBackupFilesDir():File=dir } to dir
        }
        fun ungrantedSibling():Uri {
            val dir=File(context.cacheDir,"media-exports/${UUID.randomUUID()}").apply { mkdirs() };privateDirs+=dir
            val file=File(dir,"take.production.json").apply { writeText("ungranted fixture") }
            return FileProvider.getUriForFile(context,"${context.packageName}.mediaexports",file)
        }
        fun close() {
            shares.forEach { share ->
                share.files.forEach { context.revokeUriPermission(it.uri.toUri(),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                share.discard()
                // Test-only cleanup after the independent receiver has returned; production retains handoffs.
                share.files.filter { it.role!=MediaShareRole.ORIGINAL }.map { it.uri.toUri().pathSegments[1] }.distinct().forEach { id ->
                    check(UUID.fromString(id).toString()==id);File(context.cacheDir,"media-exports/$id").deleteRecursively()
                }
            }
            uris.asReversed().forEach { resolver.delete(it,null,null) }
            privateDirs.forEach { it.deleteRecursively() }
        }
    }
    private fun fixture(action:(Fixture)->Unit) { val f=Fixture();try { action(f) } finally { f.close() } }
}
