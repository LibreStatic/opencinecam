/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.LutSignalDomain
import com.librestatic.opencinecam.camera.LutTransformKind

/** Only its own entry/selection are changed; existing library bytes are never reset. */
internal fun <T> withOperatorLutFixture(block: (String) -> T): T {
    val library = LutLibraries.get(InstrumentationRegistry.getInstrumentation().targetContext)
    val previous = library.states.value.operatorHash
    val name = "Photo-sequence-${java.util.UUID.randomUUID()}"
    val cube = ("TITLE \"$name\"\nLUT_3D_SIZE 2\n" + List(8) { "0 1 0" }.joinToString("\n") + "\n").toByteArray()
    val hash = library.importLut(cube, name, LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE).hash
    try {
        library.select(hash)
        return block(hash)
    } finally {
        library.select(previous)
        library.delete(hash)
    }
}
