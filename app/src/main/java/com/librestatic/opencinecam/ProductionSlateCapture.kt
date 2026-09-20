/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/** Called only after committed media. Never overwrite a next-take edit or wrap at the limit.
 * UUID media identity is independent; this optional editorial number is not a crash-atomic ID. */
internal fun afterSavedProductionSlate(current: CameraSettings, saved: ProductionSlateSettings): CameraSettings =
    if (saved.autoIncrementTake && saved.takeNumber < 999999 && current.productionSlate == saved)
        current.copy(productionSlate = saved.copy(takeNumber = saved.takeNumber + 1)) else current
