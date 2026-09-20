/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class CaptureLocationTest {
    private val fix = CaptureLocationFix(12.25,-43.5,8f,1700000000000,LocationPermissionPrecision.PRECISE)
    private fun snapshot(enabled:Boolean=true,permission:LocationPermissionPrecision?=LocationPermissionPrecision.PRECISE,
        active:Boolean=true,value:CaptureLocationFix?=fix,at:Long=1000000000,now:Long=2000000000) =
        admittedCaptureLocation(enabled,permission,active,value,at,now)
    @Test fun disabledNeverProducesADeclarationEvenWithPermissionAndFix() { assertNull(snapshot(enabled=false)) }
    @Test fun permissionAndForegroundAreIndependentAndNoCoordinatesSurviveRejection() {
        val blocked=listOf(snapshot(permission=null)!!,snapshot(active=false)!!,snapshot(value=null)!!,
            snapshot(permission=LocationPermissionPrecision.APPROXIMATE)!!)
        assertEquals(listOf(CaptureLocationStatus.NO_PERMISSION,CaptureLocationStatus.INACTIVE,CaptureLocationStatus.UNAVAILABLE,CaptureLocationStatus.UNAVAILABLE),blocked.map {it.status})
        for(value in blocked) { assertNull(value.fix);assertEquals(setOf("schema","status"),captureLocationJson(value).keys) }
    }
    @Test fun freshnessUsesMonotonicTimeAndRejectsFutureInvalidOrStaleSamples() {
        assertEquals(120000L,snapshot(now=121000000000)!!.ageMillis)
        for(value in listOf(snapshot(now=121001000000)!!,snapshot(now=0)!!,snapshot(at=-1)!!)) {
            assertEquals(CaptureLocationStatus.STALE,value.status);assertNull(value.fix)
            assertEquals(setOf("schema","status"),captureLocationJson(value).keys)
        }
    }
    @Test fun admittedCopyDoesNotChangeWhenNextFixConsentOrPrecisionChanges() {
        val admitted=snapshot()!!;val json=captureLocationJson(admitted)
        assertEquals(1000L,admitted.ageMillis);assertEquals(fix,admitted.fix)
        snapshot(enabled=false);snapshot(value=fix.copy(latitude=10.0));snapshot(permission=null)
        assertEquals(json,captureLocationJson(admitted))
        val coarse=fix.copy(permissionPrecision=LocationPermissionPrecision.APPROXIMATE)
        assertEquals(coarse,snapshot(permission=LocationPermissionPrecision.APPROXIMATE,value=coarse)!!.fix)
    }
    @Test fun coordinatesAccuracyAndSnapshotStructureRejectInvalidValues() {
        for(bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,-90.01,90.01)) assertThrows(IllegalArgumentException::class.java) {fix.copy(latitude=bad)}
        for(bad in listOf(Double.NaN,Double.NEGATIVE_INFINITY,-180.01,180.01)) assertThrows(IllegalArgumentException::class.java) {fix.copy(longitude=bad)}
        for(bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f)) assertThrows(IllegalArgumentException::class.java) {fix.copy(accuracyMeters=bad)}
        assertThrows(IllegalArgumentException::class.java) {fix.copy(epochMillis=0)}
        assertThrows(IllegalArgumentException::class.java) {CaptureLocationSnapshot(CaptureLocationStatus.AVAILABLE)}
        assertThrows(IllegalArgumentException::class.java) {CaptureLocationSnapshot(CaptureLocationStatus.STALE,fix,0)}
        assertThrows(IllegalArgumentException::class.java) {CaptureLocationSnapshot(CaptureLocationStatus.AVAILABLE,fix,120001)}
    }
}
