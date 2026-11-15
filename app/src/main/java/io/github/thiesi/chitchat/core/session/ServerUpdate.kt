package io.github.thiesi.chitchat.core.session

import io.github.thiesi.chitchat.core.network.apiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The minimum ChitChat release this app supports (AGENTS.md, server-update.md): settled SSE resume. */
internal const val MINIMUM_SERVER_VERSION = "3.10.0"
private val MINIMUM = listOf(3L, 10L, 0L)

/**
 * The facts server-update.md's rule reads from one `session.php` body, by key presence (an explicit `null` counts as
 * present). Facts only: [needsServerUpdate] decides.
 */
internal data class ServerDiscovery(
    val schemaVersion: Boolean,
    val version: String?,
    val policies: Boolean,
    val recoveryKey: Boolean,
)

/**
 * Runs only on a body that already passed the typed bootstrap decode. A body that isn't a JSON object gives no facts
 * (null), which is no verdict: a garbled response stays an ordinary failure, never "update your server".
 */
internal fun serverDiscovery(body: String): ServerDiscovery? {
    val root = try { apiJson.parseToJsonElement(body) as? JsonObject } catch (_: IllegalArgumentException) { null } ?: return null
    val capabilities = root["capabilities"] as? JsonObject
    val version = ((root["application"] as? JsonObject)?.get("version") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return ServerDiscovery(
        // Key presence, like every other fact: an explicit null is present, so only a missing key means pre-3.3.0.
        schemaVersion = capabilities?.containsKey("schema_version") == true,
        version = version,
        policies = root.containsKey("policies"),
        recoveryKey = (root["user"] as? JsonObject)?.containsKey("recovery_key") == true,
    )
}

/** The leading `major.minor.patch` as three non-negative integers, or null (unknown). */
internal fun parseServerVersion(version: String?): List<Long>? {
    val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(version ?: return null) ?: return null
    return match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
}

/**
 * server-update.md's Detection, exactly:
 * 1. no `capabilities.schema_version`: older than 3.3.0, so the state shows;
 * 2. otherwise `application.version` compared numerically with [MINIMUM_SERVER_VERSION];
 * 3. a missing or unparseable version is unknown, and shows the state only when every observable session-level
 *    feature key is also missing: `policies`, plus `user.recovery_key` for a signed-in member.
 * Per-feature keys never decide it on their own.
 */
internal fun needsServerUpdate(facts: ServerDiscovery, member: Boolean): Boolean {
    if (!facts.schemaVersion) return true
    val version = parseServerVersion(facts.version)
    if (version != null) {
        for (i in 0..2) if (version[i] != MINIMUM[i]) return version[i] < MINIMUM[i]
        return false
    }
    return !facts.policies && !(member && facts.recoveryKey)
}

/** `application.version` as reported, for the details line and the note only: bounded and printable, else unknown. */
internal fun displayedServerVersion(version: String?): String? =
    version?.trim()?.takeIf { it.length in 1..40 && it.none { c -> c.isISOControl() } }

/** P-183: the operator note holds the raw host, the minimum and the found version (or "unknown"), nothing else. */
internal fun operatorNote(address: String, found: String?): String =
    "Hi! The ChitChat Android app needs our server ($address) updated to ChitChat $MINIMUM_SERVER_VERSION or newer. " +
        "It currently runs ${found ?: "unknown"}."
