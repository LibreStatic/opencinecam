/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Known signal at real source depth. FLAC24 is an immutable independently encoded asset. */
internal fun writeProxyDepthFixture(file: File, pcm16: ByteArray, flac: Boolean, type: ProxyPcmSampleType,
    rate: Int = 48000, channels: Int = 2): ByteArray {
    val raw = when (type) {
        ProxyPcmSampleType.S16_LE -> pcm16
        ProxyPcmSampleType.S24_LE -> ByteArray(pcm16.size / 2 * 3).also { bytes ->
            for (i in 0 until pcm16.size / 2) { bytes[i*3]=85; bytes[i*3+1]=pcm16[i*2]; bytes[i*3+2]=pcm16[i*2+1] }
        }
        ProxyPcmSampleType.F32_LE -> ByteBuffer.allocate(pcm16.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            val source = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
            while (source.hasRemaining()) putFloat(source.short / 32767f)
        }.array()
        else -> error("Unsupported fixture representation")
    }
    if (flac && type == ProxyPcmSampleType.S24_LE) {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()
        check(hash(pcm16) == "638f46cd150e488648b1112044e8ce386d68053146f9be7b8466f04823c250c0")
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("proxy-sidecar/pcm24.flac").use { it.readBytes() }
        check(hash(bytes) == "fe7ec1fcf18659cf2d7e989c5468cc8d9411695e19df815d634b6a5be8df999c")
        file.writeBytes(bytes)
    } else if (flac) {
        require(type == ProxyPcmSampleType.S16_LE)
        ProxyPcmProbeDeviceTest().encodeFlac(file, raw, rate, channels)
    } else {
        val header = createWavHeader(raw.size.toLong(), rate, type.bytes * 8, channels, type == ProxyPcmSampleType.F32_LE)
        val bytes = ByteArray(header.remaining()); header.get(bytes)
        file.writeBytes(bytes + raw)
    }
    return raw
}

/** Each instrumentation invocation owns an evidence namespace; repeat runs never overwrite bytes. */
internal fun proxyTestEvidencePrefix(): String =
    (InstrumentationRegistry.getArguments().getString("e17EvidenceRun") ?: "rev63-${java.util.UUID.randomUUID()}")
        .also { require(it.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
