"""Only the exact pinned image at the cached SDK path is saved."""

from pathlib import Path
import tempfile
import unittest

import pinned_image_cache as cache


class PinnedImageCacheTests(unittest.TestCase):
    def image(self, sdk: Path, api: int, revision: str | None, system_img: bool = True) -> None:
        image = sdk / f"system-images/android-{api}/google_apis/x86_64"
        image.mkdir(parents=True)
        if revision is not None: (image / "source.properties").write_text(f"Pkg.Revision={revision}\n")
        if system_img: (image / "system.img").write_bytes(b"")

    def test_exact_pinned_revisions_are_cacheable(self):
        for api, revision in ((35, "9"), (36, "7")):
            with self.subTest(api=api), tempfile.TemporaryDirectory() as temp:
                sdk = Path(temp); self.image(sdk, api, revision)
                self.assertTrue(cache.pinned(sdk, api, cached_sdk=sdk))

    def test_other_revision_missing_files_or_another_sdk_path_are_not(self):
        cases = (("10", True, True), ("7", True, True), (None, True, True), ("9", False, True), ("9", True, False))
        for revision, system_img, same_sdk in cases:
            with self.subTest(revision=revision, system_img=system_img, same_sdk=same_sdk), tempfile.TemporaryDirectory() as temp:
                sdk = Path(temp); self.image(sdk, 35, revision, system_img)
                self.assertFalse(cache.pinned(sdk, 35, cached_sdk=sdk if same_sdk else sdk / "other"))
        with tempfile.TemporaryDirectory() as temp:
            self.assertFalse(cache.pinned(Path(temp), 35, cached_sdk=Path(temp)))


if __name__ == "__main__":
    unittest.main()
