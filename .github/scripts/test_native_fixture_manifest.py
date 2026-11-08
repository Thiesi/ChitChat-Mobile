"""Guard the app/native-fixture window contract before any device invocation."""
from pathlib import Path
import unittest
import re
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
        # The explicit library activity theme wins over the application fallback. Require the
        # host declaration itself, so removing it cannot silently reintroduce the mismatch.
        mode = main.get(ANDROID + "windowSoftInputMode")
        self.assertIsNotNone(mode, "Production must declare its IME resize policy")
        for name in ("androidx.activity.ComponentActivity", "io.github.thiesi.chitchat.testing.NativeWindowTestActivity"):
            host = next(a for a in debug.findall("activity") if a.get(ANDROID + "name") == name)
            with self.subTest(host=name):
                self.assertEqual(theme, host.get(ANDROID + "theme"))
                self.assertEqual(set(mode.split("|")), set((host.get(ANDROID + "windowSoftInputMode") or "").split("|")))

    def test_fixture_reuses_the_production_post_super_window_and_theme_callback(self):
        source_root = ROOT / "app/src"
        main = (source_root / "main/java/io/github/thiesi/chitchat/MainActivity.kt").read_text()
        host = (source_root / "debug/java/io/github/thiesi/chitchat/testing/NativeWindowTestActivity.kt").read_text()
        for source, content in ((main, "setContent {"), (host, "createContent?.invoke()")):
            create = source[source.index("override fun onCreate("):]
            self.assertLess(create.index("super.onCreate(savedInstanceState)"), create.index("configureChatWindow()"))
            self.assertLess(create.index("configureChatWindow()"), create.index(content))
            self.assertIn("import io.github.thiesi.chitchat.core.ui.configureChatWindow", source)
        self.assertRegex(main, r"onThemeChanged\s*=\s*\{\s*dark\s*->\s*configureChatWindow\(dark\)")
        test_root = source_root / "androidTest/java/io/github/thiesi/chitchat/feature"
        reply = (test_root / "conversation/ReplyComposerJourneyTest.kt").read_text()
        self.assertIn("createAndroidComposeRule<NativeWindowTestActivity>()", reply)
        self.assertIn("onThemeChanged = { activity.configureChatWindow(it) }", reply)
        wrapper = (test_root / "account/TestAccounts.kt").read_text()
        self.assertEqual(2, len(re.findall(r"onThemeChanged\s*=\s*onThemeChanged", wrapper)))


if __name__ == "__main__": unittest.main()
