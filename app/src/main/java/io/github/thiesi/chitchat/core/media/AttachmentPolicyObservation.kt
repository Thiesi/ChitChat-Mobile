package io.github.thiesi.chitchat.core.media

import java.util.Locale

/** Cached advertised facts only, never server permission, a refusal or send/consent authority. */
internal sealed interface AttachmentPolicyObservation {
    data object AwaitingFinalBytes : AttachmentPolicyObservation
    data object Unadvertised : AttachmentPolicyObservation
    data object WithinAdvertisedFacts : AttachmentPolicyObservation
    data object Unavailable : AttachmentPolicyObservation
    data object Invalid : AttachmentPolicyObservation
    data object UploadsDisabled : AttachmentPolicyObservation
    class TooLarge(val sizeBytes: Long, val maxBytes: Long) : AttachmentPolicyObservation
    class TypeNotAllowed(val mimeType: String) : AttachmentPolicyObservation
}

/** A size is supplied only for the immutable eventual upload bytes. Original photo size is
 * not final evidence before cleaning or an explicit unchanged-photo choice. MIME is the
 * frozen provider hint; absence/octet-stream leaves detection to the server.
 */
internal fun observeAttachmentPolicy(policy: AttachmentPolicy, finalSizeBytes: Long?,
    mimeType: String?): AttachmentPolicyObservation {
    require(finalSizeBytes == null || finalSizeBytes > 0) { "Invalid prepared attachment size" }
    return when (policy) {
        AttachmentPolicy.Unavailable -> AttachmentPolicyObservation.Unavailable
        AttachmentPolicy.Invalid -> AttachmentPolicyObservation.Invalid
        AttachmentPolicy.Unknown -> if (finalSizeBytes == null) AttachmentPolicyObservation.AwaitingFinalBytes
            else AttachmentPolicyObservation.Unadvertised
        is AttachmentPolicy.Known -> {
            val mime = mimeType?.lowercase(Locale.ROOT)
            when {
                policy.maxBytes <= 0 -> AttachmentPolicyObservation.UploadsDisabled
                finalSizeBytes != null && finalSizeBytes > policy.maxBytes ->
                    AttachmentPolicyObservation.TooLarge(finalSizeBytes, policy.maxBytes)
                mime != null && mime != "application/octet-stream" && mime !in policy.allowedMimeTypes ->
                    AttachmentPolicyObservation.TypeNotAllowed(mime)
                finalSizeBytes == null -> AttachmentPolicyObservation.AwaitingFinalBytes
                else -> AttachmentPolicyObservation.WithinAdvertisedFacts
            }
        }
    }
}
