/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProxyJobStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun folder() = temporary.newFolder()
    private fun job(index: Int = 1, kind: LocalMediaKind = LocalMediaKind.VIDEO): ProxyJob {
        val collection = when (kind) { LocalMediaKind.VIDEO -> "video"; LocalMediaKind.PHOTO -> "images"; LocalMediaKind.AUDIO -> "audio" }
        val mime = when (kind) { LocalMediaKind.VIDEO -> "video/mp4"; LocalMediaKind.PHOTO -> "image/jpeg"; LocalMediaKind.AUDIO -> "audio/flac" }
        val primary = LocalMediaArtifact("content://media/external_primary/$collection/media/$index", "Take ñ $index", mime, 999L + index, 123L + index)
        val metadata = LocalMediaArtifact("content://media/external_primary/downloads/$index", "Take $index.json", "application/json", 512, 123)
        return ProxyJob(UUID(0, index.toLong()).toString(), LocalMediaTake("take:${UUID(1, index.toLong())}", primary,
            listOf(primary), listOf(metadata), kind, ProductionSlateSettings(project = " Film ñ ", camera = "A", scene = "3", reel = "R", lens = "35mm",
                takeNumber = 999999, location = ProductionSlateLocation.EXTERIOR, timeOfDay = ProductionSlateTimeOfDay.NIGHT,
                goodTake = true, autoIncrementTake = true), LocalMediaRelationStatus.DECLARED), ProxySettings(), ProxyJobStatus.QUEUED)
    }

    @Test fun allStatusesKindsSettingsSlateAndArtifactsRoundTripExactly() {
        val dir = folder(); val store = ProxyJobStore(dir)
        val jobs = ProxyJobStatus.entries.mapIndexed { i, status -> job(i + 1, LocalMediaKind.entries[i % 3]).let { value ->
            value.copy(status = status, attempts = i, error = if (i == 0) null else "Observed\nerror ñ$i",
                settings = ProxySettings(listOf(640, 1280, 1920)[i % 3], listOf(1, 2, 3, 5, 8)[i % 5]))
        } }
        store.write(jobs)
        assertEquals(jobs, store.read()); assertEquals(jobs, ProxyJobStore(dir).read())
        val second = job(20).take.primary.copy(uri = "content://media/external_primary/video/media/21")
        val multi = job(20).let { it.copy(take = it.take.copy(originals = listOf(it.take.primary, second), slate = null,
            relationStatus = LocalMediaRelationStatus.INCOMPLETE)) }
        store.write(listOf(multi)); assertEquals(listOf(multi), store.read())
    }

    @Test fun missingDirectoryMeansEmptyButBackupIsNeverMistakenForEmpty() {
        val dir = File(folder(), "new"); val store = ProxyJobStore(dir)
        assertEquals(emptyList<ProxyJob>(), store.read()); assertFalse(dir.exists())
        val original = listOf(job()); store.write(original)
        assertTrue(File(dir, "jobs.json").renameTo(File(dir, "jobs.json.bak")))
        File(dir, "jobs.json.tmp").writeText("interrupted new bytes")
        assertEquals(original, store.read()); assertTrue(File(dir, "jobs.json").isFile)
        assertEquals(original, ProxyJobStore(dir).read())
    }

    @Test fun currentCommitWinsOverOldBackupAndInterruptedStaging() {
        val dir = folder(); val store = ProxyJobStore(dir)
        store.write(listOf(job())); val next = listOf(job(2)); store.write(next)
        File(dir, "jobs.json.tmp").writeText("unfinished")
        assertEquals(next, ProxyJobStore(dir).read())
        assertTrue(File(dir, "jobs.json").delete())
        assertEquals(listOf(job()), ProxyJobStore(dir).read())
    }

    @Test fun corruptMainRejectsEvenWithValidBackupAndWriteDoesNotResetIt() {
        val dir = folder(); val store = ProxyJobStore(dir)
        store.write(listOf(job())); store.write(listOf(job(2)))
        val file = File(dir, "jobs.json"); file.writeText("{corrupt")
        val backup = File(dir, "jobs.json.bak").readBytes()
        assertThrows(Exception::class.java) { store.read() }
        assertThrows(Exception::class.java) { store.write(listOf(job(3))) }
        assertEquals("{corrupt", file.readText()); assertArrayEquals(backup, File(dir, "jobs.json.bak").readBytes())
    }

    @Test fun corruptBackupAndOrphanFirstWriteFailExplicitly() {
        val dir = folder(); val store = ProxyJobStore(dir)
        File(dir, "jobs.json.bak").writeText("[]")
        assertThrows(Exception::class.java) { store.read() }
        assertTrue(File(dir, "jobs.json.bak").delete())
        File(dir, "jobs.json.tmp").writeText("interrupted first write")
        assertThrows(Exception::class.java) { store.read() }
        assertThrows(Exception::class.java) { store.write(emptyList()) }
    }

    @Test fun duplicateJobsOrTakesAndInvalidMembersPreservePreviousSnapshot() {
        val dir = folder(); val store = ProxyJobStore(dir); val value = job()
        store.write(listOf(value)); val prior = File(dir, "jobs.json").readBytes()
        val invalid = listOf(listOf(value, value.copy(take = job(2).take)),
            listOf(value, value.copy(id = UUID.randomUUID().toString())),
            listOf(value.copy(id = "noncanonical")), listOf(value.copy(attempts = -1)),
            listOf(value.copy(take = value.take.copy(primary = job(2).take.primary))),
            listOf(value.copy(take = value.take.copy(originals = value.take.originals + value.take.originals))),
            listOf(value.copy(take = value.take.copy(id = "take:INVALID"))))
        for (jobs in invalid) {
            assertThrows(Exception::class.java) { store.write(jobs) }
            assertArrayEquals(prior, File(dir, "jobs.json").readBytes())
            assertEquals(listOf(value), store.read())
        }
    }

    @Test fun jobCountAndUtf8ByteLimitsAreExactAndOversizeDiskReadRejects() {
        val dir = folder(); val store = ProxyJobStore(dir)
        val limit = (1..1024).map { job(it) }
        store.write(limit); assertEquals(limit, store.read())
        val prior = File(dir, "jobs.json").readBytes()
        assertThrows(Exception::class.java) { store.write(limit + job(1025)) }
        assertThrows(Exception::class.java) { store.write(limit.map { it.copy(error = "é".repeat(4096)) }) }
        assertArrayEquals(prior, File(dir, "jobs.json").readBytes())
        File(dir, "jobs.json").outputStream().use { it.write(ByteArray(ProxyJobStore.MAX_BYTES + 1)) }
        assertThrows(Exception::class.java) { store.read() }
    }

    @Test fun malformedJsonTypesEnumsUnknownFieldsDuplicateEscapesAndDepthReject() {
        val dir = folder(); val store = ProxyJobStore(dir); store.write(listOf(job()))
        val file = File(dir, "jobs.json"); val text = file.readText()
        for (bad in listOf(text.replace("\"version\":1", "\"version\":2"),
            text.replace("\"version\":1", "\"version\":\"1\""),
            text.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            text.replace("\"QUEUED\"", "\"UNKNOWN\""), text.replace("\"attempts\":0", "\"attempts\":true"),
            text.replace("\"maxLongEdge\":1280", "\"maxLongEdge\":641"),
            text.replace("\"version\":1", "\"version\":1,\"extra\":false"), "[".repeat(33) + "0" + "]".repeat(33))) {
            file.writeText(bad); assertThrows(Exception::class.java) { store.read() }
        }
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28)); assertThrows(Exception::class.java) { store.read() }
    }

    @Test fun blockedStagingAndSymlinkCannotDamagePreviousSnapshot() {
        val dir = folder(); val store = ProxyJobStore(dir); store.write(listOf(job()))
        val bytes = File(dir, "jobs.json").readBytes()
        val staging = File(dir, "jobs.json.tmp"); assertTrue(staging.mkdir())
        assertThrows(Exception::class.java) { store.write(listOf(job(2))) }
        assertArrayEquals(bytes, File(dir, "jobs.json").readBytes()); assertTrue(staging.delete())
        val other = File(folder(), "foreign").apply { writeText("untouched") }
        Files.createSymbolicLink(staging.toPath(), other.toPath())
        assertThrows(Exception::class.java) { store.write(listOf(job(2))) }
        assertEquals("untouched", other.readText()); assertArrayEquals(bytes, File(dir, "jobs.json").readBytes())
    }
}
