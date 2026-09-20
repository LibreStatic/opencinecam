/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Closing a reader invalidates its acquired buffers: serialize closure with the complete read. */
internal class ReaderLease<T : AutoCloseable>(private val reader: T) : AutoCloseable {
    private var open = true
    @Synchronized fun <R> read(block: (T) -> R): R? = if (open) block(reader) else null
    @Synchronized override fun close() {
        if (!open) return
        open = false
        reader.close()
    }
}
