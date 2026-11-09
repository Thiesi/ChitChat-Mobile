package io.github.thiesi.chitchat.core.media

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.ByteString

internal enum class AttachmentPhotoPrivacy { NON_PHOTO, LOCATION_REMOVED, UNCHANGED_AFTER_CHOICE }
internal enum class PhotoPrivacyFailure { CLEANING_FAILED, UNSUPPORTED_IMAGE }
internal sealed interface AttachmentPreparation {
    class Ready(val payload: PreparedAttachmentPayload, val privacy: AttachmentPhotoPrivacy) : AttachmentPreparation
    /** No prepared payload and no consent. A later actual-entry/owner-fenced consumer
     * must bind an explicit Send anyway to THIS choice and original snapshot before
     * preparing its unchanged bytes. Never adopt an old result into a new selection.
     */
    class NeedsChoice(val original: PickedAttachmentSnapshot, val kind: PhotoPrivacyFailure) : AttachmentPreparation
}

/** One frozen snapshot only. No provider reopen, POST, retry or implicit original-byte fallback.
 * Successful results retain only the eventual payload; selection/consent/aggregate-budget
 * ownership and native controls are deliberately still feature-owned prerequisites.
 */
internal suspend fun prepareAttachment(
    original: PickedAttachmentSnapshot,
    source: AttachmentPickSource,
): AttachmentPreparation = withContext(Dispatchers.Default) {
    coroutineContext.ensureActive()
    validateAttachmentMetadata(original.filename, original.mimeType)
    if (original.bytes.size == 0) throw AttachmentPreparationFailure(PreparationFailureKind.EMPTY)
    if (original.bytes.size.toLong() > MAX_ATTACHMENT_SNAPSHOT_BYTES)
        throw AttachmentPreparationFailure(PreparationFailureKind.CLIENT_BUDGET)
    suspend fun ready(bytes: ByteString, privacy: AttachmentPhotoPrivacy) = AttachmentPreparation.Ready(
        PreparedAttachmentPayload.create(original.filename, original.mimeType, bytes), privacy)
    fun choice(kind: PhotoPrivacyFailure) = AttachmentPreparation.NeedsChoice(original, kind)
    fun budget(): Nothing = throw AttachmentPreparationFailure(PreparationFailureKind.CLIENT_BUDGET)
    val result = when (attachmentPhotoKind(original.bytes, original.mimeType, source)) {
        AttachmentPhotoKind.NON_PHOTO -> ready(original.bytes, AttachmentPhotoPrivacy.NON_PHOTO)
        AttachmentPhotoKind.UNSUPPORTED_IMAGE -> choice(PhotoPrivacyFailure.UNSUPPORTED_IMAGE)
        AttachmentPhotoKind.JPEG -> when (val cleaned = cleanJpegPhoto(original.bytes)) {
            is JpegPhotoCleaning.Cleaned -> ready(cleaned.bytes, AttachmentPhotoPrivacy.LOCATION_REMOVED)
            is JpegPhotoCleaning.Failed -> when (cleaned.kind) {
                JpegCleaningFailure.CLIENT_BUDGET -> budget()
                JpegCleaningFailure.INVALID_JPEG -> choice(PhotoPrivacyFailure.CLEANING_FAILED)
                JpegCleaningFailure.UNSUPPORTED_LAYOUT -> choice(PhotoPrivacyFailure.CLEANING_FAILED)
            }
        }
        AttachmentPhotoKind.PNG -> when (val cleaned = cleanPngPhoto(original.bytes)) {
            is PngPhotoCleaning.Cleaned -> ready(cleaned.bytes, AttachmentPhotoPrivacy.LOCATION_REMOVED)
            is PngPhotoCleaning.Failed -> when (cleaned.kind) {
                PngCleaningFailure.CLIENT_BUDGET -> budget()
                PngCleaningFailure.INVALID_PNG -> choice(PhotoPrivacyFailure.CLEANING_FAILED)
                PngCleaningFailure.UNSUPPORTED_LAYOUT -> choice(PhotoPrivacyFailure.CLEANING_FAILED)
            }
        }
        AttachmentPhotoKind.WEBP -> when (val cleaned = cleanWebpPhoto(original.bytes)) {
            is WebpPhotoCleaning.Cleaned -> ready(cleaned.bytes, AttachmentPhotoPrivacy.LOCATION_REMOVED)
            is WebpPhotoCleaning.Failed -> when (cleaned.kind) {
                WebpCleaningFailure.CLIENT_BUDGET -> budget()
                WebpCleaningFailure.INVALID_WEBP -> choice(PhotoPrivacyFailure.CLEANING_FAILED)
                WebpCleaningFailure.UNSUPPORTED_LAYOUT -> choice(PhotoPrivacyFailure.CLEANING_FAILED)
            }
        }
    }
    coroutineContext.ensureActive()
    result
}
