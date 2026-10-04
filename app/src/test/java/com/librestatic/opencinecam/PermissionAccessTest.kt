package com.librestatic.opencinecam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAccessTest {
    @Test
    fun grantedIsNeverBlocked() {
        assertFalse(isPermissionBlocked(granted = true, showRationale = false, rationaleSeen = true))
    }

    @Test
    fun dismissingTheFirstPromptWithBackIsNotBlocked() {
        assertFalse(isPermissionBlocked(granted = false, showRationale = false, rationaleSeen = false))
    }

    @Test
    fun deniedOnceStillShowsThePrompt() {
        assertFalse(isPermissionBlocked(granted = false, showRationale = true, rationaleSeen = true))
    }

    @Test
    fun deniedAfterARationaleIsBlocked() {
        assertTrue(isPermissionBlocked(granted = false, showRationale = false, rationaleSeen = true))
    }
}
