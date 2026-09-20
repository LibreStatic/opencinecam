/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local device admission policy, independent from frozen encoder parameters and portable presets. */
internal class ProxyPolicies private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("proxy-policy", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val states = mutable.asStateFlow()
    private fun load(): ProxyPolicy = try {
        ProxyPolicy(prefs.getBoolean("charging", false), prefs.getInt("battery", 20), prefs.getInt("reserve-mib", 256))
    } catch (_: RuntimeException) { ProxyPolicy() }
    @Synchronized fun update(transform: (ProxyPolicy) -> ProxyPolicy) {
        val policy = transform(mutable.value)
        // Keep the boolean commit result: KTX edit(commit=true) returns Unit and would hide
        // persistence failure. The advisory UseKtx lint finding is retained, not suppressed.
        check(prefs.edit().putBoolean("charging", policy.requireCharging).putInt("battery", policy.minimumBatteryPercent)
            .putInt("reserve-mib", policy.reserveSpaceMiB).commit()) { "Proxy policy persistence failed" }
        mutable.value = policy
    }
    companion object {
        @Volatile private var instance: ProxyPolicies? = null
        fun get(context: Context): ProxyPolicies = instance ?: synchronized(this) {
            instance ?: ProxyPolicies(context.applicationContext).also { instance = it }
        }
    }
}
