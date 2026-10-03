/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import android.os.PowerManager
import java.util.concurrent.Executor
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowPowerManager

/**
 * Robolectric shadows only the listener-only overload; the thermal HUD registers with an executor,
 * which otherwise reaches the missing thermal service and throws during composition.
 */
@Implements(PowerManager::class)
class ShadowThermalPowerManager : ShadowPowerManager() {
    @Implementation
    protected fun addThermalStatusListener(executor: Executor, listener: PowerManager.OnThermalStatusChangedListener) {
        addThermalStatusListener(listener as Any)
    }
}
