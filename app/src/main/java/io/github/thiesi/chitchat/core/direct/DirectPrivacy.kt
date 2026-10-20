package io.github.thiesi.chitchat.core.direct

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind

/** Shared by normal conversations and exact-target lookup; missing disclosure is never E2EE. */
internal fun validatedDirectPrivacy(value: MessagePrivacy?): MessagePrivacy {
    value ?: throw ApiFailure(FailureKind.INCOMPATIBLE)
    val direct = value.direct
    if (direct.endToEndEncrypted || direct.retentionDays < 0 || direct.retention.isBlank() ||
        (direct.inspectionEnabled && direct.inspectionRole !in setOf("admin", "super_admin")) ||
        (value.revisions.reviewEnabled && value.revisions.reviewRole !in setOf("admin", "super_admin")))
        throw ApiFailure(FailureKind.INCOMPATIBLE)
    return value
}
