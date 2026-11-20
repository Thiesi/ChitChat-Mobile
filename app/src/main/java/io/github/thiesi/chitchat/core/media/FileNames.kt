package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import java.util.Locale

/**
 * Received file names are server text, never a path (received files F5; TL conditions of 2026-11-19 and 2026-11-20).
 *
 * [sanitizedFileName] is the name a share copy is written under and the title a Save suggests: no path separators or
 * `..`, no control characters, no Unicode format characters (category Cf, which includes the bidi overrides and isolates,
 * so `photo<RLO>gnp.apk` can't pose as `photo…apk.png`), nothing a common file system refuses, and a bounded length
 * that keeps the extension. [isApkLike] runs on that sanitized name.
 */
internal object FileNames {
    /** Long enough for real names, short enough for every provider; the extension is kept within it. */
    const val MAX_LENGTH = 120
    private const val MAX_EXTENSION = 16
    private const val FALLBACK = "file"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val APK_EXTENSIONS = setOf("apk", "apks", "xapk", "apkm")
    /** Refused or special on FAT/exFAT and Windows shares, which SAF providers may sit on. */
    private val RESERVED = setOf('<', '>', ':', '"', '|', '?', '*')
    /** The bidi controls a displayed name drops (the PL's list; ZWJ and ZWNJ are kept for scripts and emoji). */
    private val BIDI_CONTROLS = setOf('‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨',
        '⁩', '‎', '‏', '؜')

    fun sanitizedFileName(name: String?): String {
        val kept = StringBuilder()
        for (char in name.orEmpty()) {
            when {
                char == '/' || char == '\\' || char in RESERVED -> kept.append('_')
                Character.isISOControl(char) -> Unit
                Character.getType(char) == Character.FORMAT.toInt() -> Unit
                else -> kept.append(char)
            }
        }
        // No `..` anywhere, and no leading or trailing dots or spaces (a hidden file, or a name some providers trim).
        var cleaned = kept.toString()
        while (cleaned.contains("..")) cleaned = cleaned.replace("..", ".")
        cleaned = cleaned.trim { it == '.' || it.isWhitespace() }
        if (cleaned.isEmpty()) return FALLBACK
        return bounded(cleaned)
    }

    /** Within [MAX_LENGTH] UTF-16 units, cutting the stem and keeping a short extension, never splitting a surrogate pair. */
    private fun bounded(name: String): String {
        if (name.length <= MAX_LENGTH) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot - 1 in 1..MAX_EXTENSION) name.substring(dot) else ""
        var stem = name.substring(0, MAX_LENGTH - extension.length)
        if (stem.isNotEmpty() && Character.isHighSurrogate(stem.last())) stem = stem.dropLast(1)
        return stem.trimEnd { it == '.' || it.isWhitespace() }.ifEmpty { FALLBACK } + extension
    }

    /** The last extension of the sanitized name, lower-case, or null. */
    fun extension(name: String?): String? {
        val sanitized = sanitizedFileName(name)
        val dot = sanitized.lastIndexOf('.')
        return if (dot > 0 && dot < sanitized.length - 1) sanitized.substring(dot + 1).lowercase(Locale.ROOT) else null
    }

    /**
     * Save-only (TL): the APK MIME type, or a sanitized name ending in .apk, .apks, .xapk or .apkm (any case). Either is
     * enough; such a file never gets Share or Open, and isn't previewed as an image even under an image MIME type.
     */
    fun isApkLike(attachment: MessageAttachment): Boolean =
        attachment.mimeType.trim().equals(APK_MIME, ignoreCase = true) || extension(attachment.name) in APK_EXTENSIONS

    /**
     * The card's type label (PL, F5b): the real last extension of the bidi-stripped name, as F3 shows it, except that a
     * file that's APK-like by either rule always reads "APK", so a Save-only card never claims to be a PDF.
     */
    fun typeLabel(attachment: MessageAttachment): String? =
        if (isApkLike(attachment)) "APK" else attachmentExtension(withoutBidiControls(attachment.name))

    /** A name for display (F5b cards): the bidi controls dropped; the caller then renders it as an isolated run. */
    fun withoutBidiControls(name: String): String = name.filterNot { it in BIDI_CONTROLS }
}
