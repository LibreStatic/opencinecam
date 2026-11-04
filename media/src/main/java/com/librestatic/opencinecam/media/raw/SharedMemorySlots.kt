// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.raw

import android.os.SharedMemory
import android.system.Os
import android.system.OsConstants
import java.nio.ByteBuffer

/**
 * Off-heap slot memory for [RawVideoRing]. A direct buffer on ART is a
 * non-movable Java array, so a ring of a gigabyte cannot come from the heap;
 * anonymous shared memory is mapped outside it. Pass [allocate] to the ring and
 * [close] once the ring is no longer used: the mappings are released here.
 *
 * Every page is written once here, so the kernel allocates and zeroes it now
 * rather than on the producer's first copy into it. Done during capture, that
 * zeroing (15 MB per full-sensor frame) made the Razr's HAL skip frames at
 * 60 fps for the whole first lap of the ring.
 */
class SharedMemorySlots : AutoCloseable {
    private val regions = ArrayList<Pair<SharedMemory, ByteBuffer>>()

    @Synchronized
    fun allocate(bytes: Int): ByteBuffer {
        val memory = SharedMemory.create("occ-raw-slot", bytes)
        val buffer = try {
            memory.mapReadWrite()
        } catch (error: Throwable) {
            memory.close()
            throw error
        }
        regions += memory to buffer
        prefault(buffer)
        return buffer
    }

    private fun prefault(buffer: ByteBuffer) {
        val page = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE).toInt() }.getOrDefault(0).takeIf { it > 0 } ?: 4096
        var offset = 0
        while (offset < buffer.capacity()) {
            buffer.put(offset, 0)
            offset += page
        }
    }

    val allocatedBytes: Long
        @Synchronized get() = regions.sumOf { it.second.capacity().toLong() }

    @Synchronized
    override fun close() {
        regions.forEach { (memory, buffer) ->
            SharedMemory.unmap(buffer)
            memory.close()
        }
        regions.clear()
    }
}
