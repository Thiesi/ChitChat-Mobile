package io.github.thiesi.chitchat.core.push

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.StrictJsonBoolean
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/** A whole-hour window in an IANA zone, applied by the server to every push for the account. */
class QuietHours(val start: Int, val end: Int, val timezone: String) {
    init { require(start in 0..23 && end in 0..23 && timezone.isNotBlank()) { "Invalid quiet hours" } }
    override fun equals(other: Any?) = other is QuietHours && other.start == start && other.end == end && other.timezone == timezone
    override fun hashCode() = (start * 31 + end) * 31 + timezone.hashCode()
}

/** Account-wide push choices (ChitChat web-push API). [direct] is null when the server doesn't offer
 * opt-in DM alerts (field missing or null): the client then hides the choice, never shows it as off.
 */
class PushPreferences(val mentioned: Boolean, val pinged: Boolean, val direct: Boolean?, val quietHours: QuietHours?) {
    fun copy(mentioned: Boolean = this.mentioned, pinged: Boolean = this.pinged, direct: Boolean? = this.direct,
        quietHours: QuietHours? = this.quietHours) = PushPreferences(mentioned, pinged, direct, quietHours)
    override fun toString() = "PushPreferences"
}

/** One registered push device. Never carries an endpoint or key material. */
class PushDevice(val id: Long, val userAgent: String?, val label: String?, val currentSession: Boolean,
    val createdAt: String, val lastUsedAt: String?) {
    override fun toString() = "PushDevice"
}

class PushSettings(val preferences: PushPreferences, val devices: List<PushDevice>)

/** Explicit, original-session reads and writes only. No implicit POST retry. */
interface PushOperations {
    val session: StateFlow<SessionState>
    suspend fun settings(expected: SessionState): PushSettings
    /** Sends the complete choice set, including the quiet-hours triplet: the server clears quiet
     * hours whenever they are omitted, so a partial update would silently erase them. */
    suspend fun update(expected: SessionState, preferences: PushPreferences): PushPreferences
    suspend fun revoke(expected: SessionState, deviceId: Long): List<PushDevice>
}

internal class SessionPushRepository(private val sessions: SessionRepository) : PushOperations {
    override val session = sessions.state
    override suspend fun settings(expected: SessionState): PushSettings {
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { pushSettings() }
    }
    override suspend fun update(expected: SessionState, preferences: PushPreferences): PushPreferences {
        val body = pushPreferencesBody(preferences) // Freeze the choice before waiting for access.
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { updatePushPreferences(body, access.csrf) }
    }
    override suspend fun revoke(expected: SessionState, deviceId: Long): List<PushDevice> {
        if (deviceId <= 0) throw ApiFailure(FailureKind.HTTP, 400, "validation_error")
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { revokePushDevice(deviceId, access.csrf) }
    }
}

internal fun pushPreferencesBody(preferences: PushPreferences): String = buildJsonObject {
    put("mentioned_push_enabled", preferences.mentioned)
    put("pinged_push_enabled", preferences.pinged)
    // Only offered choices are written; an unoffered DM choice is left to the server.
    preferences.direct?.let { put("direct_message_push_enabled", it) }
    val quiet = preferences.quietHours
    put("quiet_hours_start", quiet?.start?.let(::JsonPrimitive) ?: JsonNull)
    put("quiet_hours_end", quiet?.end?.let(::JsonPrimitive) ?: JsonNull)
    put("quiet_hours_timezone", quiet?.timezone?.let(::JsonPrimitive) ?: JsonNull)
}.toString()

@Serializable internal class QuietHoursWire(val start: Int, val end: Int, val timezone: String)

@Serializable internal class PushPreferencesWire(
    @SerialName("mentioned_push_enabled") @Serializable(with = StrictJsonBoolean::class) val mentioned: Boolean,
    @SerialName("pinged_push_enabled") @Serializable(with = StrictJsonBoolean::class) val pinged: Boolean,
    @SerialName("direct_message_push_enabled") val direct: JsonElement? = null,
    @SerialName("quiet_hours") val quietHours: QuietHoursWire? = null,
) {
    fun domain(): PushPreferences {
        val directChoice = when (val value = direct) {
            null, JsonNull -> null
            is JsonPrimitive -> if (value.isString) incompatible() else value.booleanOrNull ?: incompatible()
            else -> incompatible()
        }
        val quiet = quietHours?.let {
            if (it.start !in 0..23 || it.end !in 0..23 || it.timezone.isBlank() || it.timezone.length > 64) incompatible()
            QuietHours(it.start, it.end, it.timezone)
        }
        return PushPreferences(mentioned, pinged, directChoice, quiet)
    }
}

@Serializable internal class PushDeviceWire(
    val id: Long,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("device_label") val label: String? = null,
    @SerialName("current_session") val currentSession: JsonElement? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
) {
    fun domain(): PushDevice {
        if (id <= 0) incompatible()
        val current = when (val value = currentSession) {
            null, JsonNull -> false
            is JsonPrimitive -> if (value.isString) incompatible() else value.booleanOrNull ?: incompatible()
            else -> incompatible()
        }
        return PushDevice(id, userAgent, label?.takeIf { it.isNotBlank() }, current, createdAt, lastUsedAt)
    }
}

@Serializable internal class PushSettingsReply(val preferences: PushPreferencesWire, val devices: List<PushDeviceWire>) {
    fun domain(): PushSettings {
        val list = devices.map { it.domain() }
        if (list.map { it.id }.distinct().size != list.size) incompatible()
        return PushSettings(preferences.domain(), list)
    }
}
@Serializable internal class PushPreferencesReply(val preferences: PushPreferencesWire)
@Serializable internal class PushDevicesReply(val devices: List<PushDeviceWire>) {
    fun domain(): List<PushDevice> = devices.map { it.domain() }.also {
        if (it.map { d -> d.id }.distinct().size != it.size) incompatible()
    }
}

private fun incompatible(): Nothing = throw ApiFailure(FailureKind.INCOMPATIBLE)

/** The device's own IANA zone, the default for a new quiet-hours window. */
internal fun deviceTimeZone(): String = ZoneId.systemDefault().id

/** Whether [zone] names a real IANA zone; the server validates it too. */
internal fun isTimeZone(zone: String): Boolean = zone.isNotBlank() && zone in ZoneId.getAvailableZoneIds()

/** A short device label: the stored label when present, else "Browser on OS" only when the user
 * agent clearly names both, else the raw user agent truncated. Never a guessed device model. */
internal fun pushDeviceLabel(device: PushDevice, maxLength: Int = 48): String? {
    device.label?.let { return it }
    val agent = device.userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val browser = listOf("Edg/" to "Edge", "Firefox/" to "Firefox", "Chrome/" to "Chrome", "Safari/" to "Safari")
        .firstOrNull { agent.contains(it.first) }?.second
    val os = listOf("Android" to "Android", "iPhone" to "iOS", "iPad" to "iOS", "Windows" to "Windows",
        "Mac OS X" to "macOS", "Linux" to "Linux").firstOrNull { agent.contains(it.first) }?.second
    if (browser != null && os != null) return "$browser on $os"
    return if (agent.length <= maxLength) agent else agent.take(maxLength - 1) + "…"
}
