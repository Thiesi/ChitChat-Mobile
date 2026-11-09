package io.github.thiesi.chitchat.core.media

/** Prepared display hints, never a provider path or an authority to upload. */
internal fun validateAttachmentMetadata(filename: String, mimeType: String?) {
    require(filename.isNotBlank() && filename.length <= 255 && filename !in setOf(".", "..") &&
        filename.none { it.isISOControl() || it == '/' || it == '\\' }) { "Invalid upload basename" }
    require(mimeType == null || (mimeType.matches(Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+/[!#\$%&'*+.^_`|~0-9A-Za-z-]+")) &&
        '*' !in mimeType)) { "Invalid upload media type" }
}
