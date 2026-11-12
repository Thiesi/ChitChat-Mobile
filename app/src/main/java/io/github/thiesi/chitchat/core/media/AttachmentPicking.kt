package io.github.thiesi.chitchat.core.media

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** System picker filters from the cached bootstrap policy. A filter is a convenience for the
 * person, never authority: the original-session transport and the server still decide.
 */
internal class AttachmentPickFilters(val documentTypes: List<String>, val photo: PhotoPickerFilter?)

internal sealed interface PhotoPickerFilter {
    /** All images. Types the server doesn't advertise are then refused by the card pre-check. */
    data object Images : PhotoPickerFilter
    class Single(val mimeType: String) : PhotoPickerFilter
}

internal fun attachmentPickFilters(policy: AttachmentPolicy): AttachmentPickFilters {
    val known = policy as? AttachmentPolicy.Known
        ?: return AttachmentPickFilters(listOf("*/*"), PhotoPickerFilter.Images) // Missing policy: no filter.
    val allowed = known.allowedMimeTypes.map { it.lowercase(Locale.ROOT) }.distinct().sorted()
    val images = allowed.filter { it.startsWith("image/") && it != "image/*" }
    return AttachmentPickFilters(allowed.ifEmpty { listOf("*/*") }, when (images.size) {
        0 -> null // No allowed image type: the Photo row is left out.
        1 -> PhotoPickerFilter.Single(images.single())
        else -> PhotoPickerFilter.Images
    })
}

/** Provider display names are untrusted hints. Keep a valid upload basename without inventing
 * a type; the server detects the content. Truncation keeps a short extension visible.
 */
internal fun pickedAttachmentFilename(displayName: String?): String {
    val cleaned = displayName.orEmpty().map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }
        .joinToString("").trim()
    val name = cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: return FALLBACK_NAME
    if (name.length <= MAX_NAME) return name
    val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..16 && it.length < name.length - 1 }
    val tail = extension?.let { ".$it" }.orEmpty()
    var head = name.take(MAX_NAME - tail.length)
    if (head.lastOrNull()?.isHighSurrogate() == true) head = head.dropLast(1)
    return (head + tail).takeUnless { it.isBlank() || it == "." || it == ".." } ?: FALLBACK_NAME
}

/** A provider type that isn't one concrete media type is unknown, never guessed. */
internal fun pickedAttachmentMimeType(raw: String?): String? {
    val type = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    return try { validateAttachmentMetadata(FALLBACK_NAME, type); type } catch (_: IllegalArgumentException) { null }
}

internal enum class AttachmentSizeUnit { BYTES, KB, MB, GB }
internal class AttachmentSizeText(val number: String, val unit: AttachmentSizeUnit)

/** Decimal units, matching Android's own file-size display since API 26. */
internal fun attachmentSizeText(bytes: Long, locale: Locale, decimals: Int = 1): AttachmentSizeText {
    require(bytes >= 0) { "Invalid size" }
    val units = listOf(AttachmentSizeUnit.KB to 1_000L, AttachmentSizeUnit.MB to 1_000_000L, AttachmentSizeUnit.GB to 1_000_000_000L)
    val (unit, divisor) = units.lastOrNull { bytes >= it.second } ?: return AttachmentSizeText(
        NumberFormat.getIntegerInstance(locale).format(bytes), AttachmentSizeUnit.BYTES)
    val value = BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(divisor))
    val digits = if (value >= BigDecimal(100) && decimals <= 1) 0 else decimals
    val format = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0; maximumFractionDigits = digits; roundingMode = RoundingMode.HALF_UP
    }
    return AttachmentSizeText(format.format(value.setScale(digits, RoundingMode.HALF_UP)), unit)
}

/** The size and limit use one formatter. Add decimals until a refused size never reads the same
 * as the limit ("10.4 MB" against "10.4 MB").
 */
internal fun attachmentSizeComparison(sizeBytes: Long, limitBytes: Long, locale: Locale): Pair<AttachmentSizeText, AttachmentSizeText> {
    var decimals = 1
    while (true) {
        val size = attachmentSizeText(sizeBytes, locale, decimals)
        val limit = attachmentSizeText(limitBytes, locale, decimals)
        if (sizeBytes == limitBytes || size.unit != limit.unit || size.number != limit.number || decimals >= 9)
            return size to limit
        decimals++
    }
}

/** The plural noun phrase for a refused type, or null when there's no honest short name. */
internal sealed interface AttachmentKindText {
    class Images(val name: String) : AttachmentKindText
    class Videos(val name: String) : AttachmentKindText
    class Audio(val name: String) : AttachmentKindText
    class Files(val extension: String) : AttachmentKindText
}

internal fun attachmentKindText(mimeType: String?, filename: String): AttachmentKindText? {
    val type = mimeType?.lowercase(Locale.ROOT)
    val subtype = type?.substringAfter('/', "")?.substringBefore('+')?.removePrefix("x-")
        ?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it.length <= 12 && it.all(Char::isLetterOrDigit) }
    if (subtype != null) when (type.substringBefore('/')) {
        "image" -> return AttachmentKindText.Images(subtype)
        "video" -> return AttachmentKindText.Videos(subtype)
        "audio" -> return AttachmentKindText.Audio(subtype)
    }
    return attachmentExtension(filename)?.let(AttachmentKindText::Files)
}

/** The visible short type label on the card ("PDF"), from the name only. */
internal fun attachmentExtension(filename: String): String? = filename.substringAfterLast('.', "")
    .takeIf { filename.lastIndexOf('.') > 0 && it.length in 1..8 && it.all(Char::isLetterOrDigit) }
    ?.uppercase(Locale.ROOT)

internal sealed interface UploadRateWindow {
    data object Hour : UploadRateWindow
    class Minutes(val minutes: Long) : UploadRateWindow
    class Seconds(val seconds: Long) : UploadRateWindow
}

/** Phrases the advertised window only. It is never a live remaining-attempts counter. */
internal fun uploadRateWindow(windowSeconds: Long): UploadRateWindow = when {
    windowSeconds == 3_600L -> UploadRateWindow.Hour
    windowSeconds >= 60 && windowSeconds % 60 == 0L -> UploadRateWindow.Minutes(windowSeconds / 60)
    else -> UploadRateWindow.Seconds(windowSeconds)
}

private const val FALLBACK_NAME = "attachment"
private const val MAX_NAME = 255
