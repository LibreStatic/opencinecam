/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.StillImageSaver
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProductionSlateLegacyDeviceTest {
    @Test fun legacyJpegKeepsActualBytesAndPublishesOnlyRealRelationshipFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val bytes = try { java.io.ByteArrayOutputStream().use { out -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 91, out)); out.toByteArray() } }
            finally { bitmap.recycle() }
        val slate = ProductionSlateSettings(project = "../Shoot \"Ñ\"", scene = "A/1", takeNumber = 4)
        var image: Uri? = null; var metadata: Uri? = null
        try {
            image = StillImageSaver(context).saveJpeg(bytes, slate)
            val path = requireNotNull(resolver.query(image, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
                assertTrue(it.moveToFirst());assertEquals(0, it.getInt(2));assertTrue(it.getString(1).startsWith("OCC_"));it.getString(0)
            }
            assertFalse(path.contains("Shoot"));assertTrue(path.startsWith("DCIM/OpenCineCam/OCC_"))
            assertArrayEquals(bytes, requireNotNull(resolver.openInputStream(image)).use { it.readBytes() })
            val root = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            metadata = requireNotNull(resolver.query(root, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
                arrayOf(path.replaceFirst("DCIM/", "Download/"), context.packageName), null)).use {
                assertEquals(1, it.count);assertTrue(it.moveToFirst());assertEquals(0, it.getInt(1));ContentUris.withAppendedId(root,it.getLong(0))
            }
            val json = Json.parseToJsonElement(requireNotNull(resolver.openInputStream(metadata)).use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
            assertEquals(slate, parseProductionSlateJson(json.getValue("productionSlate").jsonObject))
            assertFalse(json.containsKey("sensorTimestampNs"));assertFalse(json.containsKey("captureId"));assertFalse(json.containsKey("orientationDegrees"))
            val artifact = json.getValue("images").jsonArray.single().jsonObject
            assertEquals(image.toString(), artifact.getValue("uri").jsonPrimitive.content)
            assertEquals(bytes.size.toLong(), artifact.getValue("bytes").jsonPrimitive.long)
            val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            try { assertEquals(32, decoded.width);assertEquals(24, decoded.height) } finally { decoded.recycle() }
        } finally {
            metadata?.let { resolver.delete(it,null,null) };image?.let { resolver.delete(it,null,null) }
        }
    }
}
