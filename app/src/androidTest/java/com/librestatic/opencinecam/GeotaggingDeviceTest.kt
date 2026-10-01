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
        // addTestProvider needs the mock-location app-op; allowing it never restarts the process.
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} android:mock_location allow")
            .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { out -> out.readBytes() } } // Waits for the command.
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
        org.junit.Assume.assumeTrue("Needs location revoked (revoking would kill the process); run on a fresh install",locationPermission(context)==null)
        val before=repository.states.value;val runtime=CaptureLocations.get(context);val owner=Any()
        try {
            main {repository.update {it.copy(geotaggingEnabled=true)};runtime.setForeground(owner,true)}
            assertEquals(CaptureLocationStatus.NO_PERMISSION,runtime.snapshot()!!.status)
            assertNull(runtime.snapshot()!!.fix);assertNull(listener(runtime))
        } finally {main {runtime.setForeground(owner,false);repository.set(before)}}
    }

    @Test fun approximatePermissionRemainsUsableWithoutFinePermission() {
        org.junit.Assume.assumeTrue("Needs coarse-only location (revoking would kill the process): adb shell pm grant ${context.packageName} android.permission.ACCESS_COARSE_LOCATION on a fresh install, fine not granted",
            locationPermission(context)==LocationPermissionPrecision.APPROXIMATE)
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
