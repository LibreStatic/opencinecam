/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.util.AtomicFile
import com.librestatic.opencinecam.camera.CubeLut
import com.librestatic.opencinecam.camera.LutSignalDomain
import com.librestatic.opencinecam.camera.LutTransformKind
import com.librestatic.opencinecam.camera.MonitorLut
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LutLibraryError { CORRUPT, STORAGE }
class LutLibraryEntry internal constructor(val hash: String, val name: String, val kind: LutTransformKind,
    val input: LutSignalDomain, val size: Int, val bytes: Int, domainMin: List<Float>, domainMax: List<Float>) {
    val domainMin: List<Float> = Collections.unmodifiableList(domainMin.toList())
    val domainMax: List<Float> = Collections.unmodifiableList(domainMax.toList())
}
class LutLibraryState internal constructor(entries: List<LutLibraryEntry> = emptyList(),
    val operatorHash: String? = null, val error: LutLibraryError? = null, val subjectHash: String? = null, val recordingHash: String? = null) {
    val entries: List<LutLibraryEntry> = Collections.unmodifiableList(entries.toList())
}

data class LutLibrarySelection(val operator: MonitorLut? = null, val subject: MonitorLut? = null, val recording: MonitorLut? = null, val available: Boolean = true)

/** Private originals, declarations and independent selections share one atomic commit. Calls other than states
 * and active()/activeSubject()/activeRecording()/activeSelections() perform bounded disk I/O and belong off the UI thread. No camera preset binding.
 * Corruption disables activation and preserves the file until explicit reset; no auto-purge.
 */
class LutLibrary internal constructor(private val storage: LutLibraryStorage) {
    internal constructor(context: Context) : this(AtomicLutLibraryStorage(File(context.noBackupFilesDir, "operator-lut-library.bin")))
    private data class Item(val name: String, val lut: MonitorLut, val original: ByteArray) {
        fun entry() = LutLibraryEntry(lut.cube.sha256, name, lut.kind, lut.input, lut.cube.size,
            original.size, lut.cube.domainMin.toList(), lut.cube.domainMax.toList())
    }
    private data class Snapshot(val items: List<Item>, val selected: String?, val subject: String? = null, val recording: String? = null)
    private var snapshot = Snapshot(emptyList(), null)
    private var committed: ByteArray? = null
    @Volatile private var cachedSelection = LutLibrarySelection()
    private val mutable = MutableStateFlow(LutLibraryState())
    val states: StateFlow<LutLibraryState> = mutable.asStateFlow()
    init {
        synchronized(processLock) {
            try {
                committed = storage.read()?.also { snapshot = decode(it) }
                publish()
            } catch (_: Exception) {
                cachedSelection = LutLibrarySelection(available = false)
                mutable.value = LutLibraryState(error = LutLibraryError.CORRUPT)
            }
        }
    }
    /** Lock-free: a main-thread observer never waits for a worker doing disk I/O. */
    fun active(): MonitorLut? = cachedSelection.operator
    fun activeSubject(): MonitorLut? = cachedSelection.subject
    fun activeRecording(): MonitorLut? = cachedSelection.recording
    /** All output intents from one publication, never a tuple assembled across commits. */
    fun activeSelections(): LutLibrarySelection = cachedSelection
    fun importLut(bytes: ByteArray, name: String, kind: LutTransformKind, input: LutSignalDomain): LutLibraryEntry = synchronized(processLock) {
        requireName(name)
        require(bytes.size <= CubeLut.MAX_BYTES)
        val original = bytes.copyOf()
        val item = Item(name, MonitorLut(CubeLut.parse(original), kind, input), original)
        requireHealthyDisk()
        snapshot.items.firstOrNull { it.lut.cube.sha256 == item.lut.cube.sha256 }?.let { old ->
            require(old.name == name && old.lut.kind == kind && old.lut.input == input) { "Hash already has a different declaration" }
            return@synchronized old.entry()
        }
        require(snapshot.items.size < MAX_ENTRIES) { "Library entry limit reached" }
        require(snapshot.items.sumOf { it.original.size.toLong() } + bytes.size <= MAX_RAW_BYTES) { "Library byte limit reached" }
        commit(snapshot.copy(items = snapshot.items + item))
        item.entry()
    }
    fun export(hash: String): ByteArray = synchronized(processLock) {
        requireHealthyDisk()
        requireNotNull(snapshot.items.firstOrNull { it.lut.cube.sha256 == hash }) { "Unknown LUT" }.original.copyOf()
    }
    fun select(hash: String?) = synchronized(processLock) {
        requireHealthyDisk()
        require(hash == null || snapshot.items.any { it.lut.cube.sha256 == hash }) { "Unknown LUT" }
        if (snapshot.selected != hash) commit(snapshot.copy(selected = hash))
    }
    fun selectSubject(hash: String?) = synchronized(processLock) {
        requireHealthyDisk()
        require(hash == null || snapshot.items.any { it.lut.cube.sha256 == hash }) { "Unknown LUT" }
        if (snapshot.subject != hash) commit(snapshot.copy(subject = hash))
    }
    /** Explicit file intent. UI callers must obtain irreversible-pixel confirmation before selection;
     * the capture owner independently freezes this value at take admission. No monitor inheritance.
     */
    fun selectRecording(hash: String?) = synchronized(processLock) {
        requireHealthyDisk()
        require(hash == null || snapshot.items.any { it.lut.cube.sha256 == hash }) { "Unknown LUT" }
        if (snapshot.recording != hash) commit(snapshot.copy(recording = hash))
    }
    fun delete(hash: String) = synchronized(processLock) {
        requireHealthyDisk()
        require(snapshot.items.any { it.lut.cube.sha256 == hash }) { "Unknown LUT" }
        commit(Snapshot(snapshot.items.filterNot { it.lut.cube.sha256 == hash }, snapshot.selected?.takeUnless { it == hash },
            snapshot.subject?.takeUnless { it == hash }, snapshot.recording?.takeUnless { it == hash }))
    }
    /** The sole recovery action that replaces retained corrupt bytes; caller must confirm it. */
    fun reset() = synchronized(processLock) { commit(Snapshot(emptyList(), null)) }

    private fun publish() {
        cachedSelection = LutLibrarySelection(snapshot.items.firstOrNull { it.lut.cube.sha256 == snapshot.selected }?.lut,
            snapshot.items.firstOrNull { it.lut.cube.sha256 == snapshot.subject }?.lut,
            snapshot.items.firstOrNull { it.lut.cube.sha256 == snapshot.recording }?.lut)
        mutable.value = LutLibraryState(snapshot.items.map(Item::entry), snapshot.selected, subjectHash = snapshot.subject, recordingHash = snapshot.recording)
    }
    private fun requireHealthyDisk() {
        check(mutable.value.error == null) { "Library needs explicit recovery" }
        try {
            val current = storage.read()
            check(if (committed == null) current == null else current != null && requireNotNull(committed).contentEquals(current)) {
                "Library changed on disk; reopen required"
            }
        } catch (failure: Exception) {
            cachedSelection = LutLibrarySelection(available = false)
            mutable.value = LutLibraryState(mutable.value.entries, snapshot.selected, LutLibraryError.CORRUPT, snapshot.subject, snapshot.recording)
            throw failure
        }
    }
    private fun commit(next: Snapshot) {
        val encoded = encode(next)
        try { storage.write(encoded) }
        catch (failure: Exception) {
            cachedSelection = LutLibrarySelection(available = false)
            mutable.value = LutLibraryState(mutable.value.entries, snapshot.selected, LutLibraryError.STORAGE, snapshot.subject, snapshot.recording)
            throw failure
        }
        committed = encoded
        snapshot = next
        publish()
    }
    private fun encode(value: Snapshot): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(value.items.size)
            out.writeBoolean(value.selected != null); value.selected?.let(out::writeUTF)
            out.writeBoolean(value.subject != null); value.subject?.let(out::writeUTF)
            out.writeBoolean(value.recording != null); value.recording?.let(out::writeUTF)
            for (item in value.items) {
                out.writeUTF(item.name); out.writeUTF(item.lut.kind.name); out.writeUTF(item.lut.input.name)
                out.writeUTF(item.lut.cube.sha256)
                val raw = item.original; out.writeInt(raw.size); out.write(raw)
            }
        }
    }.toByteArray().also { require(it.size <= MAX_FILE_BYTES) }
    private fun decode(bytes: ByteArray): Snapshot {
        require(bytes.size <= MAX_FILE_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val version = input.readInt(); require(version in 1..VERSION)
            val count = input.readInt(); require(count in 0..MAX_ENTRIES)
            val selectedFlag = input.readUnsignedByte(); require(selectedFlag in 0..1)
            val selected = if (selectedFlag == 1) input.readUTF().also(::requireHash) else null
            val subject = if (version >= 2) {
                val flag = input.readUnsignedByte(); require(flag in 0..1)
                if (flag == 1) input.readUTF().also(::requireHash) else null
            } else null // v1 is read without rewriting; subject preview starts unselected.
            val recording = if (version >= 3) {
                val flag = input.readUnsignedByte(); require(flag in 0..1)
                if (flag == 1) input.readUTF().also(::requireHash) else null
            } else null // Migrating either earlier version never opts into altered file pixels.
            val items = ArrayList<Item>(count)
            var total = 0L
            repeat(count) {
                val name = input.readUTF().also(::requireName)
                val kind = LutTransformKind.valueOf(input.readUTF())
                val domain = LutSignalDomain.valueOf(input.readUTF())
                val hash = input.readUTF().also(::requireHash)
                val length = input.readInt(); require(length in 1..CubeLut.MAX_BYTES)
                total += length; require(total <= MAX_RAW_BYTES)
                require(length <= input.available())
                val raw = ByteArray(length); input.readFully(raw)
                val cube = CubeLut.parse(raw); require(cube.sha256 == hash)
                require(items.none { it.lut.cube.sha256 == hash })
                items += Item(name, MonitorLut(cube, kind, domain), raw)
            }
            require(input.read() == -1)
            require(selected == null || items.any { it.lut.cube.sha256 == selected })
            require(subject == null || items.any { it.lut.cube.sha256 == subject })
            require(recording == null || items.any { it.lut.cube.sha256 == recording })
            Snapshot(items, selected, subject, recording)
        }
    }
    companion object {
        const val MAX_ENTRIES = 8
        const val MAX_RAW_BYTES = 16 * 1024 * 1024
        internal const val MAX_FILE_BYTES = MAX_RAW_BYTES + 16 * 1024
        private const val MAGIC = 0x4c555442
        private const val VERSION = 3
        private val processLock = Any()
        internal fun requireName(value: String) { require(value.isNotBlank() && value.length <= 120 && value.none { it.isISOControl() }) }
        private fun requireHash(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
    }
}

internal interface LutLibraryStorage {
    fun read(): ByteArray?
    /** Must either durably replace the complete file or leave the previous file recoverable. */
    fun write(bytes: ByteArray)
}
private class AtomicLutLibraryStorage(private val file: File) : LutLibraryStorage {
    private val atomic = AtomicFile(file)
    override fun read(): ByteArray? {
        if (!file.exists() && !File(file.path + ".bak").exists()) {
            if (File(file.path + ".new").exists()) throw IOException("Incomplete LUT library")
            return null
        }
        return atomic.openRead().use { input ->
            val buffer = ByteArray(LutLibrary.MAX_FILE_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val n = input.read(buffer, size, buffer.size - size)
                if (n < 0) break
                check(n > 0); size += n
            }
            require(size <= LutLibrary.MAX_FILE_BYTES)
            buffer.copyOf(size)
        }
    }
    override fun write(bytes: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw IOException("LUT directory unavailable")
            stream = atomic.startWrite(); stream.write(bytes); stream.flush(); stream.fd.sync()
            atomic.finishWrite(stream); stream = null
            check(read()?.contentEquals(bytes) == true) { "LUT commit readback failed" }
        } catch (failure: Exception) {
            try { stream?.let(atomic::failWrite) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
object LutLibraries {
    @Volatile private var instance: LutLibrary? = null
    fun get(context: Context): LutLibrary = instance ?: synchronized(this) {
        instance ?: LutLibrary(context.applicationContext).also { instance = it }
    }
}
