package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5: server file names are never a path (TL conditions of 2026-11-19 and 2026-11-20). */
class FileNamesTest {
    private fun name(value: String?) = FileNames.sanitizedFileName(value)
    private fun file(name: String, mime: String = "application/octet-stream") =
        MessageAttachment(1, name, mime, 10, "a".repeat(64), previewable = mime.startsWith("image/"))

    @Test fun pathSeparatorsAndTraversalNeverSurvive() {
        for (hostile in listOf("../../etc/passwd", "..\\..\\boot.ini", "a/../../b", "/abs/path.txt", "....//x")) {
            val sanitized = name(hostile)
            assertFalse(hostile, sanitized.contains('/') || sanitized.contains('\\'))
            assertFalse(hostile, sanitized.contains(".."))
            assertFalse(hostile, sanitized.startsWith("."))
        }
        assertEquals("a_._b", name("a/../b"))
    }

    @Test fun controlAndFormatCharactersAreDropped() {
        assertEquals("report.pdf", name("re\u0000po\u0007rt\n.pdf"))
        // The bidi override can't make an APK pose as a PNG (TL): the format characters go before anything else checks.
        assertEquals("photognp.apk", name("photo‮gnp.apk"))
        assertEquals("ab.txt", name("a⁦b⁩.txt"))
        assertEquals("ab.txt", name("a​b﻿.txt"))
    }

    @Test fun reservedCharactersBecomeUnderscores() {
        assertEquals("a_b_c_d_.txt", name("a:b*c?d\".txt"))
    }

    @Test fun emptyOrDotOnlyNamesFallBack() {
        assertEquals("file", name(null))
        assertEquals("file", name(""))
        assertEquals("file", name(" .. "))
        assertEquals("file", name("‮\u0000"))
        assertEquals("hidden", name(".hidden"))
    }

    @Test fun longNamesKeepTheirExtensionWithinTheLimit() {
        val long = "x".repeat(400) + ".pdf"
        val sanitized = name(long)
        assertEquals(FileNames.MAX_LENGTH, sanitized.length)
        assertTrue(sanitized.endsWith(".pdf"))
        // A surrogate pair is never split at the cut.
        val emoji = "😀".repeat(100) + ".png"
        val cut = name(emoji)
        assertTrue(cut.length <= FileNames.MAX_LENGTH)
        assertFalse(Character.isHighSurrogate(cut[cut.length - 5]))
        assertTrue(cut.endsWith(".png"))
        // A very long "extension" isn't kept as one.
        assertEquals(FileNames.MAX_LENGTH, name("a." + "b".repeat(300)).length)
    }

    @Test fun apkLikeIsTheMimeTypeOrTheSanitizedExtension() {
        assertTrue(FileNames.isApkLike(file("tool.bin", "application/vnd.android.package-archive")))
        for (apk in listOf("app.apk", "APP.APK", "bundle.apks", "game.xapk", "mirror.apkm")) assertTrue(apk, FileNames.isApkLike(file(apk)))
        // Under an image type too, and through a bidi override.
        assertTrue(FileNames.isApkLike(file("pic.apk", "image/png")))
        assertTrue(FileNames.isApkLike(file("photo‮gnp.apk", "image/png")))
        assertFalse(FileNames.isApkLike(file("apk")))
        assertFalse(FileNames.isApkLike(file("notes.apk.pdf", "application/pdf")))
        assertFalse(FileNames.isApkLike(file("photo.png", "image/png")))
    }

    @Test fun displayNamesDropBidiControlsButKeepJoiners() {
        assertEquals("photognp.apk", FileNames.withoutBidiControls("photo‮gnp.apk"))
        assertEquals("a‍b‌c", FileNames.withoutBidiControls("a‍b‌c‎‏؜⁦⁩"))
    }
}
