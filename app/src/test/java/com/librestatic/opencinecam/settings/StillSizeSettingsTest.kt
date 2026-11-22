/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.settings

import android.util.Size
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.camera.StillPhotoFormat
import com.librestatic.opencinecam.driver.DriverCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The still size picked in the RES slot survives a restart: per camera and format, else the last one picked anywhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StillSizeSettingsTest {
    // Camera "0" offers 12, 8 and 5 MP in 4:3 (JPEG) and 12 and 8 MP (HEIC); camera "1" offers 8 and 5 MP JPEG.
    private val back = DriverCamera.copy(heicSize = Size(4000, 3000), heicSizes = listOf(Size(4000, 3000), Size(3264, 2448)))
    private val front = DriverCamera.copy(cameraId = "1", jpegSize = Size(3264, 2448), jpegSizes = listOf(Size(3264, 2448), Size(2592, 1944)))

    private fun state(settings: CameraSettings, cameraId: String = "0", mode: CaptureMode = CaptureMode.PHOTO) = CameraUiState(
        phase = CameraUiPhase.PREVIEWING, selectedMode = mode, cameras = listOf(back, front), selectedCameraId = cameraId,
        effectiveSettings = settings,
    )

    @Test fun codecRoundTripsSortedAndCanonical() {
        val sizes = mapOf("1:JPEG" to (3264 to 2448), "0:HEIC" to (4000 to 3000), "0:JPEG" to (2592 to 1944))
        val text = StillSizesCodec.encode(sizes)
        assertEquals("0:HEIC=4000x3000,0:JPEG=2592x1944,1:JPEG=3264x2448", text)
        assertEquals(sizes, StillSizesCodec.decode(text))
        assertEquals(emptyMap<String, Pair<Int, Int>>(), StillSizesCodec.decode(null))
        assertEquals(emptyMap<String, Pair<Int, Int>>(), StillSizesCodec.decode(""))
        // Entries that could not be read back are not written.
        assertEquals("0:JPEG=10x10", StillSizesCodec.encode(mapOf("0:JPEG" to (10 to 10), "0:DNG" to (10 to 10), "a b:JPEG" to (10 to 10), "0:HEIC" to (0 to 10))))
    }

    @Test fun anyMalformedTextDecodesToNothing() {
        for (bad in listOf("0:JPEG", "0:JPEG=", "0:JPEG=4000", "0:JPEG=4000x", "0:JPEG=x3000", "0:JPEG=0x3000", "0:JPEG=4000x-1",
            "0:JPEG=040x30", "0:JPEG=40000x3000", "0:JPEG=4000x3000x1", "0:DNG=4000x3000", "0:jpeg=4000x3000", "0=4000x3000",
            ":JPEG=4000x3000", "a/b:JPEG=4000x3000", "0:JPEG=4000x3000,0:JPEG=3264x2448", "0:JPEG=4000x3000,", "0:JPEG=4000x3000=1",
            "0:JPEG=4000 x3000", "0:JPEG=4000x3000,garbage")) {
            assertEquals(bad, emptyMap<String, Pair<Int, Int>>(), StillSizesCodec.decode(bad))
        }
    }

    @Test fun theStoreKeepsTheSizesAndSanitisesTheGlobalOne() {
        val settings = CameraSettings(stillSizes = mapOf("0:JPEG" to (3264 to 2448)), photoWidth = 3264, photoHeight = 2448)
        val memory = PresetPreferences(); CameraSettingsStore(memory).save(settings)
        assertEquals("0:JPEG=3264x2448", memory.all["still-sizes"])
        assertEquals(settings, CameraSettingsStore(memory).load())
        for (stored in listOf(mapOf("photo-width" to 4000), mapOf("photo-height" to 3000), mapOf("photo-width" to -4000, "photo-height" to 3000),
            mapOf("photo-width" to 4000, "photo-height" to 0), mapOf("photo-width" to 40_000, "photo-height" to 30_000))) {
            val loaded = CameraSettingsStore(PresetPreferences(stored)).load()
            assertEquals(stored.toString(), 0 to 0, loaded.photoWidth to loaded.photoHeight)
        }
        assertTrue(CameraSettingsStore(PresetPreferences(mapOf("still-sizes" to "nonsense"))).load().stillSizes.isEmpty())
    }

    @Test fun theCameraAndFormatEntryWinsOverTheGlobalSize() {
        val settings = CameraSettings(stillSizes = mapOf("0:JPEG" to (2592 to 1944)), photoWidth = 3264, photoHeight = 2448)
        assertEquals(2592 to 1944, settings.stillSizeFor("0", StillPhotoFormat.JPEG))
        assertEquals(3264 to 2448, settings.stillSizeFor("0", StillPhotoFormat.HEIC))
        assertEquals(3264 to 2448, settings.stillSizeFor("1", StillPhotoFormat.JPEG))
        assertEquals(3264 to 2448, settings.stillSizeFor(null, StillPhotoFormat.JPEG))
        assertNull(CameraSettings(stillSizes = mapOf("1:JPEG" to (2592 to 1944))).stillSizeFor("0", StillPhotoFormat.JPEG))
        assertNull(CameraSettings().stillSizeFor("0", StillPhotoFormat.JPEG))
    }

    @Test fun theActiveSizeIsTheRememberedOneWhenOffered() {
        assertEquals(4000 to 3000, state(CameraSettings()).activeStillSize)
        assertEquals(3264 to 2448, state(CameraSettings(stillSizes = mapOf("0:JPEG" to (3264 to 2448)))).activeStillSize)
        assertEquals(2592 to 1944, state(CameraSettings(photoWidth = 2592, photoHeight = 1944)).activeStillSize)
        assertEquals("0:JPEG", state(CameraSettings()).stillSizeKey)
        assertEquals("0:HEIC", state(CameraSettings(photoFormat = StillPhotoFormat.HEIC)).stillSizeKey)
    }

    @Test fun aRememberedSizeTheCameraDoesNotOfferFallsBackToTheLargestAndIsKept() {
        // The front camera has no 12 MP: it runs its largest, and the setting still says 12 MP for the back camera.
        val settings = CameraSettings(stillSizes = mapOf("1:JPEG" to (4000 to 3000)), photoWidth = 4000, photoHeight = 3000)
        assertEquals(3264 to 2448, state(settings, cameraId = "1").activeStillSize)
        assertEquals(4000 to 3000, settings.stillSizeFor("1", StillPhotoFormat.JPEG))
        assertEquals(4000 to 3000, state(settings, cameraId = "0").activeStillSize)
    }

    @Test fun camerasAndFormatsResolveIndependently() {
        val settings = CameraSettings(stillSizes = mapOf("0:JPEG" to (2592 to 1944), "0:HEIC" to (3264 to 2448), "1:JPEG" to (2592 to 1944)))
        assertEquals(2592 to 1944, state(settings).activeStillSize)
        assertEquals(3264 to 2448, state(settings.copy(photoFormat = StillPhotoFormat.HEIC)).activeStillSize)
        assertEquals(2592 to 1944, state(settings, cameraId = "1").activeStillSize)
        val onlyBackJpeg = CameraSettings(stillSizes = mapOf("0:JPEG" to (2592 to 1944)))
        assertEquals(4000 to 3000, state(onlyBackJpeg.copy(photoFormat = StillPhotoFormat.HEIC)).activeStillSize)
        assertEquals(3264 to 2448, state(onlyBackJpeg, cameraId = "1").activeStillSize)
        // Burst lists JPEG sizes whatever the photo format, so it reads the JPEG entry.
        assertEquals(2592 to 1944, state(onlyBackJpeg.copy(photoFormat = StillPhotoFormat.HEIC), mode = CaptureMode.BURST).activeStillSize)
    }

    @Test fun rawPhotoKeepsItsFixedSize() {
        val settings = CameraSettings(photoWidth = 2592, photoHeight = 1944)
        assertEquals(4000 to 3000, state(settings, mode = CaptureMode.RAW_PHOTO).activeStillSize)
        assertNull(state(settings, mode = CaptureMode.RAW_PHOTO).stillSizeKey)
    }
}
