/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/** The privacy policy promises no cloud backup or device transfer: both rule files must exclude every domain. */
class BackupRulesTest {
    private val domains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    private fun appDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app/src/main/AndroidManifest.xml").isFile) dir = dir.parentFile
        return File(requireNotNull(dir), "app/src/main")
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement

    private fun excluded(parent: Element): Set<String> {
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }.map {
            assertEquals(".", it.getAttribute("path"))
            it.getAttribute("domain")
        }.toSet()
    }

    @Test fun manifestReferencesBothRuleFiles() {
        val app = parse(File(appDir(), "AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(android, "fullBackupContent"))
    }

    @Test fun dataExtractionRulesExcludeEverythingFromCloudAndTransfer() {
        val root = parse(File(appDir(), "res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = root.getElementsByTagName(section).item(0) as Element
            assertEquals(section, domains, excluded(element))
        }
    }

    @Test fun legacyBackupRulesExcludeEverything() {
        assertEquals(domains, excluded(parse(File(appDir(), "res/xml/backup_rules.xml"))))
    }
}
