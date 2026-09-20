/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ReaderLeaseTest {
    @Test fun closingWaitsForCompleteBufferRead() {
        val closed = AtomicBoolean(false)
        val lease = ReaderLease(AutoCloseable { closed.set(true) })
        val entered = CountDownLatch(1)
        val continueRead = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val read = Thread {
            try { lease.read { entered.countDown(); check(continueRead.await(5, TimeUnit.SECONDS)); assertFalse(closed.get()) } }
            catch (problem: Throwable) { failure.set(problem) }
        }
        read.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        val close = Thread { closeStarted.countDown(); lease.close() }
        close.start(); assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        assertFalse(closed.get()); continueRead.countDown()
        read.join(5000); close.join(5000)
        assertFalse(read.isAlive); assertFalse(close.isAlive); assertNull(failure.get()); assertTrue(closed.get())
    }
    @Test fun queuedCallbackCannotAcquireAfterReaderClosure() {
        val lease = ReaderLease(AutoCloseable {})
        lease.close()
        assertNull(lease.read { fail("acquired a closed reader") })
    }
    @Test fun failedReadStillReleasesTheLeaseForClosure() {
        var closed = false
        val lease = ReaderLease(AutoCloseable { closed = true })
        assertThrows(IllegalStateException::class.java) { lease.read { error("invalid frame") } }
        lease.close(); assertTrue(closed)
    }
    @Test fun closureIsTerminalEvenWhenNativeCloseThrows() {
        var closes = 0
        val lease = ReaderLease(AutoCloseable { closes++; error("native close") })
        assertThrows(IllegalStateException::class.java) { lease.close() }
        lease.close(); assertEquals(1, closes)
        assertNull(lease.read { fail("read after failed close") })
    }
}
