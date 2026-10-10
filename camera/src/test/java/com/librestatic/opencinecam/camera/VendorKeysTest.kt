package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class VendorKeysTest {
    private val key = "org.codeaurora.qcamera3.sharpness.strength"
    private val gate = "org.codeaurora.qcamera3.sharpness.range"

    @Test fun advertisedKeyWins() {
        assertEquals(VendorKeyResolution.ADVERTISED, resolveVendorKey(key, gate, setOf(key), setOf(gate), 36))
        assertEquals(VendorKeyResolution.ADVERTISED, resolveVendorKey(key, gate, setOf(key), emptySet(), 26))
    }

    @Test fun unadvertisedKeyIsResolvedByNameOnlyWithTheGateKey() {
        assertEquals(VendorKeyResolution.BY_NAME, resolveVendorKey(key, gate, emptySet(), setOf(gate), 29))
        assertEquals(VendorKeyResolution.BY_NAME, resolveVendorKey(key, gate, emptySet(), setOf(gate), 36))
    }

    @Test fun noGateKeyMeansAbsent() {
        assertEquals(VendorKeyResolution.ABSENT, resolveVendorKey(key, gate, emptySet(), setOf("other"), 36))
        assertEquals(VendorKeyResolution.ABSENT, resolveVendorKey(key, gate, emptySet(), emptySet(), 36))
    }

    @Test fun belowApi29OnlyAdvertisedKeysCount() {
        assertEquals(VendorKeyResolution.ABSENT, resolveVendorKey(key, gate, emptySet(), setOf(gate), 28))
    }
}
