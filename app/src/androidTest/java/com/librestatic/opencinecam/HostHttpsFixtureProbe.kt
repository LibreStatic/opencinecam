/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assume.assumeTrue

/**
 * The HTTPS WebDAV fixture runs on the host (10.0.2.2:18443 from the emulator), outside
 * instrumentation. Without it every case would fail on connectivity, which says nothing about
 * the code under test, so callers skip instead.
 */
internal fun assumeHostHttpsFixture() {
    val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 18443), 1_000) } }.isSuccess
    assumeTrue("Host HTTPS WebDAV fixture is not listening on 10.0.2.2:18443", reachable)
}
