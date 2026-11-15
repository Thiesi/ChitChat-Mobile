package io.github.thiesi.chitchat.core.policies

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * Installation policies (ChitChat v3.10.0 docs/api/policies.md; play-readiness.md §1, P-176). Discovery is public and
 * presence-gated: a missing `policies` key means unknown (older server), an entry's `null` means None (not published).
 * Documents are informational here; rules acceptance is O3b. Nothing is cached on disk.
 */

/** One published document as discovery names it. [url] is absolute and only ever opened through an explicit choice. */
data class PolicyLink(val url: String, val version: Int?, val updatedAt: String?)

/** `session.php` `policies`; each document null when None (or malformed: no row, never a guess). */
data class PolicyDiscovery(val rules: PolicyLink?, val privacy: PolicyLink?, val legalNotice: PolicyLink?)

/** The documents `policies/show.php` serves. The legal notice is a link only, with no document read. */
enum class PolicyDocumentName(val wire: String) { RULES("rules"), PRIVACY("privacy") }

/** A `policies/show.php` read. Server HTML is never decoded or shown; hosted documents render their Markdown. */
sealed interface PolicyDocument {
    data class Hosted(val markdown: String, val version: Int?, val updatedAt: String?, val changeSummary: String?) : PolicyDocument
    data class External(val url: String) : PolicyDocument
    /** `404 policy_document_not_found`: the installation hasn't published it. Not an error. */
    data object NotPublished : PolicyDocument
}

/** Null when the server sent no `policies` object (unknown); otherwise each entry decoded leniently. */
internal fun policyDiscovery(field: JsonElement?): PolicyDiscovery? {
    val root = field as? JsonObject ?: return null
    return PolicyDiscovery(policyLink(root["rules"]), policyLink(root["privacy"]), policyLink(root["legal_notice"]))
}

private fun policyLink(field: JsonElement?): PolicyLink? {
    val entry = field as? JsonObject ?: return null
    val url = policyUrl(entry["url"]) ?: return null
    val version = (entry["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 1 }
    val updated = (entry["updated_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length in 1..64 }
    return PolicyLink(url, version, updated)
}

/** A non-empty string of at most 2,048 characters without whitespace or controls. Its scheme is judged where it opens. */
internal fun policyUrl(field: JsonElement?): String? =
    (field as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotEmpty() && it.length <= 2048 && it.none { c -> c.isWhitespace() || c.isISOControl() } }
