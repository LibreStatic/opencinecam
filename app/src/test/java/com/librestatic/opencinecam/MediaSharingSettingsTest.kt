/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.storage.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MediaSharingSettingsTest {
    private val keys = setOf("media-share-content", "media-share-metadata", "media-share-include-lut")
    private fun document(settings: CameraSettings = CameraSettings()) = Json.parseToJsonElement(
        CameraPresetCodec.encode(CameraPreset(name = "Share", settings = settings))).jsonObject
    @Test fun allEighteenSelectionsRoundTripWithoutChangingOtherPreferences() {
        assertEquals(MediaSharingSettings(), CameraSettingsStore(PresetPreferences()).load().mediaSharing)
        for (content in MediaShareContent.entries) for (metadata in MediaShareMetadata.entries) for (lut in listOf(false, true)) {
            val source = CameraSettings(mediaSharing = MediaSharingSettings(content, metadata, lut),
                productionSlate = ProductionSlateSettings(scene = "Keep"), audioInputDeviceId = 42)
            val store = CameraSettingsStore(PresetPreferences()); store.save(source)
            assertEquals(source, store.load())
            assertEquals(source.mediaSharing, CameraPresetCodec.decode(document(source).toString()).settings.mediaSharing)
        }
    }
    @Test fun versionEighteenHas160KeysAndFifteenRetains152WithThreeDefaults() {
        assertEquals(18, CameraPresetCodec.VERSION); assertEquals(160, CameraPresetCodec.portableKeys.size)
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
        val source = CameraSettings(gallery = GallerySettings(GalleryMediaKind.VIDEO, false, true))
        val root = document(source); val old = root.getValue("settings").jsonObject.filterKeys { !it.startsWith("capture-naming-") && !it.startsWith("playback-") } - keys
        assertEquals(152, old.size)
        assertEquals(source, CameraPresetCodec.decode(JsonObject(root + mapOf("version" to JsonPrimitive(15), "settings" to JsonObject(old))).toString()).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(15))).toString()) }
    }
    @Test fun corruptedPreferencesDefaultAndNoncanonicalPresetsReject() {
        for (values in listOf(mapOf("media-share-content" to "ALL"), mapOf("media-share-metadata" to "PRIVATE"),
            mapOf("media-share-include-lut" to "true"))) {
            assertEquals(MediaSharingSettings(), CameraSettingsStore(PresetPreferences(values)).load().mediaSharing)
        }
        val root = document()
        for ((key, value) in listOf("media-share-content" to JsonPrimitive("ALL"), "media-share-metadata" to JsonPrimitive("PRIVATE"),
            "media-share-include-lut" to JsonPrimitive("true"))) assertThrows(Exception::class.java) {
            CameraPresetCodec.decode(JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to value)))).toString())
        }
        for (key in keys) assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root +
            ("settings" to JsonObject(root.getValue("settings").jsonObject - key))).toString()) }
    }
    @Test fun sharingIsLiveButCaptureSlateAndInputStayFrozenAndFailedWritesDoNotPublish() {
        val before = CameraSettings(productionSlate = ProductionSlateSettings(scene = "Frozen"), audioInputDeviceId = 1)
        val next = before.copy(mediaSharing = MediaSharingSettings(MediaShareContent.ORIGINALS_ONLY),
            productionSlate = ProductionSlateSettings(scene = "Next"), audioInputDeviceId = 2)
        val live = before.withLivePreferencesFrom(next)
        assertEquals(next.mediaSharing, live.mediaSharing); assertEquals(before.productionSlate, live.productionSlate); assertEquals(1, live.audioInputDeviceId)
        val repository = SettingsRepository(object : SettingsPersistence {
            override fun load() = before
            override fun save(settings: CameraSettings) { error("write failure") }
        })
        assertThrows(IllegalStateException::class.java) { repository.set(next) }
        assertEquals(before, repository.states.value)
        assertTrue("media-sharing" in SettingsCatalog.search("compartir", SettingsCategory.CAPTURE) { "" })
    }
    @Test fun metadataEligibilityNeverSilentlyFallsBackForAnyContentOrRelationship() {
        val original = LocalMediaArtifact("content://media/external_primary/images/media/1", "image.jpg", "image/jpeg", 10, 1)
        for (status in LocalMediaRelationStatus.entries) for (content in MediaShareContent.entries) for (metadata in MediaShareMetadata.entries) for (slate in listOf(null, ProductionSlateSettings())) {
            val take = LocalMediaTake("one", original, listOf(original), emptyList(), LocalMediaKind.PHOTO, slate, status)
            assertEquals(content == MediaShareContent.ORIGINALS_ONLY || status == LocalMediaRelationStatus.DECLARED && (metadata == MediaShareMetadata.TECHNICAL || slate != null),
                canPrepareMediaShare(take, MediaSharingSettings(content, metadata)))
        }
    }
}
