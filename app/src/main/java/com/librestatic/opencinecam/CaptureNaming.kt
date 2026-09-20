/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** A display-name convention, never a directory, URI identity or remote retarget instruction. */
data class CaptureNamingSettings(
    val enabled: Boolean = false,
    val template: String = "{project}_{scene}_T{take}",
) {
    init { validateCaptureNameTemplate(template) }
}

/** Frozen at admission, before preparation/capture; the same instant and slate name every member. */
data class CaptureNameSnapshot(
    val settings: CaptureNamingSettings,
    val slate: ProductionSlateSettings,
    val epochMillis: Long,
    val captureLocation: CaptureLocationSnapshot? = null,
) {
    init { require(epochMillis in 0L..253402300799999L) }
}

private val captureNameTokens = setOf("project", "camera", "scene", "take", "reel", "date", "time")
private val captureNameToken = Regex("\\{([a-z]+)\\}")

fun validateCaptureNameTemplate(template: String): String {
    require(template.length in 1..128 && template.isNotBlank() && Charsets.UTF_8.newEncoder().canEncode(template)) {
        "Filename template must contain 1 to 128 valid characters"
    }
    require(template !in setOf(".", "..") && !template.endsWith('.') && !template.endsWith(' ')) { "Invalid filename template ending" }
    val literals = captureNameToken.replace(template) { match ->
        require(match.groupValues[1] in captureNameTokens) { "Unknown filename token" }; ""
    }
    require(literals.none { it in "{}<>:\"/\\|?*" || Character.isISOControl(it) ||
        Character.getType(it) in setOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt()) }) {
        "Filename template contains invalid tokens, paths or controls"
    }
    return Normalizer.normalize(template, Normalizer.Form.NFC)
}

/** Full UUID is mandatory to keep independent captures distinct, even after export to one folder.
 * Variable slate text is normalized for names only; the full editorial metadata remains unchanged.
 * The human prefix is bounded to 160 UTF-8 bytes, leaving room for all related role suffixes. */
fun captureFileStem(bundleId: String, naming: CaptureNameSnapshot? = null): String {
    require(UUID.fromString(bundleId).toString() == bundleId)
    if (naming == null || !naming.settings.enabled) return "OCC_$bundleId"
    val instant = Instant.ofEpochMilli(naming.epochMillis)
    val slate = naming.slate
    val values = mapOf("project" to slate.project, "camera" to slate.camera, "scene" to slate.scene,
        "take" to slate.takeNumber.toString().padStart(4, '0'), "reel" to slate.reel,
        "date" to DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(ZoneOffset.UTC).format(instant),
        "time" to DateTimeFormatter.ofPattern("HHmmssSSS", Locale.ROOT).withZone(ZoneOffset.UTC).format(instant))
    val expanded = captureNameToken.replace(validateCaptureNameTemplate(naming.settings.template)) { values.getValue(it.groupValues[1]) }
    val normalized = Normalizer.normalize(expanded, Normalizer.Form.NFC)
    val cleaned = buildString {
        var index = 0
        while (index < normalized.length) {
            val point = normalized.codePointAt(index)
            val invalid = point <= Char.MAX_VALUE.code && point.toChar() in "{}<>:\"/\\|?*" ||
                Character.isWhitespace(point) || Character.isISOControl(point) ||
                Character.getType(point) in setOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())
            if (invalid) append('_') else appendCodePoint(point)
            index += Character.charCount(point)
        }
    }.trim('.', '_', ' ')
    val prefix = buildString {
        var index = 0; var bytes = 0
        while (index < cleaned.length) {
            val point = cleaned.codePointAt(index)
            val unit = String(Character.toChars(point)); val count = unit.toByteArray(Charsets.UTF_8).size
            if (bytes + count > 160) break
            append(unit); bytes += count; index += Character.charCount(point)
        }
    }.trimEnd('.', '_', ' ').ifEmpty { "untitled" }
    return "${prefix}_$bundleId"
}
