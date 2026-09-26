/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxySourceHashCacheTest {
    @Test fun unchangedStampReusesHashAndChangedStampRehashes() = runBlocking {
        val cache = ProxySourceHashCache(); val computed = AtomicInteger()
        var stamp = ProxySourceStamp(100, 10, 1)
        suspend fun hash() = cache.hash("content://video/1", { stamp }) { "hash-${computed.incrementAndGet()}" }
        assertEquals("hash-1", hash()); assertEquals("hash-1", hash()); assertEquals(1, computed.get())
        stamp = stamp.copy(generation = 2) // Same size and second, but the provider saw a write.
        assertEquals("hash-2", hash()); assertEquals(2, computed.get())
        stamp = stamp.copy(sizeBytes = 101)
        assertEquals("hash-3", hash()); assertEquals("hash-3", hash()); assertEquals(3, computed.get())
    }

    @Test fun unknownOrMovingStampIsNeverCached() = runBlocking {
        val cache = ProxySourceHashCache(); val computed = AtomicInteger()
        repeat(2) { cache.hash("content://video/2", { null }) { "x${computed.incrementAndGet()}" } }
        assertEquals(2, computed.get())
        var generation = 0L
        // The source changes while it is being hashed: that digest must not be reused.
        repeat(2) { cache.hash("content://video/3", { ProxySourceStamp(1, 1, generation) }) { generation++; "y${computed.incrementAndGet()}" } }
        assertEquals(4, computed.get())
    }

    @Test fun capacityEvictsLeastRecentlyUsed() = runBlocking {
        val cache = ProxySourceHashCache(capacity = 2); val computed = AtomicInteger()
        val stamp = ProxySourceStamp(1, 1, 1)
        suspend fun hash(key: String) = cache.hash(key, { stamp }) { "$key-${computed.incrementAndGet()}" }
        hash("a"); hash("b"); hash("a"); hash("c") // Evicts b.
        assertEquals(3, computed.get())
        hash("a"); assertEquals(3, computed.get())
        hash("b"); assertEquals(4, computed.get())
    }
}
