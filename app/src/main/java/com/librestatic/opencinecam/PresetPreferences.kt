/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.SharedPreferences

/** Isolated adapter reuses the canonical settings mapping without touching application preferences. */
internal class PresetPreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
    private val values = initial.toMutableMap().apply {
        put("audio-aac-log-migrated-v1", true)
        put("mode-selector-carousel-migrated-v1", true)
    }
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun contains(key: String?) = values.containsKey(key)
    override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue
    override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = error("Sets are not preset settings")
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val edits = mutableMapOf<String, Any?>()
        private var clear = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { edits[requireNotNull(key)] = value }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = error("Sets are not preset settings")
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean { if (clear) values.clear(); edits.forEach { (k,v) -> if (v == null) values.remove(k) else values[k] = v }; return true }
        override fun apply() { commit() }
    }
}
