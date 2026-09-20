/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Synthetic coordinates delivered by Android's real test provider. No physical GPS claim. */
internal class GeotagTestProvider(private val context:Context) : AutoCloseable {
    private val manager=context.getSystemService(LocationManager::class.java)
    private val token=Any()
    private val providers=mutableListOf<String>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    val runtime get()=CaptureLocations.get(context)
    init {
        try {
            val candidates=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER) +
                if(android.os.Build.VERSION.SDK_INT>=31) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
            for(provider in candidates) {
                @Suppress("DEPRECATION")
                manager.addTestProvider(provider,false,false,false,false,false,false,false,Criteria.POWER_LOW,Criteria.ACCURACY_FINE)
                providers+=provider;manager.setTestProviderEnabled(provider,true)
            }
        } catch(failure:Exception) {close();throw failure}
    }
    fun foreground(value:Boolean) = instrumentation.runOnMainSync {runtime.setForeground(token,value)}
    fun enabled(value:Boolean) {providers.forEach {manager.setTestProviderEnabled(it,value)}}
    fun publish(latitude:Double=12.25,longitude:Double=-43.5,ageMillis:Long=0) {
        for(provider in providers) manager.setTestProviderLocation(provider,Location(provider).apply {
            this.latitude=latitude;this.longitude=longitude;accuracy=5f
            time=System.currentTimeMillis()-ageMillis;elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()-ageMillis*1_000_000
        })
    }
    fun waitFor(predicate:(CaptureLocationSnapshot?)->Boolean):CaptureLocationSnapshot? {
        val end=SystemClock.elapsedRealtime()+15_000
        while(SystemClock.elapsedRealtime()<end) {val value=runtime.snapshot();if(predicate(value)) return value;Thread.sleep(20)}
        error("Location snapshot condition timed out: ${runtime.snapshot()?.status}")
    }
    override fun close() {
        foreground(false)
        providers.asReversed().forEach {manager.removeTestProvider(it)};providers.clear()
    }
}

class GeotaggingDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val repository get()=SettingsRepositories.get(context)
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun listener(runtime:CaptureLocations)=CaptureLocations::class.java.getDeclaredField("listener").apply {isAccessible=true}.get(runtime) as? LocationListener

    @Test fun optedInWithoutAndroidPermissionNeverRegistersAProviderOrStoresCoordinates() {
        assertNull("Runner must revoke both runtime permissions before this case",locationPermission(context))
        val before=repository.states.value;val runtime=CaptureLocations.get(context);val owner=Any()
        try {
            main {repository.update {it.copy(geotaggingEnabled=true)};runtime.setForeground(owner,true)}
            assertEquals(CaptureLocationStatus.NO_PERMISSION,runtime.snapshot()!!.status)
            assertNull(runtime.snapshot()!!.fix);assertNull(listener(runtime))
        } finally {main {runtime.setForeground(owner,false);repository.set(before)}}
    }

    @Test fun realProviderFreshnessOptOutAndOldCallbackAreFenced() {
        assertEquals(LocationPermissionPrecision.PRECISE,locationPermission(context))
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
        assertEquals(LocationPermissionPrecision.PRECISE,locationPermission(context))
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
        assertEquals(LocationPermissionPrecision.PRECISE,locationPermission(context))
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

    @Test fun approximatePermissionRemainsUsableWithoutFinePermission() {
        assertEquals(LocationPermissionPrecision.APPROXIMATE,locationPermission(context))
        val before=repository.states.value
        // A resumed Android Activity is necessary for OS while-in-use app-ops, not
        // merely this cache's ownership token. The debug test manifest supplies it.
        ActivityScenario.launch(ComponentActivity::class.java).use {
        GeotagTestProvider(context).use { provider ->
            try {
                main {repository.update {it.copy(geotaggingEnabled=true)}}
                provider.foreground(true);provider.publish()
                val fix=provider.waitFor {it?.status==CaptureLocationStatus.AVAILABLE}!!.fix!!
                assertEquals(LocationPermissionPrecision.APPROXIMATE,fix.permissionPrecision)
                assertTrue(fix.accuracyMeters>=0)
                android.util.Log.i("E16GeotagProbe","androidApproximatePermission=true finePermissionAbsent=true frameworkLocationDelivered=true accuracyReported=true")
            } finally {main {repository.set(before);provider.runtime.refreshAccess()}}
        }
        }
    }
}
