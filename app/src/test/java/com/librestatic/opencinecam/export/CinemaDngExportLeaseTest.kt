// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CinemaDngExportLeaseTest {
    private val authority = "com.android.externalstorage.documents"

    @Test fun aDriveTakeIsReadThroughItsPickedFolder() {
        val take = "content://$authority/tree/1A2B-3C4D%3ATakes/document/1A2B-3C4D%3ATakes%2FOpenCineCam%2FA001%2FA001.ocraw"
        assertEquals("content://$authority/tree/1A2B-3C4D%3ATakes", CinemaDngExportRuntime.sourceTree(take))
    }

    @Test fun aPhoneTakeNeedsNoFolder() {
        assertNull(CinemaDngExportRuntime.sourceTree("content://media/external/file/42"))
        assertNull(CinemaDngExportRuntime.sourceTree("content://$authority/document/1A2B-3C4D%3ATakes%2FA001.ocraw"))
    }
}
