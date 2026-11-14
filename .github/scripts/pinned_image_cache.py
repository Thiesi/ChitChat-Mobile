"""Decide whether the installed native system image may be saved to the Actions cache.

The cache key names the exact pinned image revision and emulator build. Only an image that
verifiably is that pinned revision, at the hosted runner's fixed SDK path that the cache step
names, is saved; anything else (a failed install, a different revision, another SDK location)
is not, so a cache hit can never differ from the pinned artifact. A miss downloads as today.
"""

import os
from pathlib import Path

import pin_journey_image as pin

CACHED_SDK = Path("/usr/local/lib/android/sdk")


def pinned(sdk: Path, api: int, cached_sdk: Path = CACHED_SDK) -> bool:
    image = sdk / f"system-images/android-{api}/google_apis/x86_64"
    return (sdk == cached_sdk and image.is_dir() and not image.is_symlink()
            and (image / "system.img").is_file()
            and pin.revision(image / "source.properties") == pin.IMAGE_PINS[api]["revision"])


def main() -> None:
    api = pin.requested_api()
    sdk = Path(os.environ.get("ANDROID_HOME", "")).resolve()
    result = pinned(sdk, api)
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"pinned={'true' if result else 'false'}\n")
    print(f"API{api} image cacheable: {result}.")


if __name__ == "__main__":
    main()
