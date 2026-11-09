package io.github.thiesi.chitchat.core.media

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8

internal enum class AttachmentPickSource { FILE, PHOTO_PICKER }
internal enum class AttachmentPhotoKind { JPEG, PNG, WEBP, UNSUPPORTED_IMAGE, NON_PHOTO }

/** Privacy routing only, never a MIME-policy fact, valid-image proof or permission to send.
 * Complete signatures win; image MIME/Photo Picker may only escalate an unknown header.
 * Known signatures with truncated/invalid bodies still go to a cleaner or explicit choice.
 */
internal suspend fun attachmentPhotoKind(
    bytes: ByteString,
    mimeType: String?,
    source: AttachmentPickSource,
): AttachmentPhotoKind = withContext(Dispatchers.Default) {
    coroutineContext.ensureActive()
    if (bytes.size.toLong() > MAX_ATTACHMENT_SNAPSHOT_BYTES)
        throw AttachmentPreparationFailure(PreparationFailureKind.CLIENT_BUDGET)
    val kind = when {
        bytes.starts(JPEG) -> AttachmentPhotoKind.JPEG
        bytes.starts(PNG) -> AttachmentPhotoKind.PNG
        bytes.starts(RIFF) && bytes.matches(8, WEBP) -> AttachmentPhotoKind.WEBP
        OTHER_IMAGES.any(bytes::starts) || bytes.hasIsoImageBrand() -> AttachmentPhotoKind.UNSUPPORTED_IMAGE
        source == AttachmentPickSource.PHOTO_PICKER || mimeType?.startsWith("image/", ignoreCase = true) == true ->
            AttachmentPhotoKind.UNSUPPORTED_IMAGE
        else -> AttachmentPhotoKind.NON_PHOTO
    }
    coroutineContext.ensureActive()
    kind
}

private fun ByteString.matches(at: Int, marker: ByteString) =
    at >= 0 && marker.size <= size - at && rangeEquals(at, marker, 0, marker.size)
private fun ByteString.starts(marker: ByteString) = matches(0, marker)
private fun ByteString.word(at: Int): Int =
    ((this[at].toInt() and 255) shl 24) or ((this[at + 1].toInt() and 255) shl 16) or
        ((this[at + 2].toInt() and 255) shl 8) or (this[at + 3].toInt() and 255)

private suspend fun ByteString.hasIsoImageBrand(): Boolean {
    if (!matches(4, FTYP) || size < 12) return false
    val shortSize = word(0).toLong() and 0xffffffffL
    val data = if (shortSize == 1L) 16 else 8
    if (size < data + 4) return false
    // This is a signature candidate, not a container validator. A known image major
    // brand cannot be downgraded by a truncated body or a corrupt declared size.
    if (imageBrand(word(data))) return true
    val declared = when (shortSize) {
        0L -> size.toLong()
        1L -> {
            // Unsigned extended lengths larger than our entire bounded snapshot
            // are clamped without a signed overflow or allocation from the length.
            val high = word(8).toLong() and 0xffffffffL
            if (high != 0L) size.toLong() else word(12).toLong() and 0xffffffffL
        }
        else -> shortSize
    }
    val end = minOf(declared, size.toLong()).toInt()
    var at = data + 8 // Skip minor_version; its four bytes are never a compatible brand.
    while (at <= end - 4) {
        if ((at and 8191) == 0) coroutineContext.ensureActive()
        if (imageBrand(word(at))) return true
        at += 4
    }
    return false
}

private fun imageBrand(value: Int) = when (value) {
    0x6d696631, 0x6d736631, // mif1, msf1: HEIF structures
    0x68656963, 0x68656978, 0x68657663, 0x68657678, // heic, heix, hevc, hevx
    0x6865696d, 0x68656973, 0x6865766d, 0x68657673, // heim, heis, hevm, hevs
    0x61766966, 0x61766973 -> true // avif, avis
    else -> false
}

private val JPEG = "ffd8ff".decodeHex()
private val PNG = "89504e470d0a1a0a".decodeHex()
private val RIFF = "RIFF".encodeUtf8()
private val WEBP = "WEBP".encodeUtf8()
private val FTYP = "ftyp".encodeUtf8()
private val OTHER_IMAGES = listOf(
    "GIF87a".encodeUtf8(), "GIF89a".encodeUtf8(), "BM".encodeUtf8(),
    "49492a00".decodeHex(), "4d4d002a".decodeHex(), // TIFF, including DNG-style raw
    "49492b00".decodeHex(), "4d4d002b".decodeHex(), // BigTIFF
    "4949524f".decodeHex(), "49495253".decodeHex(), "4d4d4f52".decodeHex(), "4d4d5352".decodeHex(), // ORF
    "49495500".decodeHex(), "4d4d0055".decodeHex(), // RW2-style raw
    "FUJIFILMCCD-RAW".encodeUtf8(),
)
