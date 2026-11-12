/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecordingDestinationStoreTest {
    private lateinit var context: Context
    private var probeResult = DriveProbeResult.OK
    private val probed = mutableListOf<Uri>()
    /** Every state the store asked the USB attach alias to take, in order. */
    private val attachHandler = mutableListOf<Boolean>()
    /** Runs once inside the next probe, while the adoption waits on it. */
    private var duringProbe: (() -> Unit)? = null

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf(RECORDING_JOURNAL, STILL_JOURNAL).forEach { File(context.noBackupFilesDir, it).delete() }
        context.getDatabasePath(WEBDAV_OUTBOX_DATABASE).delete()
    }

    /** An artifact in [state] in the WebDAV outbox whose source is a take's video on [tree], as an upload of a drive take queues it. */
    private fun outboxed(tree: Uri, state: String) {
        val file = context.getDatabasePath(WEBDAV_OUTBOX_DATABASE).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("""CREATE TABLE bundles (
                id TEXT PRIMARY KEY NOT NULL, endpoint_id TEXT NOT NULL,
                endpoint_revision INTEGER NOT NULL CHECK(endpoint_revision>=0),
                revision INTEGER NOT NULL CHECK(revision>=0), sealed INTEGER NOT NULL CHECK(sealed IN (0,1)))""")
            db.execSQL("""CREATE TABLE artifacts (
                id TEXT PRIMARY KEY NOT NULL, bundle_id TEXT NOT NULL REFERENCES bundles(id) ON DELETE CASCADE,
                role TEXT NOT NULL, source_uri TEXT NOT NULL, source_name TEXT NOT NULL, remote_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL CHECK(size_bytes>0), revision INTEGER NOT NULL CHECK(revision>=0),
                modified_seconds INTEGER CHECK(modified_seconds>=0), sha256 TEXT, state TEXT NOT NULL,
                attempt_id TEXT UNIQUE, attempt_kind TEXT, process_token TEXT,
                admission_process TEXT, admission_revision INTEGER CHECK(admission_revision>=0), admission_network TEXT,
                admission_cellular INTEGER CHECK(admission_cellular IN (0,1)),
                remote_may_exist INTEGER NOT NULL CHECK(remote_may_exist IN (0,1)), source_failure TEXT,
                UNIQUE(bundle_id,role), UNIQUE(bundle_id,source_uri), UNIQUE(bundle_id,remote_name))""")
            db.version = 1
            val bundle = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO bundles(id, endpoint_id, endpoint_revision, revision, sealed) VALUES(?, 'nas', 0, 0, 1)", arrayOf(bundle))
            val video = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree) + "/OpenCineCam/A001/A001.mp4")
            db.execSQL("INSERT INTO artifacts(id, bundle_id, role, source_uri, source_name, remote_name, size_bytes, revision, state, " +
                "remote_may_exist) VALUES(?, ?, 'VIDEO', ?, 'A001.mp4', 'A001.mp4', 64, 0, ?, 0)",
                arrayOf(UUID.randomUUID().toString(), bundle, video.toString(), state))
        }
    }

    /** An uncommitted ([committed] 0) drive entry on [tree] in recovery journal [name], as a take or still group interrupted there leaves it. */
    private fun journalled(name: String, tree: Uri, committed: Int = 0) =
        SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, name), null).use { db ->
            if (db.version == 0) {
                db.execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY NOT NULL, committed INTEGER NOT NULL, tree TEXT, volume TEXT, folder TEXT)")
                db.version = 2
            }
            db.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, ?, ?, ?)",
                arrayOf(UUID.randomUUID().toString(), committed, tree.toString(), volumeIdOfTree(tree)))
        }

    private fun store() = RecordingDestinationStore(context,
        probe = { tree -> probed += tree; duringProbe?.let { duringProbe = null; it() }; probeResult },
        driveAttachHandler = { enabled -> attachHandler += enabled })

    private fun held(tree: Uri) = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    @Test
    fun freshStoreIsPhoneWithTheDefaultPolicy() {
        val store = store()
        assertEquals(RecordingDestination.Phone, store.configured())
        assertEquals(DriveDisconnectPolicy.STOP_AND_SAVE, store.policy())
        assertNull(store.label())
        assertEquals(ResolvedRecordingDestination(RecordingDestination.Phone, RecordingDestination.Phone, null), store.current(emptySet()))
    }

    @Test
    fun policyRoundTrips() {
        store().setPolicy(DriveDisconnectPolicy.CONTINUE_ON_PHONE)
        assertEquals(DriveDisconnectPolicy.CONTINUE_ON_PHONE, store().policy())
        context.getSharedPreferences(RecordingDestinationStore.PREFS, Context.MODE_PRIVATE).edit { putString(RecordingDestinationStore.KEY_POLICY, "BOGUS") }
        assertEquals(DriveDisconnectPolicy.STOP_AND_SAVE, store().policy())
    }

    @Test
    fun adoptedDriveRoundTripsAndResolves() {
        val adoption = store().adopt(DRIVE)
        assertEquals("FFF1-7571", (adoption as RecordingFolderAdoption.Adopted).volumeId)
        assertEquals(listOf(DRIVE), probed)
        assertTrue(held(DRIVE))

        val reopened = store()
        val configured = RecordingDestination.UsbFolder(DRIVE, "FFF1-7571")
        assertEquals(configured, reopened.configured())
        assertEquals(adoption.label, reopened.label())
        assertTrue(reopened.hasPermission())
        assertEquals(ResolvedRecordingDestination(configured, configured, null), reopened.current(setOf("fff1-7571")))
        assertEquals(DestinationFallback.DRIVE_NOT_CONNECTED, reopened.current(emptySet()).fallback)

        context.contentResolver.releasePersistableUriPermission(DRIVE, RecordingDestinationStore.READ_WRITE)
        assertEquals(ResolvedRecordingDestination(RecordingDestination.Phone, configured, DestinationFallback.PERMISSION_LOST),
            reopened.current(setOf("FFF1-7571")))
    }

    @Test
    fun driveSpeedIsKeptPerVolumeAcrossClearAndReadoption() {
        val store = store()
        assertNull(store.speed())
        store.adopt(DRIVE)
        val speed = DriveSpeed(412_000_000L, 380_000_000L, 1_700_000_000_000L)
        store.saveSpeed("fff1-7571", speed)
        assertEquals(speed, store().speed())
        assertEquals(speed, store().speed("FFF1-7571"))
        assertNull(store().speed("1A2B-3C4D"))

        store().clear()
        // Phone: no configured drive to report, but the drive's measurement stays for when it comes back.
        assertNull(store().speed())
        assertEquals(speed, store().speed("FFF1-7571"))
        store().adopt(DRIVE)
        assertEquals(speed, store().speed())

        context.getSharedPreferences(RecordingDestinationStore.PREFS, Context.MODE_PRIVATE)
            .edit { putString(RecordingDestinationStore.speedKey("FFF1-7571"), "garbage") }
        assertNull(store().speed())
    }

    @Test
    fun measuringNeverThrowsAndKeepsThePreviousSpeedOnFailure() {
        // No drive: nothing to measure.
        assertNull(store().measure())
        // Too little free space: skipped before touching the provider.
        assertNull(measureDriveSpeed(context.contentResolver, DRIVE, freeBytes = 1L))
        // Robolectric has no external storage provider, so the test file cannot be created: null, not a crash.
        assertNull(measureDriveSpeed(context.contentResolver, DRIVE, freeBytes = null))
        val store = store()
        store.adopt(DRIVE)
        val speed = DriveSpeed(412_000_000L, 380_000_000L, 1L)
        store.saveSpeed("FFF1-7571", speed)
        assertNull(store.measure())
        assertEquals(speed, store.speed())
    }

    @Test
    fun invalidTreesAreRejectedBeforeAnyPermissionOrProbe() {
        assertEquals(RecordingFolderAdoption.Rejected(RecordingFolderRejection.PHONE_STORAGE), store().adopt(tree("primary%3ADCIM")))
        assertEquals(RecordingFolderAdoption.Rejected(RecordingFolderRejection.NOT_A_DRIVE),
            store().adopt(Uri.parse("content://com.example.files/tree/FFF1-7571%3ARAW")))
        assertTrue(probed.isEmpty())
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        assertEquals(RecordingDestination.Phone, store().configured())
    }

    @Test
    fun failedProbeReleasesThePermissionAndKeepsThePreviousDestination() {
        store().adopt(DRIVE)
        probeResult = DriveProbeResult.FILE_SIZE_LIMIT
        assertEquals(RecordingFolderAdoption.Rejected(RecordingFolderRejection.FILE_SIZE_LIMIT), store().adopt(OTHER))
        assertFalse(held(OTHER))
        assertTrue(held(DRIVE))
        assertEquals(RecordingDestination.UsbFolder(DRIVE, "FFF1-7571"), store().configured())
    }

    @Test
    fun aProbeThatThrowsReleasesThePermissionAndKeepsThePreviousDestination() {
        store().adopt(DRIVE)
        duringProbe = { throw IllegalStateException("probe descriptor failed on close") }
        assertThrows(IllegalStateException::class.java) { store().adopt(OTHER) }
        assertFalse(held(OTHER))
        assertTrue(held(DRIVE))
        assertEquals(RecordingDestination.UsbFolder(DRIVE, "FFF1-7571"), store().configured())
        // The configured tree keeps its grant when its own new probe throws, and a later adoption still works.
        duringProbe = { throw IllegalStateException("probe descriptor failed on close") }
        assertThrows(IllegalStateException::class.java) { store().adopt(DRIVE) }
        assertTrue(held(DRIVE))
        assertEquals(RecordingFolderAdoption.Adopted::class.java, store().adopt(OTHER).javaClass)
    }

    @Test
    fun failedProbeOfTheConfiguredTreeKeepsItsPermission() {
        store().adopt(DRIVE)
        probeResult = DriveProbeResult.NOT_ENOUGH_SPACE
        assertEquals(RecordingFolderAdoption.Rejected(RecordingFolderRejection.NOT_ENOUGH_SPACE), store().adopt(DRIVE))
        assertTrue(held(DRIVE))
    }

    @Test
    fun aFailedProbeAfterALaterChoiceIsSuperseded() {
        store().adopt(DRIVE)
        probeResult = DriveProbeResult.FILE_SIZE_LIMIT
        duringProbe = { store().clear() }
        assertEquals(RecordingFolderAdoption.Superseded, store().adopt(OTHER))
        assertEquals(RecordingDestination.Phone, store().configured())
        assertFalse(held(OTHER))
    }

    @Test
    fun adoptingAnotherTreeReleasesThePreviousOne() {
        store().adopt(DRIVE)
        assertTrue(store().adopt(OTHER) is RecordingFolderAdoption.Adopted)
        assertFalse(held(DRIVE))
        assertTrue(held(OTHER))
        assertEquals(RecordingDestination.UsbFolder(OTHER, "1234-ABCD"), store().configured())
    }

    @Test
    fun previousTreeSharedWithTheCinemaDngExportIsKept() {
        store().adopt(DRIVE)
        context.getSharedPreferences(CINEMADNG_EXPORT_PREFS, Context.MODE_PRIVATE).edit { putString(CINEMADNG_EXPORT_TREE_KEY, DRIVE.toString()) }
        store().adopt(OTHER)
        assertTrue(held(DRIVE))
        store().clear()
        assertFalse(held(OTHER))
        assertEquals(RecordingDestination.Phone, store().configured())
        assertNull(store().label())
    }

    @Test
    fun aTreeWithAnInterruptedTakeJournalledOnItKeepsItsPermission() {
        store().adopt(DRIVE)
        // A take interrupted on the drive, deferred until it returns: recovery needs this grant then.
        journalled(RECORDING_JOURNAL, DRIVE)
        store().adopt(OTHER)
        assertTrue(held(DRIVE))
        // A receipt already committed needs nothing: switching away releases its tree.
        journalled(RECORDING_JOURNAL, OTHER, committed = 1)
        store().clear()
        assertFalse(held(OTHER))
        assertTrue(held(DRIVE))
    }

    @Test
    fun aDriveTakeStillToUploadKeepsItsTreeUntilItIsVerified() {
        store().adopt(DRIVE)
        // A committed take on the drive, queued for the WebDAV upload: the upload reads it through this grant.
        outboxed(DRIVE, "QUEUED")
        store().clear()
        assertTrue(held(DRIVE))
    }

    @Test
    fun aVerifiedUploadNoLongerKeepsItsTree() {
        store().adopt(DRIVE)
        outboxed(DRIVE, "VERIFIED")
        store().clear()
        assertFalse(held(DRIVE))
    }

    @Test
    fun anOutboxThatCannotBeReadKeepsTheTree() {
        store().adopt(DRIVE)
        context.getDatabasePath(WEBDAV_OUTBOX_DATABASE).also { it.parentFile!!.mkdirs() }.writeText("not a database")
        store().clear()
        assertTrue(held(DRIVE))
    }

    @Test
    fun aStillGroupKeepsItsTreeAndItsTwinUnderTheDrivesOtherName() {
        store().adopt(DRIVE)
        store().adopt(RENAMED)
        journalled(STILL_JOURNAL, DRIVE)
        store().clear()
        // The journalled tree, and the same folder under the other volume name, which recovery resolves through.
        assertTrue(held(DRIVE))
        assertTrue(held(RENAMED))
    }

    @Test
    fun clearGoesBackToPhoneAndReleases() {
        store().adopt(DRIVE)
        store().setPolicy(DriveDisconnectPolicy.CONTINUE_ON_PHONE)
        store().clear()
        assertEquals(RecordingDestination.Phone, store().configured())
        assertFalse(held(DRIVE))
        assertEquals(DriveDisconnectPolicy.CONTINUE_ON_PHONE, store().policy())
    }

    @Test
    fun aClearDuringTheProbeWinsOverTheAdoption() {
        store().adopt(DRIVE)
        // The operator left the page mid-probe, came back and chose the phone before the probe finished.
        duringProbe = { store().clear() }
        assertEquals(RecordingFolderAdoption.Superseded, store().adopt(OTHER))
        assertEquals(RecordingDestination.Phone, store().configured())
        assertFalse(held(OTHER))
        assertFalse(held(DRIVE))
        assertEquals(listOf(true, false), attachHandler)
    }

    @Test
    fun aLaterAdoptionDuringTheProbeWins() {
        duringProbe = { store().adopt(OTHER) }
        assertEquals(RecordingFolderAdoption.Superseded, store().adopt(DRIVE))
        assertEquals(RecordingDestination.UsbFolder(OTHER, "1234-ABCD"), store().configured())
        assertFalse(held(DRIVE))
        assertTrue(held(OTHER))
        // A superseded adoption of the tree the later one chose keeps its permission.
        duringProbe = { store().adopt(DRIVE) }
        assertEquals(RecordingFolderAdoption.Superseded, store().adopt(DRIVE))
        assertEquals(RecordingDestination.UsbFolder(DRIVE, "FFF1-7571"), store().configured())
        assertTrue(held(DRIVE))
    }

    @Test
    fun anOlderAdoptionOfTheSameTreeKeepsThePermissionTheNewerOneStillProbesWith() {
        fun pausedStore(inProbe: CountDownLatch, resume: CountDownLatch) = RecordingDestinationStore(context,
            probe = { inProbe.countDown(); check(resume.await(10, TimeUnit.SECONDS)); probeResult },
            driveAttachHandler = { enabled -> attachHandler += enabled })
        fun adoptOnThread(inProbe: CountDownLatch, resume: CountDownLatch): FutureTask<RecordingFolderAdoption> =
            FutureTask { pausedStore(inProbe, resume).adopt(DRIVE) }.also { Thread(it).start() }
        val olderInProbe = CountDownLatch(1); val olderResume = CountDownLatch(1)
        val newerInProbe = CountDownLatch(1); val newerResume = CountDownLatch(1)
        val older = adoptOnThread(olderInProbe, olderResume)
        assertTrue(olderInProbe.await(10, TimeUnit.SECONDS))
        val newer = adoptOnThread(newerInProbe, newerResume)
        assertTrue(newerInProbe.await(10, TimeUnit.SECONDS))
        // The older call finds itself superseded while the newer one has not stored the folder yet.
        olderResume.countDown()
        assertEquals(RecordingFolderAdoption.Superseded, older.get(10, TimeUnit.SECONDS))
        assertTrue(held(DRIVE))
        newerResume.countDown()
        assertTrue(newer.get(10, TimeUnit.SECONDS) is RecordingFolderAdoption.Adopted)
        assertEquals(RecordingDestination.UsbFolder(DRIVE, "FFF1-7571"), store().configured())
        assertTrue(held(DRIVE))
        // With no adoption left pending, a superseded one releases as before.
        duringProbe = { store().clear() }
        assertEquals(RecordingFolderAdoption.Superseded, store().adopt(OTHER))
        assertFalse(held(OTHER))
    }

    @Test
    fun aClearedStoreIsNotRevivedByAliasPromotion() {
        store().adopt(DRIVE)
        store().adopt(RENAMED)
        store().clear()
        assertEquals(ResolvedRecordingDestination(RecordingDestination.Phone, RecordingDestination.Phone, null), store().current(setOf("FFF1-7571")))
        assertEquals(RecordingDestination.Phone, store().configured())
        assertTrue(aliases().isEmpty())
    }

    @Test
    fun usesTreeCoversTheTreeAndItsAliases() {
        assertFalse(store().usesTree(DRIVE))
        store().adopt(DRIVE)
        store().adopt(RENAMED)
        assertTrue(store().usesTree(RENAMED))
        assertTrue(store().usesTree(DRIVE))
        assertFalse(store().usesTree(OTHER))
        store().clear()
        assertFalse(store().usesTree(RENAMED))
    }

    @Test
    fun adoptingADriveEnablesTheAttachHandler() {
        store().adopt(DRIVE)
        assertEquals(listOf(true), attachHandler)
    }

    @Test
    fun rejectedAdoptionsLeaveTheAttachHandlerAlone() {
        store().adopt(tree("primary%3ADCIM"))
        probeResult = DriveProbeResult.FILE_SIZE_LIMIT
        store().adopt(DRIVE)
        assertTrue(attachHandler.isEmpty())

        probeResult = DriveProbeResult.OK
        store().adopt(DRIVE)
        probeResult = DriveProbeResult.NOT_ENOUGH_SPACE
        store().adopt(OTHER)
        // The rejected tree changes nothing: the drive configured before stays, and so does the handler.
        assertEquals(listOf(true), attachHandler)
        assertEquals(RecordingDestination.UsbFolder(DRIVE, "FFF1-7571"), store().configured())
    }

    @Test
    fun clearingDisablesTheAttachHandler() {
        store().adopt(DRIVE)
        store().clear()
        assertEquals(listOf(true, false), attachHandler)
    }

    @Test
    fun syncReflectsTheConfiguredDestination() {
        store().syncDriveAttachHandler()
        store().adopt(DRIVE)
        attachHandler.clear()
        store().syncDriveAttachHandler()
        assertEquals(listOf(true), attachHandler)
        // A destination restored or written before the alias existed is picked up too.
        context.getSharedPreferences(RecordingDestinationStore.PREFS, Context.MODE_PRIVATE).edit { remove(RecordingDestinationStore.KEY_TREE) }
        store().syncDriveAttachHandler()
        assertEquals(listOf(true, false), attachHandler)
    }

    @Test
    fun theRealAttachHandlerTogglesTheManifestAlias() {
        val component = android.content.ComponentName(context.packageName, DRIVE_ATTACH_ALIAS)
        val packageManager = context.packageManager
        val real = RecordingDestinationStore(context, probe = { DriveProbeResult.OK })
        real.syncDriveAttachHandler()
        assertFalse(packageManager.getComponentEnabledSetting(component) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        real.adopt(DRIVE)
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, packageManager.getComponentEnabledSetting(component))
        real.clear()
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, packageManager.getComponentEnabledSetting(component))
    }

    @Test
    fun aRenamedDriveIsReportedUntilItsFolderIsChosenAgain() {
        store().adopt(DRIVE)
        val configured = RecordingDestination.UsbFolder(DRIVE, "FFF1-7571")
        assertEquals(ResolvedRecordingDestination(RecordingDestination.Phone, configured, DestinationFallback.DRIVE_RENAMED),
            store().current(setOf("FFF1-7571-01"), driveArriving = true))
        // Nothing to promote: the stored tree stays.
        assertEquals(configured, store().configured())
    }

    @Test
    fun adoptingTheSameDriveUnderItsNewNameKeepsTheOldTreeAsAnAlias() {
        store().adopt(DRIVE)
        assertTrue(store().adopt(RENAMED) is RecordingFolderAdoption.Adopted)
        val renamed = RecordingDestination.UsbFolder(RENAMED, "FFF1-7571-01")
        assertEquals(renamed, store().configured())
        assertTrue(held(DRIVE))
        assertTrue(held(RENAMED))
        assertEquals(setOf(DRIVE.toString()), aliases())
        assertEquals(ResolvedRecordingDestination(renamed, renamed, null), store().current(setOf("FFF1-7571-01")))
        assertEquals(renamed, store().configured())
    }

    @Test
    fun currentPromotesTheAliasWhenTheFirstNameMountsAgain() {
        store().adopt(DRIVE)
        store().adopt(RENAMED)
        val first = RecordingDestination.UsbFolder(DRIVE, "FFF1-7571")
        assertEquals(ResolvedRecordingDestination(first, first, null), store().current(setOf("fff1-7571")))
        // The swap is stored: every other caller sees the mounted tree, and the renamed one becomes the alias.
        assertEquals(first, store().configured())
        assertTrue(store().hasPermission())
        assertEquals(setOf(RENAMED.toString()), aliases())
        // And back again.
        val renamed = RecordingDestination.UsbFolder(RENAMED, "FFF1-7571-01")
        assertEquals(ResolvedRecordingDestination(renamed, renamed, null), store().current(setOf("FFF1-7571-01")))
        assertEquals(setOf(DRIVE.toString()), aliases())
        // Neither name mounted: no swap, just not connected.
        assertEquals(DestinationFallback.DRIVE_NOT_CONNECTED, store().current(emptySet()).fallback)
        assertEquals(renamed, store().configured())
    }

    @Test
    fun anAliasWhosePermissionWentIsDroppedQuietly() {
        store().adopt(DRIVE)
        store().adopt(RENAMED)
        context.contentResolver.releasePersistableUriPermission(DRIVE, RecordingDestinationStore.READ_WRITE)
        assertEquals(DestinationFallback.DRIVE_RENAMED, store().current(setOf("FFF1-7571")).fallback)
        assertEquals(RecordingDestination.UsbFolder(RENAMED, "FFF1-7571-01"), store().configured())
        assertTrue(aliases().isEmpty())
    }

    @Test
    fun adoptingUnderAnAliasNameReplacesThatAlias() {
        val firstNameElsewhere = tree("FFF1-7571%3AOCC")
        store().adopt(DRIVE)
        store().adopt(RENAMED)
        store().adopt(firstNameElsewhere)
        assertEquals(RecordingDestination.UsbFolder(firstNameElsewhere, "FFF1-7571"), store().configured())
        assertEquals(setOf(RENAMED.toString()), aliases())
        assertFalse(held(DRIVE))
        assertTrue(held(RENAMED))
        // Picking the alias's own tree again makes it the primary, not both.
        store().adopt(RENAMED)
        assertEquals(setOf(firstNameElsewhere.toString()), aliases())
        assertTrue(held(RENAMED))
    }

    @Test
    fun adoptingAnotherDriveReleasesTheTreeAndItsAliases() {
        store().adopt(DRIVE)
        store().adopt(RENAMED)
        store().adopt(OTHER)
        assertFalse(held(DRIVE))
        assertFalse(held(RENAMED))
        assertTrue(held(OTHER))
        assertTrue(aliases().isEmpty())
    }

    @Test
    fun clearReleasesTheTreeAndItsAliases() {
        store().adopt(DRIVE)
        store().adopt(RENAMED)
        store().clear()
        assertFalse(held(DRIVE))
        assertFalse(held(RENAMED))
        assertTrue(aliases().isEmpty())
        assertEquals(RecordingDestination.Phone, store().configured())
    }

    @Test
    fun anAliasSharedWithTheCinemaDngExportIsKeptOnClear() {
        store().adopt(DRIVE)
        store().adopt(RENAMED)
        context.getSharedPreferences(CINEMADNG_EXPORT_PREFS, Context.MODE_PRIVATE).edit { putString(CINEMADNG_EXPORT_TREE_KEY, DRIVE.toString()) }
        store().clear()
        assertTrue(held(DRIVE))
        assertFalse(held(RENAMED))
    }

    @Test
    fun driveSpeedSurvivesTheRename() {
        store().adopt(DRIVE)
        val speed = DriveSpeed(412_000_000L, 380_000_000L, 1L)
        store().saveSpeed("FFF1-7571", speed)
        store().adopt(RENAMED)
        assertEquals(speed, store().speed())
        assertEquals(speed, store().speed("fff1-7571-01"))
        assertEquals(RecordingDestinationStore.speedKey("FFF1-7571"), RecordingDestinationStore.speedKey("FFF1-7571-01"))
    }

    @Test
    fun keptAliasesAreOtherNamesOfTheSameDrive() {
        val candidates = setOf(DRIVE.toString(), RENAMED.toString(), OTHER.toString())
        assertEquals(setOf(DRIVE.toString()), keptDriveAliases("FFF1-7571-01", "FFF1-7571", candidates))
        assertEquals(setOf(RENAMED.toString()), keptDriveAliases("FFF1-7571", "FFF1-7571-01", candidates))
        assertTrue(keptDriveAliases("1234-ABCD", "FFF1-7571", candidates).isEmpty())
        assertTrue(keptDriveAliases("FFF1-7571", null, candidates).isEmpty())
    }

    private fun aliases(): Set<String> =
        context.getSharedPreferences(RecordingDestinationStore.PREFS, Context.MODE_PRIVATE).getStringSet(RecordingDestinationStore.KEY_ALIASES, null).orEmpty()

    private companion object {
        const val RECORDING_JOURNAL = "recording-recovery.sqlite"
        const val STILL_JOURNAL = "still-recovery.sqlite"
        fun tree(id: String): Uri = Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/tree/$id")
        val DRIVE = tree("FFF1-7571%3ARAW")
        /** The same drive, mounted by vold under its stable name after a mount was cut short. */
        val RENAMED = tree("FFF1-7571-01%3ARAW")
        val OTHER = tree("1234-ABCD%3A")
    }
}
