/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.core.util.AtomicFile
import com.librestatic.opencinecam.camera.CachedCodecAudioReport
import com.librestatic.opencinecam.camera.CodecAudioReportCodec
import com.librestatic.opencinecam.camera.CodecAudioReportStore
import java.io.File

/** The codec and audio report in a file in the app's private storage, replaced atomically. */
internal class AtomicFileCodecAudioReportStore(file: File) : CodecAudioReportStore {
    private val atomic = AtomicFile(file)

    override fun load(): CachedCodecAudioReport? = runCatching {
        if (!atomic.baseFile.exists()) return null
        CodecAudioReportCodec.decode(atomic.readFully().toString(Charsets.UTF_8))
    }.getOrNull()

    override fun save(report: CachedCodecAudioReport) {
        atomic.baseFile.parentFile?.mkdirs()
        val stream = atomic.startWrite()
        try {
            stream.write(CodecAudioReportCodec.encode(report).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
        }
    }

    override fun delete() = atomic.delete()
}
