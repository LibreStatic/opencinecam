/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every slider must go through [CineSlider], which keeps thumb drags out of the back-gesture insets. */
class CineSliderSourceTest {
    @Test fun appCodeDoesNotCallMaterialSliderDirectly() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java").isDirectory) dir = dir.parentFile
        val sources = File(requireNotNull(dir), "app/src/main/java").walkTopDown().filter { it.extension == "kt" && it.name != "CineSlider.kt" }
        val direct = Regex("""(?<![\w.])(?:androidx\.compose\.material3\.)?(Range)?Slider\(""")
        val offenders = sources.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) -> direct.containsMatchIn(line) }.map { (i, _) -> "${file.name}:${i + 1}" }
        }.toList()
        assertEquals("use CineSlider instead of Material3 Slider", emptyList<String>(), offenders)
    }
}
