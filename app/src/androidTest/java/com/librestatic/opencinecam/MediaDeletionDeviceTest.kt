/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real owned MediaStore rows and conditional deletes. Synthetic recording bytes qualify only
 * member ownership/deletion, not encoding or editor interoperability. Never deletes foreign rows. */
class MediaDeletionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val photos = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val audio = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Test fun confirmedPhotoDeletesOnlyItsOriginalAndMetadataAndPreservesOtherTakeAndDerivedCache() = fixture { f ->
        val take = f.photo()
        val other = f.insert(photos,"DCIM/OpenCineCam/","${f.token}-neighbor.jpg","image/jpeg",f.jpeg)
        val share = MediaShareExporter(context).prepare(take,MediaSharingSettings(MediaShareContent.METADATA_ONLY))
        try {
            val derived = share.files.associate { it.uri to bytes(it.uri) }
            val result = MediaTakeDeleter(context).delete(take)
            assertTrue(result.toString(),result.complete)
            assertEquals((take.originals+take.metadata).map { it.uri }.toSet(),result.files.map { it.artifact.uri }.toSet())
            assertTrue(result.files.all { it.status==MediaDeleteStatus.ABSENT_VERIFIED })
            (take.originals+take.metadata).forEach { assertFalse(exists(it.uri)) }
            assertArrayEquals(f.jpeg,bytes(other.toString()))
            derived.forEach { (uri,data) -> assertArrayEquals(data,bytes(uri)) }
            assertTrue(LocalMediaRepository(context).page(GallerySettings(),f.token).takes.none { it.id==take.id })
        } finally { share.discard() }
    }

    @Test fun relatedRecordingDeletesAudioAndMetadataBeforeItsVideoAnchor() = fixture { f ->
        val take = f.recording();val order = mutableListOf<String>()
        val access = MediaStoreDeleteAccess(context)
        val result = MediaTakeDeleter(object:MediaDeleteAccess by access {
            override fun delete(row:MediaDeleteRow):Int { order+=row.artifact.uri;return access.delete(row) }
        }).delete(take)
        assertTrue(result.toString(),result.complete)
        assertEquals(take.primary.uri,order.last())
        assertEquals((take.originals.filter { it!=take.primary }+take.metadata+take.primary).map { it.uri },order)
        (take.originals+take.metadata).forEach { assertFalse(exists(it.uri)) }
    }

    @Test fun staleNameAndPendingOriginalRejectWithoutMutatingAnySelectedBytes() = fixture { f ->
        val take=f.photo();val before=f.snapshot(take)
        val uri=take.primary.uri.toUri()
        assertEquals(1,resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,"${f.token}-changed.jpg") },null,null))
        assertFalse(MediaTakeDeleter(context).delete(take).complete);f.assertBytes(before)
        assertEquals(1,resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,take.primary.name);put(MediaStore.MediaColumns.IS_PENDING,1) },null,null))
        try { assertFalse(MediaTakeDeleter(context).delete(take).complete);f.assertBytes(before) }
        finally { assertEquals(1,resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)) }
    }

    @Test fun hiddenPendingNamespaceMemberRejectsBeforeAnyVisibleMemberIsDeleted() = fixture { f ->
        val take=f.photo();val before=f.snapshot(take)
        val path=resolver.query(take.primary.uri.toUri(),arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)!!.use { assertTrue(it.moveToFirst());it.getString(0) }
        val pending=f.insert(photos,path,"${f.token}-pending.jpg","image/jpeg",f.jpeg,pending=true)
        assertFalse(MediaTakeDeleter(context).delete(take).complete)
        f.assertBytes(before);assertArrayEquals(f.jpeg,bytes(pending.toString()))
    }

    @Test fun forgedForeignMemberAndNonCanonicalUriNeverAuthorizeDeletion() = fixture { f ->
        val take=f.photo();val before=f.snapshot(take)
        val other=f.insert(photos,"DCIM/OpenCineCam/","${f.token}-other.jpg","image/jpeg",f.jpeg)
        val foreign=LocalMediaRepository(context).page(GallerySettings(),"${f.token}-other").takes.single().primary
        for(forged in listOf(take.copy(originals=take.originals+foreign),take.copy(metadata=take.metadata+foreign),
            take.copy(primary=take.primary.copy(uri=take.primary.uri.replace("external_primary","external")),
                originals=listOf(take.primary.copy(uri=take.primary.uri.replace("external_primary","external")))))) {
            assertFalse(MediaTakeDeleter(context).delete(forged).complete)
            f.assertBytes(before);assertArrayEquals(f.jpeg,bytes(other.toString()))
        }
    }

    @Test fun uncommittedJournalRejectsAndRemainsByteIdentical() = fixture { f ->
        val take=f.photo();val before=f.snapshot(take);val (isolated,dir)=f.isolatedContext()
        val db=File(dir,"still-recovery.sqlite")
        SQLiteDatabase.openOrCreateDatabase(db,null).use {
            it.execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY NOT NULL, committed INTEGER NOT NULL)")
            it.version=1;it.execSQL("INSERT INTO captures VALUES(?,0)",arrayOf(take.id.removePrefix("still:")))
        }
        val journal=db.readBytes();assertFalse(MediaTakeDeleter(isolated).delete(take).complete)
        assertArrayEquals(journal,db.readBytes());f.assertBytes(before)
    }

    @Test fun missingOrCorruptRelationDeletesOnlyObservedOwnedNamespaceMembers() = fixture { f ->
        val id=UUID.randomUUID().toString();val folder="OpenCineCam/OCC_$id/"
        val image=f.insert(photos,"DCIM/$folder","${f.token}-bad.jpg","image/jpeg",f.jpeg)
        val metadata=f.insert(downloads,"Download/$folder","${f.token}-bad.json","application/json","{invalid".toByteArray())
        val corrupt=LocalMediaRepository(context).page(GallerySettings(),"${f.token}-bad").takes.single()
        assertEquals(LocalMediaRelationStatus.INVALID_METADATA,corrupt.relationStatus)
        val result=MediaTakeDeleter(context).delete(corrupt)
        assertTrue(result.toString(),result.complete);assertFalse(exists(image.toString()));assertFalse(exists(metadata.toString()))
        val missingImage=StillImageSaver(context).saveJpeg(f.jpeg);f.uris+=missingImage
        val missing=LocalMediaRepository(context).page(GallerySettings()).takes.first { it.primary.uri==missingImage.toString() }
        assertEquals(LocalMediaRelationStatus.MISSING_METADATA,missing.relationStatus)
        assertTrue(MediaTakeDeleter(context).delete(missing).complete);assertFalse(exists(missingImage.toString()))
    }

    @Test fun conditionalMismatchAfterAudioDeleteRetainsChangedMetadataAndVideoAndReportsPartial() = fixture { f ->
        val take=f.recording();val anchor=bytes(take.primary.uri);val meta=take.metadata.first();val beforeMeta=bytes(meta.uri)
        val access=MediaStoreDeleteAccess(context)
        val result=MediaTakeDeleter(object:MediaDeleteAccess by access {
            override fun delete(row:MediaDeleteRow):Int {
                if(row.artifact.uri==meta.uri) assertEquals(1,resolver.update(meta.uri.toUri(),ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,"${f.token}-changed.json")
                },null,null))
                return access.delete(row)
            }
        }).delete(take)
        assertFalse(result.complete);assertTrue(result.toString(),result.partial)
        assertEquals(MediaDeleteStatus.RETAINED,result.files.single { it.artifact.uri==meta.uri }.status)
        assertEquals(MediaDeleteStatus.NOT_ATTEMPTED,result.files.single { it.artifact.uri==take.primary.uri }.status)
        assertArrayEquals(anchor,bytes(take.primary.uri));assertArrayEquals(beforeMeta,bytes(meta.uri))
        assertFalse(exists(take.originals.single { it!=take.primary }.uri))
        val refreshed=LocalMediaRepository(context).page(GallerySettings(),f.token).takes.single()
        assertEquals(take.primary.uri,refreshed.primary.uri);assertNotEquals(LocalMediaRelationStatus.DECLARED,refreshed.relationStatus)
    }

    @Test fun verificationFailureAfterActualDeleteReportsUnknownAndKeepsAnchor() = fixture { f ->
        val take=f.recording();val secondary=take.originals.single { it!=take.primary };val anchor=bytes(take.primary.uri)
        val access=MediaStoreDeleteAccess(context)
        val result=MediaTakeDeleter(object:MediaDeleteAccess by access {
            override fun isAbsent(row:MediaDeleteRow):Boolean {
                if(row.artifact.uri==secondary.uri) throw IOException("Fixture provider verification failed")
                return access.isAbsent(row)
            }
        }).delete(take)
        assertFalse(result.complete)
        assertEquals(MediaDeleteStatus.UNKNOWN,result.files.single { it.artifact.uri==secondary.uri }.status)
        assertFalse(exists(secondary.uri));assertArrayEquals(anchor,bytes(take.primary.uri))
        take.metadata.forEach { assertTrue(exists(it.uri)) }
        assertEquals(MediaDeleteStatus.NOT_ATTEMPTED,result.files.single { it.artifact.uri==take.primary.uri }.status)
    }

    @Test fun overflowSelectionNeverDeletesATruncatedTake() = fixture { f ->
        val path="DCIM/OpenCineCam/OCC_${UUID.randomUUID()}/"
        val originals=(1..18).map { f.insert(photos,path,"${f.token}-$it.jpg","image/jpeg",f.jpeg) }
        val take=LocalMediaRepository(context).page(GallerySettings(),f.token).takes.single()
        assertEquals(LocalMediaRelationStatus.INCOMPLETE,take.relationStatus)
        assertTrue(take.originals.size<originals.size)
        assertFalse(MediaTakeDeleter(context).delete(take).complete)
        originals.forEach { assertArrayEquals(f.jpeg,bytes(it.toString())) }
    }

    @Test fun newNamespaceMemberPreventsFalseCompleteAndIsNeverDeleted() = fixture { f ->
        val take=f.photo()
        val path=resolver.query(take.primary.uri.toUri(),arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)!!.use { assertTrue(it.moveToFirst());it.getString(0) }
        var added:Uri?=null
        val access=MediaStoreDeleteAccess(context)
        val result=MediaTakeDeleter(object:MediaDeleteAccess by access {
            override fun verifyEmpty(plan:MediaDeletePlan) {
                added=f.insert(photos,path,"${f.token}-new.jpg","image/jpeg",f.jpeg)
                access.verifyEmpty(plan)
            }
        }).delete(take)
        assertFalse(result.complete);assertNotNull(result.error)
        assertTrue(result.files.all { it.status==MediaDeleteStatus.ABSENT_VERIFIED })
        assertArrayEquals(f.jpeg,bytes(requireNotNull(added).toString()))
        assertFalse(result.files.any { it.artifact.uri==added.toString() })
    }

    private fun exists(uri:String)=resolver.query(uri.toUri(),arrayOf("_id"),null,null,null)!!.use { it.moveToFirst() }
    private fun bytes(uri:String)=resolver.openInputStream(uri.toUri())!!.use { it.readBytes() }
    private inner class Fixture {
        val token="DELETE_${UUID.randomUUID()}";val uris=mutableListOf<Uri>();val privateDirs=mutableListOf<File>()
        val jpeg=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).let { bitmap ->
            try { java.io.ByteArrayOutputStream().use { out -> bitmap.eraseColor(android.graphics.Color.BLUE);check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out));out.toByteArray() } }
            finally { bitmap.recycle() }
        }
        fun photo():LocalMediaTake {
            val image=StillImageSaver(context).saveJpeg(jpeg,ProductionSlateSettings(project=token));uris+=image
            val take=LocalMediaRepository(context).page(GallerySettings(),token).takes.single()
            uris+=take.metadata.map { it.uri.toUri() };return take
        }
        fun recording():LocalMediaTake {
            val id=UUID.randomUUID().toString();val folder="OpenCineCam/OCC_TAKE_$id/"
            val video=insert(videos,"DCIM/$folder","$token.mp4","video/mp4",byteArrayOf(1,2,3))
            val sound=insert(audio,"Music/$folder","$token.wav","audio/wav",byteArrayOf(4,5,6))
            val slate=ProductionSlateSettings(project=token)
            insert(downloads,"Download/$folder","$token.timing.json","application/json",buildJsonObject {
                put("schema","opencinecam.recording.v1");put("bundleId",id);put("videoUri",video.toString());put("productionSlate",productionSlateJson(slate))
            }.toString().toByteArray())
            insert(downloads,"Download/$folder","$token.wav.json","application/json",buildJsonObject {
                put("schema","opencinecam-audio-sidecar-v1");put("audioUri",sound.toString());put("file","$token.wav");put("productionSlate",productionSlateJson(slate))
            }.toString().toByteArray())
            return LocalMediaRepository(context).page(GallerySettings(),token).takes.single().also {
                assertEquals(LocalMediaRelationStatus.DECLARED,it.relationStatus);assertEquals(2,it.originals.size);assertEquals(2,it.metadata.size)
            }
        }
        fun insert(collection:Uri,path:String,name:String,mime:String,data:ByteArray,pending:Boolean=false):Uri {
            val uri=resolver.insert(collection,ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH,path);put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.IS_PENDING,1)
            })!!;uris+=uri;resolver.openOutputStream(uri,"w")!!.use { it.write(data) }
            if(!pending) assertEquals(1,resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null))
            return uri
        }
        fun snapshot(take:LocalMediaTake)=(take.originals+take.metadata).associate { it.uri to bytes(it.uri) }
        fun assertBytes(before:Map<String,ByteArray>)=before.forEach { (uri,data) -> assertArrayEquals(data,bytes(uri)) }
        fun isolatedContext():Pair<Context,File> {
            val dir=File(context.cacheDir,"delete-private-${UUID.randomUUID()}").apply { mkdirs() };privateDirs+=dir
            return object:ContextWrapper(context) { override fun getApplicationContext():Context=this;override fun getNoBackupFilesDir():File=dir } to dir
        }
        fun close() {
            uris.asReversed().forEach { if(exists(it.toString())) assertEquals(1,resolver.delete(it,null,null)) }
            privateDirs.forEach { it.deleteRecursively() }
        }
    }
    private fun fixture(action:(Fixture)->Unit) { val f=Fixture();try { action(f) } finally { f.close() } }
}
