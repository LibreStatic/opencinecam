/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class TorchControlTest {
    @Test fun absentFlashNeverEnables() {
        assertEquals(TorchRequest(false, null), TorchCapabilities(false).resolve(true, 4, false))
    }
    @Test fun fixedIntensityDoesNotInventStrengthSupport() {
        assertEquals(TorchRequest(true, null), TorchCapabilities(true).resolve(true, 4, false))
    }
    @Test fun defaultAndRequestedLevelsStayWithinHardwareRange() {
        val caps = TorchCapabilities(true, 5, 3)
        assertEquals(TorchRequest(true, 3), caps.resolve(true, null, false))
        assertEquals(TorchRequest(true, 5), caps.resolve(true, 7, false))
        assertEquals(TorchRequest(true, 1), caps.resolve(true, -1, false))
        assertEquals(TorchRequest(true, 2), caps.resolve(true, 2, false))
    }
    @Test fun highSpeedDoesNotReceiveUnqualifiedTorchRequests() {
        assertEquals(TorchRequest(false, null), TorchCapabilities(true, 5).resolve(true, 2, true))
    }
    @Test fun offClearsStrength() {
        assertEquals(TorchRequest(false, null), TorchCapabilities(true, 5).resolve(false, 2, false))
    }
}
