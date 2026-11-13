package io.github.thiesi.chitchat.core.push

import io.github.thiesi.chitchat.core.network.apiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * A decrypted push: the server's display-safe text plus identifiers. Never logged; no string form.
 * Text keeps the server's own trimming ("…") exactly as received.
 */
class PushPayload(
    val title: String,
    val body: String,
    val link: String?,
    val appName: String?,
    val kind: String?,
    val notificationId: Long?,
    val eventId: String?,
    val binding: String?,
) {
    override fun toString() = "PushPayload"

    companion object {
        /** The server keeps the plaintext at or under 2,999 bytes; anything far larger isn't ours. */
        const val MAX_BYTES = 4_096

        /** Strict: required text must be strings, optional fields must be well formed or absent, else null. */
        fun parse(content: ByteArray): PushPayload? {
            if (content.isEmpty() || content.size > MAX_BYTES) return null
            val json = try { apiJson.parseToJsonElement(content.decodeToString(throwOnInvalidSequence = true)).jsonObject }
                catch (_: Exception) { return null }
            val title = json.text("title") ?: return null
            val body = json.text("body") ?: return null
            val link = json.optionalText("link") ?: return null
            val appName = json.optionalText("app_name") ?: return null
            val kind = json.optionalText("kind") ?: return null
            val eventId = json.optionalText("event_id") ?: return null
            val binding = json.optionalText("binding") ?: return null
            val notification = json["notification_id"]
            val notificationId = when {
                notification == null -> null
                notification is JsonPrimitive && !notification.isString && notification.booleanOrNull == null ->
                    notification.longOrNull?.takeIf { it > 0 } ?: return null
                else -> return null
            }
            if (kind.value?.let { k -> k.length in 1..64 && k.all { it in 'a'..'z' || it == '_' } } == false) return null
            if (eventId.value?.let { e -> e.length in 1..64 && e.all { it.code in 0x21..0x7e } } == false) return null
            if (binding.value?.let { b -> b.length == 64 && b.all { it in '0'..'9' || it in 'a'..'f' } } == false) return null
            return PushPayload(title, body, link.value, appName.value, kind.value, notificationId, eventId.value, binding.value)
        }

        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** Absent or null is a present-but-empty [Optional]; a non-string is a malformed payload (null). */
        private fun JsonObject.optionalText(name: String): Optional? = when (val value = this[name]) {
            null, kotlinx.serialization.json.JsonNull -> Optional(null)
            is JsonPrimitive -> if (value.isString) Optional(value.content) else null
            else -> null
        }

        private class Optional(val value: String?)
    }
}

/** Where a push goes. Fixed table on the explicit `kind`; never guessed from text. */
enum class PushKind(val channel: String) {
    MENTION(PushChannels.MENTIONS), PING(PushChannels.MENTIONS), DIRECT(PushChannels.DIRECT), OTHER(PushChannels.ACCOUNT);

    companion object {
        fun of(kind: String?): PushKind = when (kind) {
            "mentioned" -> MENTION
            "pinged" -> PING
            "direct_message_received" -> DIRECT
            else -> OTHER // Known or unknown, a missing kind included: never a high-importance channel.
        }
    }
}

/** What to post: the checked payload with its channel, tag and group, all scoped by server and account. */
class PushAlert(val payload: PushPayload, val kind: PushKind, val tag: String?, val group: String) {
    override fun toString() = "PushAlert"
}

/**
 * The only door from a decrypted message to a notification. Shown only when the sealed record for
 * [instance] exists and the payload's binding equals the record's exactly: a missing binding once this
 * phone holds one, an unknown one, or one without a recorded binding are all dropped silently.
 */
fun pushAlert(record: PushRecord?, instance: String, payload: PushPayload?): PushAlert? {
    if (record == null || record.instance != instance || payload == null) return null
    // Both null is not a match: a record without an adopted binding shows nothing.
    if (record.binding == null || record.binding != payload.binding) return null
    val scope = "${record.origin}|${record.userId}"
    return PushAlert(payload, PushKind.of(payload.kind), payload.eventId?.let { "$scope|$it" }, "chitchat:$scope")
}

/**
 * Event IDs seen in this process, so a duplicate the person already dismissed doesn't come back.
 * Memory only, bounded and cleared with the account's other local push state.
 */
class RecentPushEvents(private val limit: Int = 512) {
    private val seen = LinkedHashSet<String>()

    /** True the first time [tag] is seen; a repeat is a duplicate delivery. */
    @Synchronized fun firstTime(tag: String): Boolean {
        if (!seen.add(tag)) return false
        if (seen.size > limit) seen.remove(seen.first())
        return true
    }

    @Synchronized fun clear() = seen.clear()
}
