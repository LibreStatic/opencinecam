/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.librestatic.opencinecam.camera.AnamorphicSqueeze
import java.text.NumberFormat

/** A squeeze factor as the operator reads it, with the locale's decimal mark: "1.33×", "1,5×". */
@Composable
internal fun anamorphicSqueezeLabel(squeeze: AnamorphicSqueeze): String {
    if (!squeeze.isActive) return stringResource(R.string.anamorphic_off)
    val format = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply { maximumFractionDigits = 2 }
    return "${format.format(squeeze.factor.toDouble())}×"
}
