"""Only the dedicated, authorized reproducer arm; ordinary journey pins stay unchanged."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

import pin_journey_image as existing

# Official Google image manifest:
# https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-1.xml
ARMS = {
    "a": dict(api=36, revision="7", cores=2, ram=None),
    "b": dict(api=35, revision="9", cores=2, ram=None),
    "c": dict(api=36, revision="7", cores=4, ram="4096M"),
}
API35 = dict(url="https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip",
             size=1_738_815_903, sha1="0103e6dab21290c4b9d16550a3ce99476f884eef")


def main():
    arm = ARMS[os.environ["REPRODUCER_ARM"]]
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_OS") != "Linux":
        raise RuntimeError("Reproducer pins are only for dedicated Linux Actions emulators")
    sdk = Path(os.environ["ANDROID_HOME"]).resolve(strict=True)
    image = sdk / ("system-images/android-" + str(arm["api"]) + "/google_apis/x86_64")
    if image.is_symlink() or image.resolve() != image or not image.is_relative_to(sdk):
        raise RuntimeError("Unexpected image target")
    version = subprocess.check_output([str(sdk / "emulator/emulator"), "-no-window", "-version"], text=True)
    if f"Android emulator version {existing.EMULATOR_VERSION} (build_id {existing.EMULATOR_BUILD})" not in version:
        raise RuntimeError("Pinned emulator mismatch")
    if existing.revision(image / "source.properties") == arm["revision"]:
        return
    source = API35 if arm["api"] == 35 else dict(
        url=existing.IMAGE_URL, size=existing.IMAGE_BYTES, sha1=existing.IMAGE_SHA1)
    with tempfile.TemporaryDirectory(prefix="ccm-reproducer-image-", dir=os.environ["RUNNER_TEMP"]) as temp:
        scratch = Path(temp)
        archive_path = scratch / "image.zip"
        digest = hashlib.sha1()
        count = 0
        with urllib.request.urlopen(source["url"], timeout=60) as response, archive_path.open("xb") as output:
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                if count > source["size"]:
                    raise ValueError("Image exceeds pin")
                digest.update(chunk)
                output.write(chunk)
        if count != source["size"] or digest.hexdigest() != source["sha1"]:
            raise ValueError("Image checksum mismatch")
        with zipfile.ZipFile(archive_path) as archive:
            existing.extract_image(archive, scratch)
        prepared = scratch / "x86_64"
        if existing.revision(prepared / "source.properties") != arm["revision"]:
            raise ValueError("Image revision mismatch")
        if image.exists():
            shutil.rmtree(image)
        shutil.move(str(prepared), str(image))
        if existing.revision(image / "source.properties") != arm["revision"]:
            raise RuntimeError("Installed revision mismatch")


if __name__ == "__main__":
    main()
