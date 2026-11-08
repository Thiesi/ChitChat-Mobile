"""Validate archive boundaries without SDK installation or downloads."""

import io
from pathlib import Path
import tempfile
import unittest
import zipfile

import pin_journey_image as pin


class ImagePinTests(unittest.TestCase):
    def test_fixed_image_tree_extracts_and_revision_is_readable(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("x86_64/source.properties", "Pkg.Revision=7\n")
        with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(data) as archive:
            pin.extract_image(archive, Path(temp))
            self.assertEqual("7", pin.revision(Path(temp) / "x86_64/source.properties"))

    def test_escaping_wrong_root_and_symlink_entries_are_rejected(self):
        for name in ("x86_64/../../outside", "/x86_64/file", "other/file", "x86_64\\escape"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                data = io.BytesIO()
                with zipfile.ZipFile(data, "w") as archive: archive.writestr(name.replace("\\", "/"), "private-body-canary")
                if "\\" in name:
                    # ZipInfo normalizes OS separators on Windows at creation/read time.
                    # Put the original malformed filename on the wire, in both ZIP headers.
                    data = io.BytesIO(data.getvalue().replace(name.replace("\\", "/").encode(), name.encode()))
                with zipfile.ZipFile(data) as archive:
                    with self.assertRaises(ValueError): pin.extract_image(archive, Path(temp))
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            link = zipfile.ZipInfo("x86_64/link"); link.external_attr = 0o120777 << 16
            archive.writestr(link, "outside")
        with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(data) as archive:
            with self.assertRaises(ValueError): pin.extract_image(archive, Path(temp))


if __name__ == "__main__":
    unittest.main()
