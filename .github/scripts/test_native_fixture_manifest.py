"""Guard the app/native-fixture window contract before any device invocation."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"


class NativeFixtureManifestTests(unittest.TestCase):
    def test_native_host_matches_production_window_theme_and_ime_mode(self):
        production = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot().find("application")
        main = next(a for a in production.findall("activity") if a.get(ANDROID + "name") in
                    {".MainActivity", "io.github.thiesi.chitchat.MainActivity"})
        theme = main.get(ANDROID + "theme") or production.get(ANDROID + "theme")
        self.assertIsNotNone(theme, "Production must declare its app window theme")
        self.assertTrue(theme.startswith("@style/"), "Production uses its own day/night resource, not a platform test theme")
        debug = ET.parse(ROOT / "app/src/debug/AndroidManifest.xml").getroot().find("application")
        host = next(a for a in debug.findall("activity") if a.get(ANDROID + "name") == "androidx.activity.ComponentActivity")
        # The explicit library activity theme wins over the application fallback. Require the
        # host declaration itself, so removing it cannot silently reintroduce the mismatch.
        self.assertEqual(theme, host.get(ANDROID + "theme"))
        mode = main.get(ANDROID + "windowSoftInputMode")
        self.assertIsNotNone(mode, "Production must declare its IME resize policy")
        self.assertEqual(set(mode.split("|")), set((host.get(ANDROID + "windowSoftInputMode") or "").split("|")))


if __name__ == "__main__": unittest.main()
