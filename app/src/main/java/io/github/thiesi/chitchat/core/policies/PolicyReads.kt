package io.github.thiesi.chitchat.core.policies

import io.github.thiesi.chitchat.core.session.SessionState

/**
 * The public document read (policies.md `policies/show.php`), signed in or not. Fenced to the rendered session's
 * server and generation; refused while the server-update state shows (nothing new starts, server-update.md).
 */
interface PolicyReads {
    suspend fun policyDocument(name: PolicyDocumentName, expected: SessionState): PolicyDocument
}
