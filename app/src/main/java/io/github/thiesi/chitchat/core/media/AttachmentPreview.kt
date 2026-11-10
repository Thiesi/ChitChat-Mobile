package io.github.thiesi.chitchat.core.media

import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Private server metadata only. This descriptor grants no fetch, cache reuse or allocation. */
@Serializable class AttachmentPreview(val url: String, val width: Int, val height: Int,
    @SerialName("mime_type") val mimeType: String, @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String) {
    internal fun validFor(attachmentId: Long, direct: Boolean): Boolean {
        if (attachmentId <= 0 || width !in 1..1024 || height !in 1..1024 ||
            mimeType != "image/webp" || sizeBytes !in 1..4L * 1024 * 1024 ||
            !sha256.matches(Regex("[a-fA-F0-9]{64}")) || url.length !in 1..256) return false
        val parsed = try { URI(url) } catch (_: URISyntaxException) { return false }
        val path = if (direct) "/api/v1/direct-messages/attachments/download.php" else "/api/v1/attachments/download.php"
        if (parsed.isAbsolute || parsed.rawAuthority != null || parsed.rawFragment != null || parsed.rawPath != path) return false
        val parts = parsed.rawQuery?.split('&') ?: return false
        if (parts.size != 2) return false
        val fields = parts.map { it.split('=', limit = 2) }
        if (fields.any { it.size != 2 } || fields.map { it[0] }.toSet() != setOf("id", "variant")) return false
        val values = fields.associate { it[0] to it[1] }
        return values["id"] == attachmentId.toString() && values["variant"] == "preview"
    }
    override fun toString() = "AttachmentPreview"
}

/** Oriented header geometry for framing only; never an image-buffer allocation budget. */
class AttachmentDimensions internal constructor(val width: Int, val height: Int) {
    override fun toString() = "AttachmentDimensions"
}
