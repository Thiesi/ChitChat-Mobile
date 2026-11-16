package io.github.thiesi.chitchat.core.settings

import kotlinx.coroutines.flow.Flow

/*
 * The app's own community guidelines, accepted per server and account on this device (play-readiness.md §1, P-170).
 * Only the minimum is kept: the server origin, the account ID and the guidelines version (presence means accepted).
 * No names, text or tokens. It lives in the existing `settings` DataStore, which the manifest excludes from backup and
 * device transfer (allowBackup=false, data_extraction_rules.xml). Sign-out keeps it ("already agreed on this device");
 * forgetting the server removes that server's records.
 */

/** The current app guidelines version. A higher version shows the guidelines screen again. */
const val APP_GUIDELINES_VERSION = 1

data class GuidelinesAcceptance(val origin: String, val accountId: Long, val version: Int)

interface GuidelinesAcceptanceStore {
    val accepted: Flow<Set<GuidelinesAcceptance>>
    /** Records [acceptance], replacing any older record for the same server and account. */
    suspend fun record(acceptance: GuidelinesAcceptance)
    /** Removes every record for [origin] (the person told the app to forget that server). */
    suspend fun forgetServer(origin: String)
}

/** Whether [origin]/[accountId] accepted at least the current guidelines version. */
fun Set<GuidelinesAcceptance>.covers(origin: String, accountId: Long, version: Int = APP_GUIDELINES_VERSION): Boolean =
    any { it.origin == origin && it.accountId == accountId && it.version >= version }

private val ORIGIN = Regex("https://[^\\s]{1,2040}")

/** One stored entry: "origin accountId version". Anything malformed is dropped on read, never guessed. */
internal fun encodeAcceptance(acceptance: GuidelinesAcceptance): String? =
    "${acceptance.origin} ${acceptance.accountId} ${acceptance.version}".takeIf {
        ORIGIN.matches(acceptance.origin) && acceptance.accountId > 0 && acceptance.version > 0
    }

internal fun decodeAcceptance(entry: String): GuidelinesAcceptance? {
    val parts = entry.split(' ')
    if (parts.size != 3 || !ORIGIN.matches(parts[0])) return null
    val id = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val version = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return GuidelinesAcceptance(parts[0], id, version)
}

/** [entries] with [acceptance] replacing any older record for the same server and account. */
internal fun withAcceptance(entries: Set<String>, acceptance: GuidelinesAcceptance): Set<String> {
    val encoded = encodeAcceptance(acceptance) ?: return entries
    return entries.filterNot { entry -> decodeAcceptance(entry)?.let { it.origin == acceptance.origin && it.accountId == acceptance.accountId } != false }
        .toSet() + encoded
}

/** [entries] without any record for [origin]; malformed entries are dropped too. */
internal fun withoutServer(entries: Set<String>, origin: String): Set<String> =
    entries.filter { entry -> decodeAcceptance(entry)?.let { it.origin != origin } == true }.toSet()
