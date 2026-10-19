/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import androidx.core.util.AtomicFile
import com.librestatic.opencinecam.camera.Main10FileCheck
import com.librestatic.opencinecam.camera.Main10FileCheckCodec
import com.librestatic.opencinecam.camera.Main10FileCheckRunner
import com.librestatic.opencinecam.camera.Main10FileCheckStore
import com.librestatic.opencinecam.camera.Main10TakeKind
import com.librestatic.opencinecam.playback.probeMain10FileSignal
import java.io.File
import java.util.concurrent.Executors

/** The latest Main10 file check in the app's private storage, replaced atomically. */
internal class AtomicFileMain10FileCheckStore(file: File) : Main10FileCheckStore {
    private val atomic = AtomicFile(file)

    override fun load(): Main10FileCheck? = runCatching {
        if (!atomic.baseFile.exists()) return null
        Main10FileCheckCodec.decode(atomic.readFully().toString(Charsets.UTF_8))
    }.getOrNull()

    override fun save(check: Main10FileCheck) {
        atomic.baseFile.parentFile?.mkdirs()
        val stream = atomic.startWrite()
        try {
            stream.write(Main10FileCheckCodec.encode(check).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
        }
    }

    companion object {
        fun forContext(context: Context) = AtomicFileMain10FileCheckStore(File(context.applicationContext.filesDir, "main10-file-check.json"))
    }
}

/**
 * Checks each published HLG or LOG take once, after publication, on a low-priority thread of its own. It outlives the
 * capture service, which stops as soon as the take is saved, and is evidence only: the take is never touched.
 */
internal object Main10FileCheckScheduler {
    private val executor by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "Main10FileCheck").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    fun schedule(context: Context, uri: String, kind: Main10TakeKind, takeName: String?) {
        val app = context.applicationContext
        Main10FileCheckRunner(AtomicFileMain10FileCheckStore.forContext(app), { probeMain10FileSignal(app, it) }, executor)
            .schedule(uri, kind, takeName)
    }
}
