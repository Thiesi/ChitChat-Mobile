package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.session.PushTeardown

/**
 * The session's local teardown with share copies added (F5): runs at exactly push's hook points, inside the session
 * lock and off Main, so copies follow the same account lifecycle as push keys (TL, 2026-11-20).
 *
 * - [retire] (sign-out, an ended session, the account-deletion teardown) deletes that account's whole copy folder,
 *   revoking every grant first.
 * - [sessionChanged] keeps only the current account's folder. "Current" uses push's own predicate: a session exists
 *   only with both a user ID and a sign-in time; anything else is no session. That state only reaches here after a
 *   completed bootstrap (the server says anonymous) or at a new sign-in's MFA step, both after any earlier account has
 *   already been retired; a cold start, a revalidation or the update screen never call it without the user. In-flight
 *   copies are never deleted here: their owner's session fence cancels and discards them.
 */
internal class ShareCopyTeardown(private val push: PushTeardown, private val copies: ShareCopies) : PushTeardown {
    override fun retire(origin: String?, userId: Long?): Long? {
        // An unknown account (an anonymous session ending) leaves no signed-in account: every folder not in flight goes.
        runCatching {
            if (origin != null && userId != null) copies.deleteAccount(ShareCopies.accountKey(origin, userId)) else copies.keepOnly(null)
        }
        return push.retire(origin, userId)
    }

    override fun sessionChanged(origin: String, userId: Long?, signedInAt: Long?) {
        push.sessionChanged(origin, userId, signedInAt)
        val current = if (userId != null && signedInAt != null) ShareCopies.accountKey(origin, userId) else null
        runCatching { copies.keepOnly(current) }
    }
}
