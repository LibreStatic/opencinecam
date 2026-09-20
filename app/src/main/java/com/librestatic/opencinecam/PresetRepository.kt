/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

interface PresetPersistence { fun read(): String?; fun write(value: String) }
data class PresetLibrary(val presets: List<CameraPreset> = emptyList(), val slots: Map<String, String> = emptyMap(), val error: String? = null)

class PresetRepository(private val persistence: PresetPersistence) {
    private val mutable = MutableStateFlow(runCatching { load(persistence.read()) }.getOrElse { PresetLibrary(error = "Preset library could not be read; original data is retained") })
    val states = mutable.asStateFlow()

    @Synchronized fun save(preset: CameraPreset) {
        val old = mutable.value; check(old.error == null) { requireNotNull(old.error) }
        require(old.presets.none { it.id != preset.id && it.name.equals(preset.name, ignoreCase = true) }) { "A preset with this name already exists" }
        val portable = CameraPresetCodec.decode(CameraPresetCodec.encode(preset)).copy(id = preset.id)
        val items = old.presets.filterNot { it.id == preset.id } + portable
        require(items.size <= 32) { "The preset library holds up to 32 entries" }
        commit(old.copy(presets = items))
    }
    @Synchronized fun delete(id: String) {
        check(mutable.value.error == null)
        commit(mutable.value.copy(presets = mutable.value.presets.filterNot { it.id == id }, slots = mutable.value.slots.filterValues { it != id }))
    }
    @Synchronized fun assign(slot: String, id: String?) {
        check(mutable.value.error == null); require(slot in setOf("C1", "C2"))
        require(id == null || mutable.value.presets.any { it.id == id })
        commit(mutable.value.copy(slots = if (id == null) mutable.value.slots - slot else mutable.value.slots + (slot to id)))
    }
    /** Explicit UI confirmation is required before replacing unreadable local data. */
    @Synchronized fun reset() { commit(PresetLibrary()) }
    private fun commit(next: PresetLibrary) {
        val data = buildJsonObject {
            put("version", 1)
            put("presets", JsonArray(next.presets.map { buildJsonObject { put("id", it.id); put("preset", Json.parseToJsonElement(CameraPresetCodec.encode(it))) } }))
            put("slots", JsonObject(next.slots.mapValues { JsonPrimitive(it.value) }))
        }.toString()
        persistence.write(data)
        mutable.value = next
    }
    private fun load(raw: String?): PresetLibrary {
        if (raw == null) return PresetLibrary()
        require(raw.toByteArray().size <= 32 * CameraPresetCodec.MAX_BYTES + 16_384)
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root.keys == setOf("version", "presets", "slots") && root["version"]?.jsonPrimitive?.int == 1)
        val items = root.getValue("presets").jsonArray
        require(items.size <= 32)
        val presets = items.map {
            val item = it.jsonObject; require(item.keys == setOf("id", "preset"))
            val id = item.getValue("id").jsonPrimitive.content
            require(id.length in 1..64 && id.none(Char::isISOControl))
            CameraPresetCodec.decode(item.getValue("preset").toString()).copy(id = id)
        }
        require(presets.map { it.id }.distinct().size == presets.size)
        require(presets.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == presets.size)
        val slots = root.getValue("slots").jsonObject.mapValues { it.value.jsonPrimitive.content }
        require(slots.keys.all { it in setOf("C1", "C2") } && slots.values.all { id -> presets.any { it.id == id } })
        return PresetLibrary(presets, slots)
    }
}

object PresetRepositories {
    @Volatile private var instance: PresetRepository? = null
    fun get(context: Context): PresetRepository = instance ?: synchronized(this) {
        instance ?: PresetRepository(object : PresetPersistence {
            private val preferences = context.applicationContext.getSharedPreferences("camera-presets", Context.MODE_PRIVATE)
            override fun read(): String? = preferences.getString("library", null)
            override fun write(value: String) { check(preferences.edit().putString("library", value).commit()) { "Preset storage write failed" } }
        }).also { instance = it }
    }
}
