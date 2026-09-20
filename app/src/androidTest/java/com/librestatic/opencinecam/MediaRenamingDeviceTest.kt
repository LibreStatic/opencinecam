/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import com.librestatic.opencinecam.transfers.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual MediaStore rename and JSON readback. Recording fixture bytes qualify relationships,
 * not a codec. IO seams inject named failures without bypassing the real ownership predicates. */
class MediaRenamingDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val photos = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val audio = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Test fun photoRenameUpdatesOnlyDeclaredNameReferencesAndPreservesOriginalBytesAndNeighbor() = fixture { f ->
        var take=f.photo()
        val metadata=take.metadata.single()
        val document=Json.parseToJsonElement(bytes(metadata.uri).toString(Charsets.UTF_8)).jsonObject
        val editorial=take.slate!!.copy(scene=take.primary.name)
        val modified=JsonObject(document.toMutableMap().apply { put("productionSlate",productionSlateJson(editorial)) }).toString().toByteArray()
        resolver.openOutputStream(metadata.uri.toUri(),"wt")!!.use { it.write(modified) }
        take=LocalMediaRepository(context).page(GallerySettings(),f.token).takes.single()
        val original=bytes(take.primary.uri)
        val neighbor=f.insert(photos,"DCIM/OpenCineCam/","${f.token}-neighbor.jpg","image/jpeg",f.jpeg)
        val preview=mediaRenamePreview(take,"Noche Á")
        val result=MediaTakeRenamer(context).rename(take,preview.stem)
        assertTrue(result.toString(),result.complete)
        val fresh=LocalMediaRepository(context).page(GallerySettings(),f.token).takes.first { it.id==take.id }
        assertEquals(LocalMediaRelationStatus.DECLARED,fresh.relationStatus)
        assertEquals(take.primary.uri,fresh.primary.uri);assertEquals(editorial,fresh.slate)
        assertEquals(preview.files.associate { it.artifact.uri to it.newName },(fresh.originals+fresh.metadata).associate { it.uri to it.name })
        assertArrayEquals(original,bytes(fresh.primary.uri));assertArrayEquals(f.jpeg,bytes(neighbor.toString()))
        val after=Json.parseToJsonElement(bytes(fresh.metadata.single().uri).toString(Charsets.UTF_8)).jsonObject
        assertEquals(fresh.primary.name,after.getValue("images").jsonArray.single().jsonObject.getValue("displayName").jsonPrimitive.content)
        assertEquals(document.getValue("images").jsonArray.single().jsonObject.getValue("sha256"),after.getValue("images").jsonArray.single().jsonObject.getValue("sha256"))
        assertEquals(take.primary.name,fresh.slate!!.scene) // Not a global string replacement.
    }

    @Test fun recordingRenameKeepsUrisAndAudioFileReferenceCoherentWithoutChangingOriginals() = fixture { f ->
        val take=f.recording();val before=take.originals.associate { it.uri to bytes(it.uri) }
        val result=MediaTakeRenamer(context).rename(take,"Recording renamed")
        assertTrue(result.toString(),result.complete)
        val fresh=LocalMediaRepository(context).page(GallerySettings(),f.token).takes.single()
        assertEquals(LocalMediaRelationStatus.DECLARED,fresh.relationStatus)
        assertEquals(take.id,fresh.id);assertEquals(take.slate,fresh.slate)
        assertEquals(take.originals.map { it.uri }.toSet(),fresh.originals.map { it.uri }.toSet())
        f.assertBytes(before)
        val sound=fresh.originals.single { it!=fresh.primary }
        val audioDocument=fresh.metadata.map { Json.parseToJsonElement(bytes(it.uri).toString(Charsets.UTF_8)).jsonObject }
            .single { it["schema"]?.jsonPrimitive?.content=="opencinecam-audio-sidecar-v1" }
        assertEquals(sound.uri,audioDocument.getValue("audioUri").jsonPrimitive.content)
        assertEquals(sound.name,audioDocument.getValue("file").jsonPrimitive.content)
    }

    @Test fun alreadyRenamedTakeDoesNotWriteOrHoldOutboxAgain() = fixture { f ->
        val take=f.photo();assertTrue(MediaTakeRenamer(context).rename(take,"Repeat").complete)
        val fresh=LocalMediaRepository(context).page(GallerySettings(),f.token).takes.single();val before=f.snapshot(fresh)
        val access=MediaStoreRenameAccess(context)
        val result=MediaTakeRenamer(object:MediaRenameAccess by access {
            override fun holdOutbox(plan:MediaRenamePlan) { fail("A no-op must not hold pending transfers") }
            override fun rename(row:MediaDeleteRow,newName:String):MediaDeleteRow { fail("A no-op must not update a row");return row }
            override fun writeMetadata(row:MediaDeleteRow,expected:ByteArray,replacement:ByteArray):MediaDeleteRow { fail("A no-op must not write metadata");return row }
        }).rename(fresh,"Repeat")
        assertTrue(result.toString(),result.complete);assertTrue(result.files.all { it.status==MediaRenameStatus.UNCHANGED })
        assertEquals(fresh,LocalMediaRepository(context).page(GallerySettings(),f.token).takes.single());f.assertBytes(before)
    }

    @Test fun staleSelectionAndMalformedMetadataRejectWithoutFurtherChanges() = fixture { f ->
        val take=f.photo();val before=f.snapshot(take)
        assertEquals(1,resolver.update(take.primary.uri.toUri(),ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,"${f.token}-external.jpg") },null,null))
        assertFalse(MediaTakeRenamer(context).rename(take,"Rejected").complete);f.assertBytes(before)
        val metadata=take.metadata.single()
        val malformed="{invalid".toByteArray();resolver.openOutputStream(metadata.uri.toUri(),"wt")!!.use { it.write(malformed) }
        val fresh=LocalMediaRepository(context).page(GallerySettings(),"${f.token}-external").takes.single()
        assertEquals(LocalMediaRelationStatus.INVALID_METADATA,fresh.relationStatus)
        assertFalse(MediaTakeRenamer(context).rename(fresh,"Rejected").complete)
        assertArrayEquals(malformed,bytes(metadata.uri));assertArrayEquals(f.jpeg,bytes(take.primary.uri))
        assertEquals("${f.token}-external.jpg",name(take.primary.uri))
    }

    @Test fun destinationCollisionAppearingAfterPreflightRetainsEveryFile() = fixture { f ->
        val take=f.photo();val before=f.snapshot(take);val access=MediaStoreRenameAccess(context)
        val path=path(take.primary.uri);var collision:Uri?=null
        val result=MediaTakeRenamer(object:MediaRenameAccess by access {
            override fun checkDestinations(rows:List<MediaDeleteRow>,names:Map<String,String>) {
                if(collision==null) collision=f.insert(photos,path,names.getValue(take.primary.uri),"image/jpeg",f.jpeg)
                access.checkDestinations(rows,names)
            }
        }).rename(take,"Collision")
        assertFalse(result.complete);assertFalse(result.compensationAttempted)
        f.assertBytes(before);assertArrayEquals(f.jpeg,bytes(requireNotNull(collision).toString()))
    }

    @Test fun changedIdentityAtConditionalUpdateIsNeverOverwrittenDuringCompensation() = fixture { f ->
        val take=f.photo();val before=f.snapshot(take);val access=MediaStoreRenameAccess(context);var changed=false
        val result=MediaTakeRenamer(object:MediaRenameAccess by access {
            override fun rename(row:MediaDeleteRow,newName:String):MediaDeleteRow {
                if(!changed) {
                    changed=true
                    assertEquals(1,resolver.update(row.artifact.uri.toUri(),ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,"external-"+row.artifact.name) },null,null))
                }
                return access.rename(row,newName)
            }
        }).rename(take,"Rejected identity")
        assertFalse(result.complete);f.assertBytes(before)
        assertTrue((take.originals+take.metadata).any { name(it.uri)=="external-"+it.name })
    }

    @Test fun failureBeforeMetadataWriteCompensatesNamesAndExactOriginalJsonBytes() = fixture { f ->
        val take=f.photo();val before=f.snapshot(take);val access=MediaStoreRenameAccess(context)
        val result=MediaTakeRenamer(object:MediaRenameAccess by access {
            override fun writeMetadata(row:MediaDeleteRow,expected:ByteArray,replacement:ByteArray):MediaDeleteRow {
                throw IOException("Fixture rejected before first metadata write")
            }
        }).rename(take,"Restore names")
        assertFalse(result.complete);assertTrue(result.compensationAttempted);assertTrue(result.toString(),result.compensationComplete)
        f.assertBytes(before);(take.originals+take.metadata).forEach { assertEquals(it.name,name(it.uri)) }
        assertEquals(LocalMediaRelationStatus.DECLARED,LocalMediaRepository(context).page(GallerySettings(),f.token).takes.single().relationStatus)
    }

    @Test fun actualMetadataWriteThenFailureRestoresExpandedJsonAndNamesExactly() = fixture { f ->
        val take=f.photo();val before=f.snapshot(take);val access=MediaStoreRenameAccess(context)
        var written=false
        val result=MediaTakeRenamer(object:MediaRenameAccess by access {
            override fun writeMetadata(row:MediaDeleteRow,expected:ByteArray,replacement:ByteArray):MediaDeleteRow {
                val changed=access.writeMetadata(row,expected,replacement)
                if(!written) {
                    written=true
                    assertTrue("Long stem expands actual JSON",replacement.size>expected.size)
                    assertArrayEquals(replacement,bytes(row.artifact.uri))
                    throw IOException("Fixture after verified actual metadata write")
                }
                return changed
            }
        }).rename(take,"Á".repeat(90))
        assertTrue("Actual metadata write must be reached",written)
        assertFalse(result.complete);assertTrue(result.compensationAttempted);assertTrue(result.toString(),result.compensationComplete)
        f.assertBytes(before);(take.originals+take.metadata).forEach { assertEquals(it.name,name(it.uri)) }
        assertEquals(LocalMediaRelationStatus.DECLARED,LocalMediaRepository(context).page(GallerySettings(),f.token).takes.single().relationStatus)
    }

    @Test fun foreignMetadataBytesAfterFailureRemainUnknownAndAreNotOverwrittenByRollback() = fixture { f ->
        val take=f.photo();val original=bytes(take.primary.uri);val access=MediaStoreRenameAccess(context)
        val foreign="{\"changedByAnotherWriter\":true}".toByteArray();var changedUri:String?=null
        val result=MediaTakeRenamer(object:MediaRenameAccess by access {
            override fun writeMetadata(row:MediaDeleteRow,expected:ByteArray,replacement:ByteArray):MediaDeleteRow {
                changedUri=row.artifact.uri
                resolver.openOutputStream(row.artifact.uri.toUri(),"wt")!!.use { it.write(foreign) }
                throw IOException("Fixture unrecognized concurrent metadata write")
            }
        }).rename(take,"Unknown metadata")
        assertFalse(result.complete);assertTrue(result.compensationAttempted);assertFalse(result.compensationComplete)
        assertTrue(result.files.any { it.status==MediaRenameStatus.UNKNOWN || it.status==MediaRenameStatus.PARTIAL })
        assertArrayEquals(foreign,bytes(requireNotNull(changedUri)));assertArrayEquals(original,bytes(take.primary.uri))
    }

    @Test fun unexpectedProviderNameIsDetectedAndCompensatedUsingObservedIdentity() = fixture { f ->
        val take=f.photo();val before=f.snapshot(take);val access=MediaStoreRenameAccess(context);var first=true
        val result=MediaTakeRenamer(object:MediaRenameAccess by access {
            override fun rename(row:MediaDeleteRow,newName:String):MediaDeleteRow {
                val requested=if(first) { first=false;newName.substringBeforeLast('.')+" (1)."+newName.substringAfterLast('.') } else newName
                return access.rename(row,requested)
            }
        }).rename(take,"Provider result")
        assertFalse(result.complete);assertTrue(result.compensationAttempted);assertTrue(result.toString(),result.compensationComplete)
        f.assertBytes(before);(take.originals+take.metadata).forEach { assertEquals(it.name,name(it.uri)) }
    }

    @Test fun enrolledSnapshotIsHeldBeforeLocalRenameAndRemoteNamesRemainUnchanged() = fixture { f ->
        val take=f.recording();val database="rename-outbox-${UUID.randomUUID()}.db"
        try {
            val original=take.primary;val metadata=take.metadata.first { Json.parseToJsonElement(bytes(it.uri).toString(Charsets.UTF_8)).jsonObject["schema"]?.jsonPrimitive?.content=="opencinecam.recording.v1" }
            val specs=listOf(WebDavArtifactSpec(UUID.randomUUID().toString(),WebDavArtifactRole.VIDEO,original.uri,original.name,original.sizeBytes),
                WebDavArtifactSpec(UUID.randomUUID().toString(),WebDavArtifactRole.VIDEO_METADATA,metadata.uri,metadata.name,metadata.sizeBytes))
            val plan=WebDavBundlePlan(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,specs)
            val members=(take.originals+take.metadata).associateBy { it.uri }
            WebDavSqliteOutbox(context,database).use { store ->
                store.stage(plan);assertNotNull(store.seal(plan.id,0,specs.map { spec -> val member=members.getValue(spec.sourceUri)
                    WebDavArtifactPublication(spec.id,WebDavClipSnapshot(spec.sourceUri,spec.sourceName,spec.sizeBytes,member.modifiedSeconds,true)) }))
            }
            val access=MediaStoreRenameAccess(context);var held=false
            val result=MediaTakeRenamer(object:MediaRenameAccess by access {
                override fun holdOutbox(plan:MediaRenamePlan) {
                    WebDavSqliteOutbox(context,database).use { store ->
                        val changed=store.holdSourcesForLocalRename(members.keys)
                        assertTrue(changed.single().artifacts.all { it.sourceFailure?.reason==WebDavSourceFailureReason.LOCAL_RENAME_REQUESTED })
                        members.values.forEach { assertEquals(it.name,name(it.uri)) }
                    };held=true
                }
                override fun rename(row:MediaDeleteRow,newName:String):MediaDeleteRow { assertTrue(held);return access.rename(row,newName) }
            }).rename(take,"Local version")
            assertTrue(result.toString(),result.complete)
            WebDavSqliteOutbox(context,database).use { store ->
                val after=requireNotNull(store.load(plan.id));assertEquals(WebDavBundleState.ATTENTION,after.state)
                assertEquals(specs.toSet(),after.artifacts.map { it.spec }.toSet())
                assertTrue(after.artifacts.all { it.attempt==null && !it.remoteMayExist })
            }
            assertEquals(LocalMediaRelationStatus.DECLARED,LocalMediaRepository(context).page(GallerySettings(),f.token).takes.single().relationStatus)
        } finally { context.deleteDatabase(database) }
    }

    @Test fun absentOutboxGuardDoesNotCreateDatabaseOrCompanionFiles() = fixture { f ->
        val (base,dir)=f.isolatedContext()
        val isolated=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getDatabasePath(name:String):File=File(dir,name)
        }
        MediaRenameOutboxGuard.prepare(isolated,setOf("content://media/external_primary/video/media/123"))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    private fun name(uri:String)=resolver.query(uri.toUri(),arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)!!.use { assertTrue(it.moveToFirst());it.getString(0) }
    private fun path(uri:String)=resolver.query(uri.toUri(),arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)!!.use { assertTrue(it.moveToFirst());it.getString(0) }
    private fun exists(uri:String)=resolver.query(uri.toUri(),arrayOf("_id"),null,null,null)!!.use { it.moveToFirst() }
    private fun bytes(uri:String)=resolver.openInputStream(uri.toUri())!!.use { it.readBytes() }
    private inner class Fixture {
        val token="RENAME_${UUID.randomUUID()}";val uris=mutableListOf<Uri>();val privateDirs=mutableListOf<File>()
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
            val dir=File(context.cacheDir,"rename-private-${UUID.randomUUID()}").apply { mkdirs() };privateDirs+=dir
            return object:ContextWrapper(context) { override fun getApplicationContext():Context=this;override fun getNoBackupFilesDir():File=dir } to dir
        }
        fun close() {
            uris.asReversed().forEach { if(exists(it.toString())) assertEquals(1,resolver.delete(it,null,null)) }
            privateDirs.forEach { it.deleteRecursively() }
        }
    }
    private fun fixture(action:(Fixture)->Unit) { val f=Fixture();try { action(f) } finally { f.close() } }
}
