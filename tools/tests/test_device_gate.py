#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "device_gate.py"
SPEC = importlib.util.spec_from_file_location("device_gate", MODULE_PATH)
assert SPEC and SPEC.loader
gate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = gate
SPEC.loader.exec_module(gate)


class DeviceGateTest(unittest.TestCase):
    def test_empty_adb_is_not_run(self):
        report = gate.collect(lambda command: "List of devices attached\n")
        self.assertEqual("NOT_RUN", report["status"])
        self.assertEqual([], report["devices"])

    def test_device_identity_is_collected_without_promotion(self):
        def run(command):
            if command[:2] == ["adb", "devices"]:
                return "List of devices attached\nserial123\tdevice product:test model:Test_Device\n"
            return """[ro.build.version.sdk]: [36]
[ro.build.fingerprint]: [vendor/device/test:14/ABC/123:userdebug/test-keys]
[ro.product.manufacturer]: [Vendor]
[ro.product.model]: [Test Device]
"""

        report = gate.collect(run)
        self.assertEqual("READY", report["status"])
        device = report["devices"][0]
        self.assertEqual("36", device["apiLevel"])
        self.assertEqual("not evaluated", report["capabilityPromotion"])

    def test_unauthorized_device_is_not_run(self):
        report = gate.collect(lambda command: "List of devices attached\nserial123\tunauthorized\n")
        self.assertEqual("NOT_RUN", report["status"])
        self.assertEqual("NOT_RUN", report["devices"][0]["status"])

    def test_adb_daemon_messages_are_not_devices(self):
        output = """* daemon not running; starting now at tcp:5037
* daemon started successfully
List of devices attached
"""
        report = gate.collect(lambda command: output)
        self.assertEqual("NOT_RUN", report["status"])
        self.assertEqual([], report["devices"])

    def test_malformed_api_level_is_unknown(self):
        def run(command):
            if command[:2] == ["adb", "devices"]:
                return "List of devices attached\nserial123\tdevice\n"
            return """[ro.build.version.sdk]: [future]
[ro.build.fingerprint]: [vendor/device/build]
"""

        report = gate.collect(run)
        self.assertEqual("UNKNOWN", report["status"])

    def test_serial_filter_selects_one_transport(self):
        def run(command):
            if command[:2] == ["adb", "devices"]:
                return """List of devices attached
first\tdevice
second\tdevice
"""
            return """[ro.build.version.sdk]: [36]
[ro.build.fingerprint]: [vendor/device/build]
"""

        report = gate.collect(run, "second")
        self.assertEqual("READY", report["status"])
        self.assertEqual(["second"], [device["serial"] for device in report["devices"]])

    def test_missing_selected_serial_is_not_run(self):
        report = gate.collect(lambda command: "List of devices attached\nfirst\tdevice\n", "second")
        self.assertEqual("NOT_RUN", report["status"])
        self.assertEqual("selected Android device not connected", report["reason"])


if __name__ == "__main__":
    unittest.main()
