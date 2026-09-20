/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Bitmap
import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*

internal fun assertPhotoAspectImage(bitmap: Bitmap, image: JsonObject, selection: PhotoAspectSelection,
    kind: StillImageKind = StillImageKind.JPEG, rawWidth: Int? = null, rawHeight: Int? = null) {
    if (!selection.enabled) {
        // Default route may retain its legacy metadata/null report; no new pixel transform.
        return
    }
    val report = image.getValue("aspect").jsonObject
    val requested = report.getValue("requested").jsonObject
    assertTrue(requested.getValue("enabled").jsonPrimitive.boolean)
    assertEquals(selection.width, requested.getValue("width").jsonPrimitive.int)
    assertEquals(selection.height, requested.getValue("height").jsonPrimitive.int)
    val sourceWidth = report.getValue("sourceWidth").jsonPrimitive.int
    val sourceHeight = report.getValue("sourceHeight").jsonPrimitive.int
    val resultWidth = report.getValue("resultWidth").jsonPrimitive.int
    val resultHeight = report.getValue("resultHeight").jsonPrimitive.int
    val crop = report.getValue("crop").jsonObject
    if (kind == StillImageKind.DNG) {
        assertEquals("RAW_UNCHANGED", report.getValue("disposition").jsonPrimitive.content)
        assertEquals("NATIVE_IMAGE_PIXELS", report.getValue("coordinateSpace").jsonPrimitive.content)
        assertEquals(rawWidth, sourceWidth); assertEquals(rawHeight, sourceHeight)
        assertEquals(sourceWidth, resultWidth); assertEquals(sourceHeight, resultHeight)
        assertEquals(0, crop.getValue("left").jsonPrimitive.int); assertEquals(0, crop.getValue("top").jsonPrimitive.int)
        // Platform DNG BitmapFactory can return an embedded thumbnail, not native RAW dimensions.
    } else {
        assertEquals("APPLIED", report.getValue("disposition").jsonPrimitive.content)
        assertEquals("ORIENTED_IMAGE_PIXELS", report.getValue("coordinateSpace").jsonPrimitive.content)
        assertEquals(resultWidth, bitmap.width); assertEquals(resultHeight, bitmap.height)
        assertEquals(bitmap.width, image.getValue("width").jsonPrimitive.int)
        assertEquals(bitmap.height, image.getValue("height").jsonPrimitive.int)
        assertEquals(0, image.getValue("orientationDegrees").jsonPrimitive.int)
        assertEquals(0, report.getValue("outputOrientationDegrees").jsonPrimitive.int)
        assertEquals(resultWidth.toLong() * selection.height, resultHeight.toLong() * selection.width)
        val multiple = minOf(sourceWidth / selection.width, sourceHeight / selection.height)
        assertEquals(multiple * selection.width, resultWidth); assertEquals(multiple * selection.height, resultHeight)
        assertEquals((sourceWidth - resultWidth) / 2, crop.getValue("left").jsonPrimitive.int)
        assertEquals((sourceHeight - resultHeight) / 2, crop.getValue("top").jsonPrimitive.int)
    }
    assertEquals(resultWidth, crop.getValue("width").jsonPrimitive.int)
    assertEquals(resultHeight, crop.getValue("height").jsonPrimitive.int)
    android.util.Log.i("PhotoFormatProbe", "aspect=$report actualDecoded=${bitmap.width}x${bitmap.height} kind=$kind")
}
