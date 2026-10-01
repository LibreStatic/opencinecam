import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path

from tools.qualify_oclog2 import DRIVER_SAMPLER_SHADERS, EXPECTED_SHADERS, TIER_GREY_RATIO, evaluate


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def float32(value: float) -> float:
    """The app records the gain as a Kotlin Float widened to a JSON double."""
    return struct.unpack("f", struct.pack("f", value))[0]


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
            "source": {"path": "HLG10_BT2020", "androidDataSpace": 168165376, "dataSpaceMismatchedFrames": 0},
            "transform": {
                "curve": "OCLog2",
                "version": "2.0.0",
                "shaderSha256": EXPECTED_SHADERS["HLG10_BT2020"],
                "greyReference": "NATIVE",
                "sceneGain": 1.0,
                "ycbcrConversion": "BT2020/full/10-bit",
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
                "greyReference": "NATIVE",
                "sceneGain": 1.0,
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

    def test_driver_ycbcr_conversion_fails_by_name(self) -> None:
        manifest = self.manifest()
        manifest["profile"]["shaderSha256"] = DRIVER_SAMPLER_SHADERS["HLG10_BT2020"]
        result = evaluate(manifest, self.root)
        self.assertEqual("FAILED", result["status"])
        check = next(item for item in result["checks"] if item["name"] == "runtime-shader")
        self.assertEqual("driver-ycbcr-conversion", check["reason"])

    def test_ycbcr_conversion_must_be_reported_from_the_dataspace(self) -> None:
        for conversion, status, bucket in (
            (None, "NOT_RUN", "notRun"),
            ("BT601/limited/10-bit (default)", "NOT_RUN", "notRun"),
            ("BT709/limited/8-bit", "FAILED", "failed"),
            ("BT2020/limited/10-bit", "QUALIFIED", None),
        ):
            with self.subTest(conversion=conversion):
                self.write_sidecar_transform(ycbcrConversion=conversion)
                result = evaluate(self.manifest(), self.root)
                self.assertEqual(status, result["status"])
                if bucket:
                    self.assertIn("ycbcr-conversion", result[bucket])

    def rewrite_sidecar_source(self, **fields: object) -> dict:
        sidecar = json.loads(self.sidecar.read_text())
        sidecar["source"].update(fields)
        self.sidecar.write_text(json.dumps(sidecar))
        manifest = self.manifest()
        manifest["files"]["sidecar"]["sha256"] = sha256(self.sidecar)
        return manifest

    def test_limited_range_hlg_dataspace_is_accepted(self) -> None:
        result = evaluate(self.rewrite_sidecar_source(androidDataSpace=302383104), self.root)
        self.assertEqual("QUALIFIED", result["status"])

    def test_sdr_dataspace_on_hlg_tier_fails(self) -> None:
        # BT709 standard, SMPTE 170M transfer, limited range: the HLG decode would be wrong.
        result = evaluate(self.rewrite_sidecar_source(androidDataSpace=(1 << 16) | (3 << 22) | (2 << 27)), self.root)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("source-dataspace", result["failed"])

    def test_mismatched_frames_fail_even_if_last_frame_matches(self) -> None:
        result = evaluate(self.rewrite_sidecar_source(dataSpaceMismatchedFrames=3), self.root)
        self.assertIn("source-dataspace", result["failed"])

    def test_unreported_dataspace_is_not_run(self) -> None:
        result = evaluate(self.rewrite_sidecar_source(androidDataSpace=None), self.root)
        self.assertEqual("NOT_RUN", result["status"])
        self.assertIn("source-dataspace", result["notRun"])

    def test_declared_failure_beats_missing_checks(self) -> None:
        manifest = self.manifest()
        del manifest["implementations"]["ocio"]
        manifest["implementations"]["gpu"] = {"status": "FAIL", "maxAbsError": 0.1}
        result = evaluate(manifest, self.root)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("implementation-gpu", result["failed"])
        self.assertIn("implementation-ocio", result["notRun"])

    def write_sidecar_transform(self, **changes: object) -> None:
        sidecar = json.loads(self.sidecar.read_text())
        sidecar["transform"].update(changes)
        for key in [key for key, value in changes.items() if value is None]:
            del sidecar["transform"][key]
        self.sidecar.write_text(json.dumps(sidecar))

    def test_scene_gain_is_part_of_the_tuple(self) -> None:
        result = evaluate(self.manifest(), self.root)
        self.assertEqual("NATIVE", result["tuple"]["greyReference"])
        self.assertEqual(1.0, result["tuple"]["sceneGain"])

    def test_matched_reference_qualifies_with_the_recorded_float_gain(self) -> None:
        gain = float32(TIER_GREY_RATIO)
        self.write_sidecar_transform(greyReference="MATCH_SDR", sceneGain=gain)
        manifest = self.manifest()
        manifest["profile"].update(greyReference="MATCH_SDR", sceneGain=gain)
        manifest["files"]["sidecar"]["sha256"] = sha256(self.sidecar)
        result = evaluate(manifest, self.root)
        self.assertEqual("QUALIFIED", result["status"])
        self.assertAlmostEqual(3.7396, result["tuple"]["sceneGain"], places=4)

    def test_sidecar_without_gain_is_a_mismatch(self) -> None:
        self.write_sidecar_transform(greyReference=None, sceneGain=None)
        manifest = self.manifest()
        manifest["files"]["sidecar"]["sha256"] = sha256(self.sidecar)
        result = evaluate(manifest, self.root)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("sidecar-contract", result["failed"])

    def test_sidecar_with_a_different_gain_is_a_mismatch(self) -> None:
        self.write_sidecar_transform(greyReference="MATCH_SDR", sceneGain=float32(TIER_GREY_RATIO))
        manifest = self.manifest()
        manifest["files"]["sidecar"]["sha256"] = sha256(self.sidecar)
        result = evaluate(manifest, self.root)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("sidecar-contract", result["failed"])

    def test_gain_inconsistent_with_reference_and_tier_fails(self) -> None:
        manifest = self.manifest()
        # MATCH_HLG leaves the HLG tier untouched; a ratio gain there is not a valid tuple.
        manifest["profile"].update(greyReference="MATCH_HLG", sceneGain=TIER_GREY_RATIO)
        result = evaluate(manifest, self.root)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("scene-gain", result["failed"])

    def test_profile_without_gain_is_not_run(self) -> None:
        manifest = self.manifest()
        del manifest["profile"]["greyReference"]
        del manifest["profile"]["sceneGain"]
        result = evaluate(manifest, self.root)
        self.assertIn("scene-gain", result["notRun"])
        self.assertIn("sidecar-contract", result["failed"])


if __name__ == "__main__":
    unittest.main()
