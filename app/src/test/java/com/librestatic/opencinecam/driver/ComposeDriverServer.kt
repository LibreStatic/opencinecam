/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jdemeulenaere.compose.driver.startComposeDriverServer
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Serves one composable over Compose Driver's HTTP API for agent visual feedback (AGENTS.md).
 *
 * Started by `tools/compose-driver.sh start <Screen>`. Ordinary `test` runs exclude this class (its
 * SDK 36 sandbox needs Java 21), and the assumption below guards a direct run. Compose Driver needs a Java 21+ test JVM, which the script picks,
 * so SDK 36 runs too. The default viewport is the 1080x2400 @ 420 dpi phone the UI is reviewed on.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-port-420dpi", shadows = [ShadowThermalPowerManager::class])
class ComposeDriverServer {
    @Test
    fun serve() {
        Assume.assumeTrue("compose.driver.composable is not set", System.getProperty("compose.driver.composable") != null)
        System.getProperty("composeDriver.qualifiers")?.takeIf { it.isNotBlank() }?.let(RuntimeEnvironment::setQualifiers)
        startComposeDriverServer(port = System.getProperty("composeDriver.port")?.toInt() ?: 8765)
    }
}
