/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.FileInputStream
import java.io.InputStream

private const val MAX_PREPARED_FDINFO_BYTES = 4096

/** Borrows an already owned, open descriptor; never closes or changes that descriptor. */
internal fun requireReadOnlyPreparedDescriptor(descriptor: ParcelFileDescriptor) {
    check(descriptor.fileDescriptor.valid()) { "Prepared artifact descriptor is closed" }
    val fd = descriptor.fd
    check(fd >= 0) { "Prepared artifact descriptor is invalid" }
    // fdinfo is kernel-generated for this exact process descriptor. Reading it uses no
    // API-30-only fcntl method, hidden API, or writable duplicate of the artifact.
    val flags = FileInputStream("/proc/self/fdinfo/$fd").use(::readPreparedDescriptorFlags)
    check(descriptor.fileDescriptor.valid() && descriptor.fd == fd) { "Prepared artifact descriptor changed" }
    check((flags and OsConstants.O_ACCMODE.toLong()) == OsConstants.O_RDONLY.toLong()) {
        "Prepared artifact descriptor is not read-only"
    }
}

/** Reads at most the limit plus one sentinel byte. The caller owns [input]. */
internal fun readPreparedDescriptorFlags(input: InputStream): Long {
    val buffer = ByteArray(MAX_PREPARED_FDINFO_BYTES + 1)
    var size = 0
    while (size < buffer.size) {
        val count = input.read(buffer, size, buffer.size - size)
        if (count < 0) break
        check(count > 0) { "Prepared descriptor flags reader made no progress" }
        size += count
    }
    return parsePreparedDescriptorFlags(buffer.copyOf(size))
}

/** Strict, pure parser: exactly one canonical flags field containing unsigned octal digits. */
internal fun parsePreparedDescriptorFlags(bytes: ByteArray): Long {
    check(bytes.isNotEmpty() && bytes.size <= MAX_PREPARED_FDINFO_BYTES) { "Prepared descriptor flags data has invalid length" }
    check(bytes.all { byte ->
        val value = byte.toInt() and 0xff
        value == 9 || value == 10 || value in 32..126
    }) { "Prepared descriptor flags data is not ASCII text" }
    var flags: Long? = null
    for (line in bytes.toString(Charsets.US_ASCII).split('\n')) {
        if (!line.trimStart(' ', '\t').startsWith("flags")) continue
        check(flags == null) { "Prepared descriptor flags field is duplicated" }
        val match = requireNotNull(Regex("flags:[ \\t]+([0-7]+)").matchEntire(line)) {
            "Prepared descriptor flags field is malformed"
        }
        flags = requireNotNull(match.groupValues[1].toLongOrNull(8)) { "Prepared descriptor flags overflow" }
    }
    return checkNotNull(flags) { "Prepared descriptor flags field is missing" }
}
