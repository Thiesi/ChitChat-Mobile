package io.github.thiesi.chitchat.core.media

/** Bootstrap facts only. Neither a policy nor a quota grants permission to upload. */
sealed interface AttachmentPolicy {
    /** The server did not advertise a policy. No size, type or quota defaults are implied. */
    data object Unknown : AttachmentPolicy
    /** Explicit null from bootstrap, also used for signed-out and guest sessions. */
    data object Unavailable : AttachmentPolicy
    /** Unusable advertised settings disable uploads without invalidating the session. */
    data object Invalid : AttachmentPolicy
    class Known internal constructor(
        val maxBytes: Long,
        val allowedMimeTypes: Set<String>,
        val previewableMimeTypes: Set<String>,
        val rateLimits: UploadRateLimits?,
    ) : AttachmentPolicy
}

data class UploadRateLimit(val maximumAttempts: Long, val windowSeconds: Long)
data class UploadRateLimits(val room: UploadRateLimit, val directMessage: UploadRateLimit)
