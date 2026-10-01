/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.location.Location
import android.location.LocationListener
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/**
 * Provider cases that need PRECISE location. They live in their own class because a runtime grant cannot
 * be undone from inside the process (revoking kills it) and [GeotaggingDeviceTest] plus
 * [GeotaggingPermissionUiTest] require location to still be revoked. Class order is alphabetical, so
 * this class runs after both and may grant what it needs.
 */
class GeotaggingProviderDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val repository get()=SettingsRepositories.get(context)
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun listener(runtime:CaptureLocations)=CaptureLocations::class.java.getDeclaredField("listener").apply {isAccessible=true}.get(runtime) as? LocationListener
    private fun ensurePreciseLocation() {
        if(locationPermission(context)!=LocationPermissionPrecision.PRECISE) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_FINE_LOCATION)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        assertEquals(LocationPermissionPrecision.PRECISE,locationPermission(context))
    }

    @Test fun realProviderFreshnessOptOutAndOldCallbackAreFenced() {
        ensurePreciseLocation()
        val before=repository.states.value
        // A resumed Android Activity is necessary for OS while-in-use app-ops, not
        // merely this cache's ownership token. The debug test manifest supplies it.
        ActivityScenario.launch(ComponentActivity::class.java).use {
        GeotagTestProvider(context).use { provider ->
            try {
                main {repository.update {it.copy(geotaggingEnabled=false)}}
                provider.foreground(true)
                assertNull(provider.runtime.snapshot());assertNull(listener(provider.runtime))
                main {repository.update {it.copy(geotaggingEnabled=true)};provider.runtime.refreshAccess()}
                provider.publish(ageMillis=121_000)
                provider.waitFor {it?.status==CaptureLocationStatus.STALE}
                assertNull(provider.runtime.snapshot()!!.fix)
                provider.publish(latitude=13.25)
                val admitted=provider.waitFor {it?.status==CaptureLocationStatus.AVAILABLE}!!
                assertEquals(13.25,admitted.fix!!.latitude,0.0);assertEquals(-43.5,admitted.fix.longitude,0.0)
                val old=requireNotNull(listener(provider.runtime))
                main {repository.update {it.copy(geotaggingEnabled=false)};provider.runtime.refreshAccess()}
                assertNull(listener(provider.runtime));assertNull(provider.runtime.snapshot())
                main {old.onLocationChanged(Location("gps").apply {latitude=80.0;longitude=20.0;accuracy=1f;time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()})}
                assertNull(provider.runtime.snapshot())
                main {repository.update {it.copy(geotaggingEnabled=true)};provider.runtime.refreshAccess()}
                provider.waitFor {it?.status==CaptureLocationStatus.AVAILABLE}
                main {old.onLocationChanged(Location("gps").apply {latitude=80.0;longitude=20.0;accuracy=1f;time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()})}
                assertEquals(13.25,provider.runtime.snapshot()!!.fix!!.latitude,0.0)
                provider.foreground(false)
                assertEquals(CaptureLocationStatus.INACTIVE,provider.runtime.snapshot()!!.status)
                assertNull(listener(provider.runtime));assertNull(provider.runtime.snapshot()!!.fix)
                assertEquals(13.25,admitted.fix.latitude,0.0)
                android.util.Log.i("E16GeotagProbe","androidTestProvider=true freshAndStale=true optOutStops=true oldCallbacksFenced=true admittedSnapshotFrozen=true noBackgroundCollection=true")
            } finally {main {repository.set(before);provider.runtime.refreshAccess()}}
        }
        }
    }

    @Test fun providersOffThenOnRecoverWithoutChangingConsentOrResumedOwner() {
        ensurePreciseLocation()
        val before=repository.states.value
        // A resumed Android Activity is necessary for OS while-in-use app-ops, not
        // merely this cache's ownership token. The debug test manifest supplies it.
        ActivityScenario.launch(ComponentActivity::class.java).use {
        GeotagTestProvider(context).use { provider ->
            try {
                provider.enabled(false)
                main {repository.update {it.copy(geotaggingEnabled=true)}}
                provider.foreground(true)
                provider.waitFor {it?.status==CaptureLocationStatus.UNAVAILABLE}
                provider.enabled(true)
                val deadline=SystemClock.elapsedRealtime()+15_000
                while(provider.runtime.snapshot()?.status!=CaptureLocationStatus.AVAILABLE && SystemClock.elapsedRealtime()<deadline) {
                    provider.publish();Thread.sleep(1_100)
                }
                assertEquals(CaptureLocationStatus.AVAILABLE,provider.runtime.snapshot()!!.status)
                assertEquals(12.25,provider.runtime.snapshot()!!.fix!!.latitude,0.0)
                provider.enabled(false)
                provider.waitFor {it?.status==CaptureLocationStatus.UNAVAILABLE}
                assertNull(provider.runtime.snapshot()!!.fix)
                assertTrue(repository.states.value.geotaggingEnabled)
                provider.enabled(true)
                val resumedDeadline=SystemClock.elapsedRealtime()+15_000
                while(provider.runtime.snapshot()?.fix?.latitude!=14.25 && SystemClock.elapsedRealtime()<resumedDeadline) {
                    provider.publish(latitude=14.25);Thread.sleep(1_100)
                }
                assertEquals(14.25,provider.runtime.snapshot()!!.fix!!.latitude,0.0)
                android.util.Log.i("E16GeotagProbe","providersOffOnRecovery=true sameConsentAndOwner=true disabledCacheCleared=true")
            } finally {main {repository.set(before);provider.runtime.refreshAccess()}}
        }
        }
    }

    @Test fun mainActivityPauseResumeOwnsLocationCollection() {
        ensurePreciseLocation()
        val before=repository.states.value
        GeotagTestProvider(context).use { provider ->
            try {
                main {repository.update {it.copy(geotaggingEnabled=true,audioEnabled=false)}}
                ActivityScenario.launch(MainActivity::class.java).use { activity ->
                    provider.publish();provider.waitFor {it?.status==CaptureLocationStatus.AVAILABLE}
                    activity.moveToState(Lifecycle.State.CREATED)
                    assertEquals(CaptureLocationStatus.INACTIVE,provider.runtime.snapshot()!!.status)
                    assertNull(listener(provider.runtime))
                    activity.moveToState(Lifecycle.State.RESUMED)
                    // Respect the production10s request interval; repeated provider emissions
                    // are bounded input, not a faster production cadence chosen for a green test.
                    val deadline=SystemClock.elapsedRealtime()+15_000
                    while(provider.runtime.snapshot()?.fix?.latitude!=13.0 && SystemClock.elapsedRealtime()<deadline) {
                        provider.publish(latitude=13.0);Thread.sleep(1_100)
                    }
                    assertEquals(13.0,provider.runtime.snapshot()!!.fix!!.latitude,0.0)
                }
                assertEquals(CaptureLocationStatus.INACTIVE,provider.runtime.snapshot()!!.status)
                assertNull(listener(provider.runtime))
                android.util.Log.i("E16GeotagProbe","actualMainActivityLifecycle=true pausedStops=true resumedCollects=true destroyedStops=true")
            } finally {main {repository.set(before);provider.runtime.refreshAccess()}}
        }
    }
}
