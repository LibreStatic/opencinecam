import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.qualify_oclog2 import EXPECTED_SHADERS, evaluate


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class OpenCineLogQualificationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.clip = self.root / "fixture.mp4"
        self.artifact = self.root / "manifest.json"
        self.sidecar = self.root / "fixture.oclog2.json"
        self.ffprobe = self.root / "fixture.ffprobe.json"
        self.clip.write_bytes(b"synthetic-hevc-main10-fixture")
        self.artifact.write_text(json.dumps({
            "schema": "opencinecam-oclog2-artifacts-v1",
            "specVersion": "2.0.0",
        }))
        self.sidecar.write_text(json.dumps({
            "cameraId": "0",
            "source": {"path": "HLG10_BT2020"},
            "transform": {
                "curve": "OCLog2",
                "version": "2.0.0",
                "shaderSha256": EXPECTED_SHADERS["HLG10_BT2020"],
            },
        }))
        self.ffprobe.write_text(json.dumps({"streams": [{
            "codec_type": "video",
            "codec_name": "hevc",
            "profile": "Main 10",
            "width": 1920,
            "height": 1080,
        }]}))

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def manifest(self) -> dict:
        return {
            "schema": "opencinecam-oclog2-qualification-input-v1",
            "artifact": {
                "id": "oclog2-fixture",
                "path": self.artifact.name,
                "sha256": sha256(self.artifact),
                "sourceDigest": "b" * 64,
            },
            "target": {"fingerprint": "vendor/device/build", "cameraId": "0", "apiLevel": 35, "physical": True},
            "profile": {
                "width": 1920,
                "height": 1080,
                "fps": 30,
                "sourcePath": "HLG10_BT2020",
                "specVersion": "2.0.0",
                "shaderSha256": EXPECTED_SHADERS["HLG10_BT2020"],
            },
            "provenance": {
                "dynamicRange": "HLG10",
                "colorSpace": "BT2020_HLG",
                "sourcePrecisionClaim": "camera-hlg10-profile",
            },
            "implementationTolerance": 2e-5,
            "implementations": {
                name: {"status": "PASS", "maxAbsError": 1e-6}
                for name in ("cpu", "gpu", "lut1d", "lut3d", "ocio", "dctl")
            },
            "sustained": {"durationSeconds": 30, "encodedFrames": 900, "droppedFrames": 0, "status": "PASS"},
            "files": {
                "clip": {"path": self.clip.name, "sha256": sha256(self.clip)},
                "sidecar": {"path": self.sidecar.name, "sha256": sha256(self.sidecar)},
                "ffprobe": {"path": self.ffprobe.name, "sha256": sha256(self.ffprobe)},
            },
            "workflows": [
                {"name": "ffmpeg", "status": "PASS"},
                {"name": "[redacted editor]", "independent": True, "status": "PASS", "maxAbsError": 1e-6},
            ],
        }

    def test_complete_exact_bundle_qualifies(self) -> None:
        result = evaluate(self.manifest(), self.root)
        self.assertEqual("QUALIFIED", result["status"])
        self.assertEqual([], result["failed"])
        self.assertEqual([], result["notRun"])

    def test_missing_external_evidence_is_not_run(self) -> None:
        manifest = self.manifest()
        manifest["target"]["physical"] = False
        manifest["workflows"] = []
        result = evaluate(manifest, self.root)
        self.assertEqual("NOT_RUN", result["status"])
        self.assertIn("physical-target", result["notRun"])
        self.assertIn("independent-editor", result["notRun"])

    def test_tampered_hashed_file_fails(self) -> None:
        manifest = self.manifest()
        self.sidecar.write_text("{}")
        result = evaluate(manifest, self.root)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("sidecar-file", result["failed"])

    def test_stale_shader_fails_closed(self) -> None:
        manifest = self.manifest()
        manifest["profile"]["shaderSha256"] = "0" * 64
        result = evaluate(manifest, self.root)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("runtime-shader", result["failed"])

    def test_declared_failure_beats_missing_checks(self) -> None:
        manifest = self.manifest()
        del manifest["implementations"]["ocio"]
        manifest["implementations"]["gpu"] = {"status": "FAIL", "maxAbsError": 0.1}
        result = evaluate(manifest, self.root)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("implementation-gpu", result["failed"])
        self.assertIn("implementation-ocio", result["notRun"])


if __name__ == "__main__":
    unittest.main()
