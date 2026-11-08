"""Pin only the dedicated Linux Actions emulator image before its first launch.

The action invokes sdkmanager first. If it installs a later revision, replace
just that image with the approved checksum-verified official archive.
"""

import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tempfile
import urllib.request
import zipfile

IMAGE_REVISION = "7"
EMULATOR_BUILD = "16740729"
EMULATOR_VERSION = "37.2.13.0"
# Official https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-1.xml
IMAGE_URL = "https://dl.google.com/android/repository/sys-img/google_apis/x86_64-36_r07.zip"
IMAGE_BYTES = 1_895_447_397
IMAGE_SHA1 = "c6bf44bdcd885bb902b4ba752d111a073ad7a817"


def revision(path: Path) -> str | None:
    try:
        match = re.search(r"(?m)^Pkg\.Revision\s*=\s*([0-9.]+)\s*$", path.read_text())
        return match[1] if match else None
    except OSError:
        return None


def extract_image(archive: zipfile.ZipFile, destination: Path) -> None:
    for item in archive.infolist():
        name = PurePosixPath(item.filename)
        if (name.is_absolute() or ".." in name.parts or not name.parts or name.parts[0] != "x86_64"
                or "\\" in item.orig_filename or ":" in item.orig_filename
                or stat.S_ISLNK(item.external_attr >> 16)):
            raise ValueError("Unexpected image archive entry")
        target = destination.joinpath(*name.parts)
        if item.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(item) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_OS") != "Linux":
        raise RuntimeError("Installer is only for the dedicated Linux Actions emulator")
    sdk = Path(os.environ["ANDROID_HOME"]).resolve(strict=True)
    image = sdk / "system-images/android-36/google_apis/x86_64"
    if image.is_symlink() or image.resolve() != image or not image.is_relative_to(sdk):
        raise RuntimeError("Unexpected SDK image target")
    version = subprocess.check_output([str(sdk / "emulator/emulator"), "-version"], text=True)
    if f"Android emulator version {EMULATOR_VERSION} (build_id {EMULATOR_BUILD})" not in version:
        raise RuntimeError("Pinned emulator build mismatch")
    if revision(image / "source.properties") == IMAGE_REVISION:
        print("Verified API36 google_apis x86_64 revision7 and emulator build16740729.")
        return
    with tempfile.TemporaryDirectory(prefix="ccm-pinned-image-", dir=os.environ["RUNNER_TEMP"]) as temp:
        scratch = Path(temp)
        archive_path = scratch / "image.zip"
        digest = hashlib.sha1()
        count = 0
        with urllib.request.urlopen(IMAGE_URL, timeout=60) as response, archive_path.open("xb") as output:
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                if count > IMAGE_BYTES:
                    raise ValueError("Image archive exceeds pinned size")
                digest.update(chunk); output.write(chunk)
        if count != IMAGE_BYTES or digest.hexdigest() != IMAGE_SHA1:
            raise ValueError("Image archive checksum mismatch")
        with zipfile.ZipFile(archive_path) as archive:
            extract_image(archive, scratch)
        prepared = scratch / "x86_64"
        if revision(prepared / "source.properties") != IMAGE_REVISION:
            raise ValueError("Image archive revision mismatch")
        # Checksum, CRC-checked extraction and revision validation precede removal.
        if image.exists():
            shutil.rmtree(image)
        shutil.move(str(prepared), str(image))
        if revision(image / "source.properties") != IMAGE_REVISION:
            raise RuntimeError("Installed image verification failed")
    print("Installed checksum-verified API36 google_apis x86_64 revision7.")


if __name__ == "__main__":
    main()
