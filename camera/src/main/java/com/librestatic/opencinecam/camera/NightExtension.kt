/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import kotlin.math.abs

/**
 * Output sizes (width to height) a camera's OEM night extension (CameraExtensionCharacteristics
 * EXTENSION_NIGHT) advertises. Present on a descriptor only when the extension is supported and
 * both the preview and JPEG outputs offer at least one size.
 */
data class NightExtensionCapability(
    val previewSizes: List<Pair<Int, Int>>,
    val jpegSizes: List<Pair<Int, Int>>,
) {
    init {
        require(previewSizes.isNotEmpty() && jpegSizes.isNotEmpty())
    }
}

/** The extension session's two outputs. */
data class NightExtensionSizes(val preview: Pair<Int, Int>, val jpeg: Pair<Int, Int>)

/** CameraExtensionCharacteristics.EXTENSION_NIGHT, without an Android dependency. */
const val NIGHT_EXTENSION_ID = 4

/** First platform level with CameraExtensionSession. */
const val NIGHT_EXTENSION_MIN_SDK = 31

/** Null unless [supportedExtensions] lists night and it offers a usable size for each output. */
fun nightExtensionCapability(
    supportedExtensions: Collection<Int>,
    previewSizes: List<Pair<Int, Int>>,
    jpegSizes: List<Pair<Int, Int>>,
): NightExtensionCapability? {
    if (NIGHT_EXTENSION_ID !in supportedExtensions) return null
    val preview = previewSizes.filter { (w, h) -> w > 0 && h > 0 }.distinct()
    val jpeg = jpegSizes.filter { (w, h) -> w > 0 && h > 0 }.distinct()
    if (preview.isEmpty() || jpeg.isEmpty()) return null
    return NightExtensionCapability(preview, jpeg)
}

/**
 * How the operator wants Night photo. OFF never offers or engages it. AUTO offers it from the
 * capture screen when the scene is dark and engages it only while the operator has switched it on
 * there. ON engages it for every photo wherever the camera allows it.
 */
enum class NightPhotoMode { OFF, AUTO, ON }

/**
 * Whether the operator's choice asks for the extension now. [transientEngaged] is the quick
 * toggle's session-only state; it counts in AUTO alone.
 */
fun nightPhotoRequested(mode: NightPhotoMode, transientEngaged: Boolean): Boolean = when (mode) {
    NightPhotoMode.OFF -> false
    NightPhotoMode.AUTO -> transientEngaged
    NightPhotoMode.ON -> true
}

/** True when the operator asked for Night and this device and camera can honour it. */
fun nightExtensionEngaged(requested: Boolean, sdkInt: Int, capability: NightExtensionCapability?): Boolean =
    requested && sdkInt >= NIGHT_EXTENSION_MIN_SDK && capability != null

/**
 * Picks the extension outputs. The JPEG is the largest advertised size in the aspect of
 * [referenceJpeg] (the camera's normal still), or the largest overall when that aspect is not
 * offered. The preview is [referencePreview] itself when the extension offers it, because the
 * viewfinder surface is already sized to it and extension sessions reject any other surface size.
 * Otherwise it prefers that size's aspect, then the JPEG's, and takes the size nearest 1080p within
 * it, as the regular preview does.
 */
fun NightExtensionCapability.selectSizes(
    referenceJpeg: Pair<Int, Int>?,
    referencePreview: Pair<Int, Int>?,
): NightExtensionSizes {
    fun area(size: Pair<Int, Int>) = size.first.toLong() * size.second
    fun aspect(size: Pair<Int, Int>) = maxOf(size.first, size.second).toDouble() / minOf(size.first, size.second)
    fun sameAspect(a: Pair<Int, Int>, b: Pair<Int, Int>) = abs(aspect(a) - aspect(b)) < ASPECT_TOLERANCE
    val matching = referenceJpeg?.let { reference -> jpegSizes.filter { sameAspect(it, reference) } }.orEmpty()
    val jpeg = (matching.ifEmpty { jpegSizes }).maxBy(::area)
    if (referencePreview != null && referencePreview in previewSizes) return NightExtensionSizes(referencePreview, jpeg)
    val previewPool = referencePreview?.let { reference -> previewSizes.filter { sameAspect(it, reference) } }.orEmpty()
        .ifEmpty { previewSizes.filter { sameAspect(it, jpeg) } }
        .ifEmpty { previewSizes }
    val bounded = previewPool.filter { area(it) <= PREVIEW_PIXELS }.ifEmpty { previewPool }
    val preview = bounded.minBy { abs(area(it) - PREVIEW_PIXELS) }
    return NightExtensionSizes(preview, jpeg)
}

private const val ASPECT_TOLERANCE = 0.01
private const val PREVIEW_PIXELS = 1920L * 1080L
