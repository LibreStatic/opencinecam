/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage
import org.junit.Assert.*
import org.junit.Test
class PendingOutputTransactionTest {
    @Test fun successIsPublishedOnceAndLaterCloseDoesNotDeleteIt() {
        val calls=mutableListOf<String>();val t=PendingOutputTransaction("uri",{calls+="close"},{calls+="delete"})
        assertEquals("uri",t.finish(true,{calls+="prepare"},{calls+="publish"}))
        assertEquals("uri",t.finish(false));assertEquals("uri",t.finish(true))
        assertEquals(listOf("close","prepare","publish"),calls)
    }
    @Test fun discardCannotBeResurrectedByALaterSuccessFlag() {
        var deleted=0;val t=PendingOutputTransaction("uri",{}, {deleted++})
        assertNull(t.finish(false));assertNull(t.finish(true,{fail("prepared")},{fail("published")}));assertEquals(1,deleted)
    }
    @Test fun closeFailureStillDeletesAndPreventsPublication() {
        var deleted=false;val problem=IllegalStateException("close");val t=PendingOutputTransaction("uri",{throw problem},{deleted=true})
        assertSame(problem,assertThrows(IllegalStateException::class.java) { t.finish(true,{fail("prepare")},{fail("publish")}) })
        assertTrue(deleted);assertNull(t.finish(true))
    }
    @Test fun preparationOrPublicationFailureDeletesAndStaysTerminal() {
        for(stage in listOf("prepare","publish")) {
            var deleted=0;val error=IllegalStateException(stage);val t=PendingOutputTransaction("uri",{}, {deleted++})
            assertSame(error,assertThrows(IllegalStateException::class.java) { t.finish(true,{if(stage=="prepare")throw error},{if(stage=="publish")throw error}) })
            assertEquals(1,deleted);assertNull(t.finish(true))
        }
    }
    @Test fun failedCleanupIsReportedWithoutLosingTheOriginalFailure() {
        val close=IllegalStateException("close");val delete=IllegalStateException("delete")
        val t=PendingOutputTransaction("uri",{throw close},{throw delete})
        assertSame(close,assertThrows(IllegalStateException::class.java) { t.finish(false) });assertArrayEquals(arrayOf(delete),close.suppressed)
    }
    @Test fun concurrentFinishCallsPublishOrDeleteOnlyOnce() {
        val closes=java.util.concurrent.atomic.AtomicInteger();val deletes=java.util.concurrent.atomic.AtomicInteger();val publishes=java.util.concurrent.atomic.AtomicInteger()
        val t=PendingOutputTransaction("uri",{closes.incrementAndGet()},{deletes.incrementAndGet()})
        val start=java.util.concurrent.CountDownLatch(1)
        val threads=(0..19).map { n -> Thread { start.await();t.finish(n%2==0,{}, {publishes.incrementAndGet()}) }.apply { start() } }
        start.countDown();threads.forEach { it.join() }
        assertEquals(1,closes.get());assertEquals(1,deletes.get()+publishes.get())
    }

    @Test fun splitPreparationClosesAndWritesOnceWithoutPublishing() {
        val calls = mutableListOf<String>()
        val t = PendingOutputTransaction("uri", { calls += "close" }, { calls += "delete" })
        assertEquals("uri", t.prepare { calls += "prepare" })
        assertEquals("uri", t.prepare { fail("prepared twice") })
        assertEquals(listOf("close", "prepare"), calls)
        assertEquals("uri", t.publish { calls += "publish" })
        assertEquals("uri", t.publish { fail("published twice") })
        assertEquals("uri", t.finish(false))
        assertEquals(listOf("close", "prepare", "publish"), calls)
    }

    @Test fun publicationBeforePreparationDoesNothingAndDoesNotClaimCompletion() {
        val calls = mutableListOf<String>()
        val t = PendingOutputTransaction("uri", { calls += "close" }, { calls += "delete" })
        assertNull(t.publish { fail("unprepared publication") })
        assertTrue(calls.isEmpty())
        assertEquals("uri", t.prepare { calls += "prepare" })
        assertEquals("uri", t.publish { calls += "publish" })
        assertEquals(listOf("close", "prepare", "publish"), calls)
    }

    @Test fun abortPreparedOutputDeletesWithoutClosingAgainOrResurrecting() {
        val calls = mutableListOf<String>()
        val t = PendingOutputTransaction("uri", { calls += "close" }, { calls += "delete" })
        t.prepare { calls += "prepare" }
        assertNull(t.finish(false))
        assertNull(t.prepare { fail("resurrected preparation") })
        assertNull(t.publish { fail("resurrected publication") })
        assertNull(t.finish(true))
        assertEquals(listOf("close", "prepare", "delete"), calls)
    }

    @Test fun splitPreparationFailureCleansAllRowsAndStaysTerminal() {
        val rows = mutableSetOf("video")
        val error = IllegalStateException("metadata write")
        val t = PendingOutputTransaction("uri", {}, { rows.clear() })
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            t.prepare { rows += "metadata"; throw error }
        })
        assertTrue(rows.isEmpty())
        assertNull(t.publish { fail("publish after failure") })
        assertNull(t.prepare { fail("prepare after failure") })
    }

    @Test fun splitPartialPublicationFailureDeletesPublishedAndPendingRows() {
        val rows = linkedMapOf("video" to "pending")
        val error = IllegalStateException("video publish")
        val t = PendingOutputTransaction("uri", {}, { rows.clear() })
        t.prepare { rows["metadata"] = "pending" }
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            t.publish { rows["metadata"] = "published"; throw error }
        })
        assertTrue(rows.isEmpty())
        assertNull(t.finish(true))
    }

    @Test fun reentrantAbortDuringSplitPreparationWaitsForTheWriterToReturn() {
        val calls = mutableListOf<String>()
        lateinit var t: PendingOutputTransaction<String>
        t = PendingOutputTransaction("uri", { calls += "close" }, { calls += "delete" })
        assertNull(t.prepare {
            calls += "write start"
            assertNull(t.finish(false))
            assertFalse(calls.contains("delete"))
            calls += "write end"
        })
        assertEquals(listOf("close", "write start", "write end", "delete"), calls)
        assertNull(t.publish { fail("aborted publication") })
    }

    @Test fun reentrantAbortWhileClosingSkipsTheSplitPreparationWriter() {
        val calls = mutableListOf<String>()
        lateinit var t: PendingOutputTransaction<String>
        t = PendingOutputTransaction("uri", {
            calls += "close"
            assertNull(t.finish(false))
            assertFalse(calls.contains("delete"))
        }, { calls += "delete" })
        assertNull(t.prepare { fail("write after abort") })
        assertEquals(listOf("close", "delete"), calls)
    }

    @Test fun legacyFirstCompletionOwnsOutcomeDespiteNestedAbortRequests() {
        val calls = mutableListOf<String>()
        lateinit var t: PendingOutputTransaction<String>
        t = PendingOutputTransaction("uri", {
            calls += "close"
            assertNull(t.finish(false))
        }, { fail("legacy completion was revoked") })
        assertEquals("uri", t.finish(true, prepare = {
            calls += "prepare"
            assertNull(t.finish(false))
            assertNull(t.finish(true, { fail("nested prepare") }, { fail("nested publish") }))
        }, publish = {
            calls += "publish"
            assertNull(t.finish(false))
        }))
        assertEquals(listOf("close", "prepare", "publish"), calls)
    }

    @Test fun reentrantSuccessCannotPublishAnUnfinishedPreparation() {
        val calls = mutableListOf<String>()
        lateinit var t: PendingOutputTransaction<String>
        t = PendingOutputTransaction("uri", {}, { fail("delete") })
        assertEquals("uri", t.prepare {
            assertNull(t.prepare { fail("nested preparation") })
            assertNull(t.publish { fail("publication while writing") })
            assertNull(t.finish(true, { fail("nested preparation") }, { fail("nested publication") }))
            calls += "write"
        })
        assertEquals(listOf("write"), calls)
        assertEquals("uri", t.publish { calls += "publish" })
        assertEquals(listOf("write", "publish"), calls)
    }

    @Test fun reentrantCallsDoNotObservePublishedResultInsidePublication() {
        lateinit var t: PendingOutputTransaction<String>
        t = PendingOutputTransaction("uri", {}, { fail("successful publication revoked") })
        t.prepare()
        assertEquals("uri", t.publish {
            assertNull(t.publish { fail("nested publish") })
            assertNull(t.prepare { fail("nested prepare") })
            assertNull(t.finish(false))
            assertNull(t.finish(true))
        })
        assertEquals("uri", t.finish(false))
    }

    @Test fun reentrantCleanupCannotResurrectAnAbortedOutput() {
        var deletions = 0
        lateinit var t: PendingOutputTransaction<String>
        t = PendingOutputTransaction("uri", {}, {
            deletions++
            assertNull(t.prepare { fail("cleanup preparation") })
            assertNull(t.publish { fail("cleanup publication") })
            assertNull(t.finish(true))
            assertNull(t.finish(false))
        })
        t.prepare()
        assertNull(t.finish(false))
        assertEquals(1, deletions)
    }

    @Test fun splitFailurePreservesOriginalAndCleanupFailuresOnce() {
        val publication = IllegalStateException("publication")
        val cleanup = IllegalStateException("cleanup")
        var deletions = 0
        val t = PendingOutputTransaction("uri", {}, { deletions++; throw cleanup })
        t.prepare()
        assertSame(publication, assertThrows(IllegalStateException::class.java) { t.publish { throw publication } })
        assertArrayEquals(arrayOf(cleanup), publication.suppressed)
        assertEquals(1, deletions)
        assertNull(t.finish(false))
    }

    @Test fun abortRequestedThenWriterFailureKeepsWriterFailureAndDeletesOnlyOnce() {
        var deletions = 0
        val error = IllegalStateException("write failed after abort request")
        lateinit var t: PendingOutputTransaction<String>
        t = PendingOutputTransaction("uri", {}, { deletions++ })
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            t.prepare { t.finish(false); throw error }
        })
        assertEquals(1, deletions)
        assertNull(t.finish(true))
    }

    @Test fun legacySuccessAfterSplitPreparationUsesTheFrozenPreparation() {
        val calls = mutableListOf<String>()
        val t = PendingOutputTransaction("uri", { calls += "close" }, { fail("delete") })
        t.prepare { calls += "original prepare" }
        assertEquals("uri", t.finish(true, { fail("replacement prepare") }, { calls += "publish" }))
        assertEquals(listOf("close", "original prepare", "publish"), calls)
    }

    @Test fun concurrentPreparationsOnlyCloseAndWriteOnce() {
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val t = PendingOutputTransaction("uri", { closes.incrementAndGet() }, { fail("delete") })
        concurrently(20) { assertEquals("uri", t.prepare { writes.incrementAndGet() }) }
        assertEquals(1, closes.get())
        assertEquals(1, writes.get())
        assertEquals("uri", t.publish())
    }

    @Test fun concurrentPublishAndAbortChooseOneFinalOutcomeAfterPreparation() {
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val publications = java.util.concurrent.atomic.AtomicInteger()
        val deletions = java.util.concurrent.atomic.AtomicInteger()
        val t = PendingOutputTransaction("uri", { closes.incrementAndGet() }, { deletions.incrementAndGet() })
        t.prepare()
        concurrently(20) { n ->
            if (n % 2 == 0) t.publish { publications.incrementAndGet() } else t.finish(false)
        }
        assertEquals(1, closes.get())
        assertEquals(1, publications.get() + deletions.get())
        if (publications.get() == 1) assertEquals("uri", t.publish()) else assertNull(t.publish())
    }

    @Test fun concurrentAbortWaitsUntilPreparationCallbackFinishes() {
        val writing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val abortStarted = java.util.concurrent.CountDownLatch(1)
        val abortFinished = java.util.concurrent.CountDownLatch(1)
        val deleted = java.util.concurrent.atomic.AtomicBoolean()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val t = PendingOutputTransaction("uri", {}, { deleted.set(true) })
        val writer = Thread {
            try { t.prepare { writing.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) } }
            catch (failure: Throwable) { errors += failure }
        }
        val aborter = Thread {
            try { abortStarted.countDown(); t.finish(false) }
            catch (failure: Throwable) { errors += failure }
            finally { abortFinished.countDown() }
        }
        writer.start()
        try {
            assertTrue(writing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            aborter.start()
            assertTrue(abortStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(abortFinished.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertFalse(deleted.get())
        } finally {
            release.countDown()
            writer.join(5000)
            if (aborter.state != Thread.State.NEW) aborter.join(5000)
        }
        assertFalse(writer.isAlive)
        assertFalse(aborter.isAlive)
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(deleted.get())
        assertNull(t.publish())
    }

    private fun concurrently(count: Int, action: (Int) -> Unit) {
        val start = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = (0 until count).map { index -> Thread {
            try { check(start.await(5, java.util.concurrent.TimeUnit.SECONDS)); action(index) }
            catch (failure: Throwable) { errors += failure }
        }.apply { start() } }
        start.countDown()
        workers.forEach { it.join(5000); assertFalse("Worker did not finish", it.isAlive) }
        assertTrue(errors.toString(), errors.isEmpty())
    }
}
