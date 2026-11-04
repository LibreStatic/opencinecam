/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetworkShareSessionTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val clock = ManualClock()
    private var recording = false
    private val second = InetAddress.getByName("127.0.0.2") as Inet4Address
    private var addresses = listOf(ShareBinding("wlan0", loopback))
    private val stops = mutableListOf<NetworkShareStopReason>()
    private val sessions = mutableListOf<NetworkShareSession>()

    private fun session(ports: IntRange = freePort().let { it..it }) = NetworkShareSession(
        tree = ShareTree({ listOf(ShareFixture.take()) }, clock),
        credentials = ShareCredentials("occ", "k7pq2m9xwe4hjtna"),
        addresses = { addresses },
        takeActive = { recording },
        clock = clock,
        ports = ports,
        onStopped = { stops += it },
    ).also { sessions += it }

    @After
    fun closeAll() {
        sessions.forEach { it.stop(NetworkShareStopReason.OPERATOR) }
    }

    private fun connects(port: Int, address: InetAddress = loopback): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), 1_000) }
        true
    } catch (_: java.io.IOException) {
        false
    }

    @Test
    fun startsOnTheGivenAddressesAndReportsTheirUrls() {
        val port = freePort()
        val session = session(port..port)
        val start = session.start() as NetworkShareSession.Start.Running
        assertEquals(port, start.port)
        assertEquals(listOf("http://127.0.0.1:$port/"), start.urls)
        assertTrue(session.running)
        assertTrue(connects(port))
        assertNull(session.tick())
    }

    @Test
    fun refusesToStartWhileATakeRecords() {
        recording = true
        val session = session()
        assertEquals(NetworkShareSession.Start.Refused(NetworkShareStopReason.RECORDING), session.start())
        assertFalse(session.running)
    }

    @Test
    fun refusesToStartWithoutAQualifyingAddress() {
        addresses = emptyList()
        assertEquals(NetworkShareSession.Start.Refused(NetworkShareStopReason.NO_NETWORK), session().start())
    }

    @Test
    fun movesToTheNextPortWhenTheFirstIsTakenAndGivesUpWhenAllAre() {
        ServerSocket().use { busy ->
            busy.bind(InetSocketAddress(loopback, 0))
            val taken = busy.localPort
            assertEquals(NetworkShareSession.Start.Refused(NetworkShareStopReason.PORT_BUSY), session(taken..taken).start())
            val next = (taken + 1..taken + 20).first { candidate -> runCatching { ServerSocket(candidate, 1, loopback).close() }.isSuccess }
            val start = session(taken..next).start() as NetworkShareSession.Start.Running
            assertEquals(next, start.port)
        }
    }

    @Test
    fun stopsAsSoonAsATakeRecords() {
        val port = freePort()
        val session = session(port..port)
        session.start()
        recording = true
        assertEquals(NetworkShareStopReason.RECORDING, session.tick())
        assertFalse(session.running)
        assertFalse(connects(port))
        assertEquals(listOf(NetworkShareStopReason.RECORDING), stops)
    }

    @Test
    fun stopsAfter15MinutesWithoutActivity() {
        val session = session()
        session.start()
        clock.advance(NetworkShareSession.IDLE_STOP_MS - 1)
        assertNull(session.tick())
        clock.advance(1)
        assertEquals(NetworkShareStopReason.IDLE, session.tick())
        assertEquals(listOf(NetworkShareStopReason.IDLE), stops)
    }

    @Test
    fun onlyRealTransfersResetTheIdleClock() {
        val port = freePort()
        val session = session(port..port)
        session.start()
        val take = ShareFixture.TAKE
        // request() answers a fresh challenge each time: the manual clock ages nonces past their 5 minutes.
        RawHttpClient(loopback, port, "occ", "k7pq2m9xwe4hjtna").use { c ->
            // A mounted desktop polling: OPTIONS, Depth 0 and the root listing keep nothing alive.
            assertEquals(200, c.request("OPTIONS", "/").status)
            clock.advance(NetworkShareSession.IDLE_STOP_MS - 2_000)
            assertEquals(207, c.request("PROPFIND", "/", mapOf("Depth" to "1")).status)
            assertEquals(207, c.request("PROPFIND", "/$take/", mapOf("Depth" to "0")).status)
            assertEquals(207, c.request("PROPFIND", "/$take/${take}_000000.dng", mapOf("Depth" to "0")).status)
            assertEquals(200, c.request("HEAD", "/$take/${take}_000000.dng").status)
            assertEquals(416, c.request("GET", "/$take/${take}_000000.dng", mapOf("Range" to "bytes=999999-")).status)
            assertNull(session.tick())
            clock.advance(1_000)
            assertNull(session.tick())

            // Opening a take's folder counts.
            assertEquals(207, c.request("PROPFIND", "/$take/", mapOf("Depth" to "1")).status)
            clock.advance(NetworkShareSession.IDLE_STOP_MS - 1)
            assertNull(session.tick())

            // So does a GET that sends a body.
            assertEquals(206, c.request("GET", "/$take/${take}_000000.dng", mapOf("Range" to "bytes=0-9")).status)
            clock.advance(NetworkShareSession.IDLE_STOP_MS - 1)
            assertNull(session.tick())
        }
        // Unauthenticated noise does not count either.
        RawHttpClient(loopback, port).use { assertEquals(401, it.request("GET", "/$take/${take}_000000.dng").status) }
        clock.advance(1)
        assertEquals(NetworkShareStopReason.IDLE, session.tick())
    }

    @Test
    fun aNetworkThatAppearsMidSessionIsNeverBound() {
        val port = freePort()
        val session = session(port..port)
        session.start()
        addresses = listOf(ShareBinding("wlan0", loopback), ShareBinding("ap0", second))
        assertNull(session.tick())
        assertTrue(session.running)
        assertEquals(listOf("http://127.0.0.1:$port/"), session.urls())
        assertFalse(connects(port, second))
    }

    @Test
    fun oneOfTwoOriginalAddressesVanishingStopsTheShare() {
        val port = freePort()
        addresses = listOf(ShareBinding("wlan0", loopback), ShareBinding("ap0", second))
        val session = session(port..port)
        session.start()
        assertTrue(connects(port, second))
        addresses = listOf(ShareBinding("wlan0", loopback))
        assertEquals(NetworkShareStopReason.NO_NETWORK, session.tick())
        assertFalse(connects(port))
    }

    @Test
    fun anAddressMovingToAnotherInterfaceStopsTheShare() {
        val session = session()
        session.start()
        addresses = listOf(ShareBinding("rndis0", loopback))
        assertEquals(NetworkShareStopReason.NO_NETWORK, session.tick())
    }

    @Test
    fun stopsWhenNoAddressIsLeft() {
        val session = session()
        session.start()
        addresses = emptyList()
        assertEquals(NetworkShareStopReason.NO_NETWORK, session.tick())
    }

    @Test
    fun theFirstStopReasonWinsAndASessionStartsOnce() {
        val session = session()
        session.start()
        assertEquals(NetworkShareStopReason.OPERATOR, session.stop(NetworkShareStopReason.OPERATOR))
        assertNull(session.stop(NetworkShareStopReason.IDLE))
        assertNull(session.tick())
        assertEquals(listOf(NetworkShareStopReason.OPERATOR), stops)
        try {
            session.start()
            fail("restarted")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun theWildcardIsNeverBound() {
        addresses = listOf(ShareBinding("wlan0", InetAddress.getByName("0.0.0.0") as Inet4Address))
        try {
            session().start()
            fail("bound the wildcard")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun stateNeverPrintsThePassword() {
        val running = NetworkShareState.Running(listOf("http://192.168.1.23:8642/"), "occ", "k7pq2m9xwe4hjtna")
        assertFalse(running.toString().contains("k7pq2m9xwe4hjtna"))
        assertFalse(ShareCredentials("occ", "k7pq2m9xwe4hjtna").toString().contains("k7pq2m9xwe4hjtna"))
    }

    companion object {
        fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }
}
