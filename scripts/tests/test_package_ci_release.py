"""Delivery tests use synthetic APK bytes, never a user's book or signing key."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("package_ci_release", Path(__file__).resolve().parents[1] / "package-ci-release.py")
packaging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packaging)


class PackageCiReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "app/build/outputs/apk/preview"
        self.source.mkdir(parents=True)
        self.mapping = self.root / "app/build/outputs/mapping/preview/mapping.txt"
        self.mapping.parent.mkdir(parents=True)
        self.mapping.write_text("synthetic R8 mapping", encoding="utf-8")
        self.env = {"GITHUB_SHA": "a" * 40, "GITHUB_REF": "refs/heads/main", "GITHUB_EVENT_NAME": "push",
                    "GITHUB_RUN_NUMBER": "7", "GITHUB_RUN_ATTEMPT": "2", "GITHUB_RUN_ID": "123",
                    "GITHUB_SERVER_URL": "https://github.com", "GITHUB_REPOSITORY": "example/readx"}

    def metadata(self, output="app-preview.apk", ocr=False):
        return {"applicationId": "io.readx.app", "variantName": "preview", "elements": [{
            "outputFile": output, "filters": [], "versionCode": 100007,
            "versionName": "0.7.4" + ("-ocr" if ocr else "") + "-preview-ci.7.2"}]}

    def build(self):
        (self.source / "app-preview.apk").write_bytes(b"synthetic ordinary APK")
        packaging.write_json(self.source / "output-metadata.json", self.metadata())
        packaging.stage(self.root)

    def test_output_and_checksums_are_preserved_without_ocr(self):
        self.build()
        packaging.finalize(self.root, self.env, "b" * 64)
        release = self.root / "build/ci/release"
        self.assertEqual(b"synthetic ordinary APK", (release / "app-preview.apk").read_bytes())
        self.assertEqual(["app-preview.apk"], [p.name for p in release.glob("*.apk")])
        info = json.loads((release / "build-info.json").read_text(encoding="utf-8"))
        self.assertEqual("ci-v0.7.4-7.2-aaaaaaa", info["tag"])
        self.assertEqual(self.env["GITHUB_SHA"], info["commit"])
        self.assertEqual(100007, info["apk"]["versionCode"])
        self.assertFalse(info["bundledOcr"])
        for line in (release / "SHA256SUMS.txt").read_text().splitlines():
            digest, filename = line.split("  ")
            self.assertEqual(packaging.digest(release / filename), digest)
        self.assertEqual(3, len((release / "SHA256SUMS.txt").read_text().splitlines()))

    def test_bundled_ocr_output_is_rejected(self):
        self.build()
        packaging.write_json(self.source / "output-metadata.json", self.metadata(ocr=True))
        with self.assertRaisesRegex(ValueError, "ordinary CI Preview"):
            packaging.stage(self.root)

    def test_path_escape_and_splits_are_rejected(self):
        for output, filters in [("../evil.apk", []), ("app-preview.apk", [{"filterType": "ABI"}])]:
            with self.subTest(output=output):
                meta = self.metadata(output=output)
                meta["elements"][0]["filters"] = filters
                packaging.write_json(self.source / "output-metadata.json", meta)
                with self.assertRaises(ValueError):
                    packaging.stage(self.root)

    def test_empty_apk_is_rejected(self):
        (self.source / "app-preview.apk").write_bytes(b"")
        packaging.write_json(self.source / "output-metadata.json", self.metadata())
        with self.assertRaisesRegex(ValueError, "empty"):
            packaging.stage(self.root)

    def test_changed_apk_cannot_be_published(self):
        self.build()
        (self.root / "build/ci/release/app-preview.apk").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "changed"):
            packaging.finalize(self.root, self.env, "b" * 64)

    def test_different_run_and_pr_publication_are_rejected(self):
        self.build()
        for changes in [{"GITHUB_RUN_NUMBER": "8"}, {"GITHUB_EVENT_NAME": "pull_request"}, {"GITHUB_REF": "refs/heads/feature"}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                packaging.finalize(self.root, {**self.env, **changes}, "b" * 64)

    def test_wrong_application_and_build_type_are_rejected(self):
        for change in [{"applicationId": "example.other"}, {"variantName": "debug"}]:
            with self.subTest(change=change):
                packaging.write_json(self.source / "output-metadata.json", {**self.metadata(), **change})
                with self.assertRaisesRegex(ValueError, "ReadX Preview metadata"):
                    packaging.stage(self.root)


if __name__ == "__main__":
    unittest.main()
