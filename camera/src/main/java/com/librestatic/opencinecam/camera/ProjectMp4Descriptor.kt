/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.system.Os
import java.io.FileDescriptor

/** Uses public positional FD APIs; does not own or close the caller descriptor. */
internal class ProjectMp4Descriptor(private val descriptor: FileDescriptor) : ProjectMp4File {
    override val size: Long get() = Os.fstat(descriptor).st_size
    override fun read(offset: Long, length: Int): ByteArray {
        val bytes=ByteArray(length); var done=0
        while (done < length) {
            val count=Os.pread(descriptor,bytes,done,length-done,offset+done)
            check(count>0) { "Truncated project MP4" }; done+=count
        }
        return bytes
    }
    override fun write(offset: Long, bytes: ByteArray) {
        var done=0
        while (done < bytes.size) {
            val count=Os.pwrite(descriptor,bytes,done,bytes.size-done,offset+done)
            check(count>0) { "Project MP4 write did not advance" }; done+=count
        }
    }
}
