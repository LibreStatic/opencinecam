/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

/** Presentation proportions, not an allocation size. Rational SAR is retained until viewport fit. */
data class VideoDisplayGeometry(val width: Int, val height: Int)

/** MediaCodec crop edges are inclusive and refer to the unrotated coded raster. */
fun videoDisplayGeometry(
    codedWidth: Int, codedHeight: Int,
    cropLeft: Int = 0, cropTop: Int = 0, cropRight: Int = codedWidth - 1, cropBottom: Int = codedHeight - 1,
    sarWidth: Int = 1, sarHeight: Int = 1, rotation: Int = 0,
    legacyDisplayWidth: Int? = null, legacyDisplayHeight: Int? = null,
): VideoDisplayGeometry {
    require(codedWidth > 0 && codedHeight > 0) { "Invalid coded video dimensions" }
    require(cropLeft >= 0 && cropTop >= 0 && cropRight >= cropLeft && cropBottom >= cropTop &&
        cropRight < codedWidth && cropBottom < codedHeight) { "Video crop exceeds coded raster" }
    require(sarWidth > 0 && sarHeight > 0) { "Invalid video sample aspect ratio" }
    require(rotation in setOf(0, 90, 180, 270)) { "Unsupported video rotation" }
    require((legacyDisplayWidth == null) == (legacyDisplayHeight == null)) { "Incomplete legacy video display geometry" }
    if (legacyDisplayWidth != null && legacyDisplayHeight != null) {
        require(legacyDisplayWidth > 0 && legacyDisplayHeight > 0) { "Invalid legacy video display geometry" }
        require(sarWidth == 1 && sarHeight == 1) { "Legacy display ratio must not be combined with SAR" }
        // Legacy tkhd proportions already describe the visible display raster before rotation.
        // Coded crop was validated above; it must not multiply or crop this display ratio again.
        return if (rotation % 180 == 0) VideoDisplayGeometry(legacyDisplayWidth, legacyDisplayHeight)
            else VideoDisplayGeometry(legacyDisplayHeight, legacyDisplayWidth)
    }
    fun gcd(a: Long, b: Long): Long {
        var x = a; var y = b
        while (y != 0L) { val next = x % y; x = y; y = next }
        return x
    }
    val sarDivisor = gcd(sarWidth.toLong(), sarHeight.toLong())
    var width = (cropRight.toLong() - cropLeft + 1) * (sarWidth / sarDivisor)
    var height = (cropBottom.toLong() - cropTop + 1) * (sarHeight / sarDivisor)
    if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) {
        val divisor = gcd(width, height); width /= divisor; height /= divisor
    }
    require(width in 1..Int.MAX_VALUE.toLong() && height in 1..Int.MAX_VALUE.toLong()) { "Video display aspect exceeds representable bounds" }
    return if (rotation % 180 == 0) VideoDisplayGeometry(width.toInt(), height.toInt())
        else VideoDisplayGeometry(height.toInt(), width.toInt())
}
