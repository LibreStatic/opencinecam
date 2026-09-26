/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProxyCatalogResidueTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun key(seed: Char) = seed.toString().repeat(64)

    @Test fun unfinishedWritesAreDiscardedAndLegacyBackupsWinAsInAtomicFile() {
        val root = temporary.newFolder()
        File(root, "${key('a')}.json").writeText("committed-a")
        File(root, "${key('a')}.json.new").writeText("partial-a") // Died before rename: base stays valid.
        File(root, "${key('b')}.json.new").writeText("partial-b") // First write never finished.
        File(root, "${key('c')}.json").writeText("torn-c")
        File(root, "${key('c')}.json.bak").writeText("committed-c") // Legacy write: backup is authoritative.
        File(root, "${key('d')}.json.bak").writeText("committed-d")
        recoverProxyReceiptResidue(root)
        assertEquals(listOf("${key('a')}.json", "${key('c')}.json", "${key('d')}.json"), root.list()!!.sorted())
        assertEquals("committed-a", File(root, "${key('a')}.json").readText())
        assertEquals("committed-c", File(root, "${key('c')}.json").readText())
        assertEquals("committed-d", File(root, "${key('d')}.json").readText())
    }

    @Test fun unknownArtifactsAreLeftForTheCatalogToReject() {
        val root = temporary.newFolder()
        File(root, "notes.txt").writeText("x")
        File(root, "${key('e')}.json.tmp").writeText("x")
        recoverProxyReceiptResidue(root)
        assertEquals(listOf("${key('e')}.json.tmp", "notes.txt"), root.list()!!.sorted())
    }

    @Test fun residueDirectoryIsNeverDeletedRecursively() {
        val root = temporary.newFolder()
        val residue = File(root, "${key('f')}.json.new").apply { mkdir(); File(this, "child").writeText("x") }
        try { recoverProxyReceiptResidue(root); fail("Directory residue accepted") } catch (_: IllegalStateException) { }
        assertTrue(File(residue, "child").exists())
    }
}
