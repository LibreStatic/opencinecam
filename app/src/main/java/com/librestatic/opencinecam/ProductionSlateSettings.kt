/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/** Descriptive capture intent. A take number is not a unique artifact identity or a filename. */
data class ProductionSlateSettings(
    val project: String = "",
    val camera: String = "",
    val scene: String = "",
    val reel: String = "",
    val lens: String = "",
    val takeNumber: Int = 1,
    val location: ProductionSlateLocation = ProductionSlateLocation.UNSPECIFIED,
    val timeOfDay: ProductionSlateTimeOfDay = ProductionSlateTimeOfDay.UNSPECIFIED,
    val goodTake: Boolean = false,
    val autoIncrementTake: Boolean = false,
) {
    init {
        require(listOf(project, camera, scene, reel, lens).all(::validProductionSlateText)) { "Invalid slate text" }
        require(takeNumber in 1..999999) { "Take number must be between 1 and 999999" }
    }
}
enum class ProductionSlateLocation { UNSPECIFIED, INTERIOR, EXTERIOR }
enum class ProductionSlateTimeOfDay { UNSPECIFIED, DAY, NIGHT }

/** UTF-16 length is bounded; invalid surrogate pairs and control/format/line separators reject. */
internal fun validProductionSlateText(value: String): Boolean {
    if (value.length > 128) return false
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (Character.isLowSurrogate(char)) return false
        if (Character.isHighSurrogate(char) && (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1]))) return false
        val codePoint = Character.codePointAt(value, index)
        when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> return false
        }
        index += Character.charCount(codePoint)
    }
    return true
}
