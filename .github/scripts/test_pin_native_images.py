"""Image selection and verify-before-replace; fake SDK and mocked downloads only."""
import hashlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import pin_journey_image as pin


class PinTests(unittest.TestCase):
    def fixture(self):
        root=Path(self.enterContext(tempfile.TemporaryDirectory(prefix="ccm-image-unit-")))
        sdk=root/"sdk";sdk.mkdir();scratch=root/"scratch";scratch.mkdir()
        for api,revision in [(35,"7"),(36,"7")]:
            image=sdk/f"system-images/android-{api}/google_apis/x86_64";image.mkdir(parents=True)
            (image/"source.properties").write_text("Pkg.Revision="+revision+"\n")
        self.enterContext(patch.dict(os.environ,dict(NATIVE_API="35",GITHUB_ACTIONS="true",RUNNER_OS="Linux",ANDROID_HOME=str(sdk),RUNNER_TEMP=str(scratch))))
        self.enterContext(patch.object(pin.subprocess,"check_output",return_value=f"Android emulator version {pin.EMULATOR_VERSION} (build_id {pin.EMULATOR_BUILD})"))
        return sdk

    def test_both_approved_pins_keep_original_diagnostic_constants(self):
        self.assertEqual("7",pin.IMAGE_REVISION)
        self.assertEqual("9",pin.IMAGE_PINS[35]["revision"])
        self.assertEqual(1738815903,pin.IMAGE_PINS[35]["size"])
        self.assertEqual("0103e6dab21290c4b9d16550a3ce99476f884eef",pin.IMAGE_PINS[35]["sha1"])
        self.assertEqual(pin.IMAGE_URL,pin.IMAGE_PINS[36]["url"])

    def test_matching_api35_does_not_download_or_touch_api36(self):
        sdk=self.fixture();path=sdk/"system-images/android-35/google_apis/x86_64/source.properties"
        path.write_text("Pkg.Revision=9\n")
        with patch.object(pin.urllib.request,"urlopen") as download:
            pin.main();download.assert_not_called()
        self.assertEqual("7",pin.revision(sdk/"system-images/android-36/google_apis/x86_64/source.properties"))

    def test_verified_api35_archive_replaces_only_that_image(self):
        sdk=self.fixture();payload=io.BytesIO()
        with zipfile.ZipFile(payload,"w") as z:z.writestr("x86_64/source.properties","Pkg.Revision=9\n")
        data=payload.getvalue();source=dict(revision="9",url="https://fixture.invalid/image.zip",size=len(data),sha1=hashlib.sha1(data).hexdigest())
        with patch.dict(pin.IMAGE_PINS,{35:source}),patch.object(pin.urllib.request,"urlopen",return_value=io.BytesIO(data)):
            pin.main()
        self.assertEqual("9",pin.revision(sdk/"system-images/android-35/google_apis/x86_64/source.properties"))
        self.assertEqual("7",pin.revision(sdk/"system-images/android-36/google_apis/x86_64/source.properties"))

    def test_bad_checksum_preserves_existing_image(self):
        sdk=self.fixture();data=b"bad archive"
        source=dict(revision="9",url="https://fixture.invalid/image.zip",size=len(data),sha1="0"*40)
        with patch.dict(pin.IMAGE_PINS,{35:source}),patch.object(pin.urllib.request,"urlopen",return_value=io.BytesIO(data)),self.assertRaises(ValueError):
            pin.main()
        self.assertEqual("7",pin.revision(sdk/"system-images/android-35/google_apis/x86_64/source.properties"))


if __name__ == "__main__":
    unittest.main()
