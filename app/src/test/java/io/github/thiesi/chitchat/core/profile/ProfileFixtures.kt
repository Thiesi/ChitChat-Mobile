package io.github.thiesi.chitchat.core.profile

import kotlinx.serialization.json.*

internal fun profileBody(id: Long = 8, username: String = "Private profile", guest: Boolean = false,
    avatar: Boolean = false, version: Long? = if (avatar) 0 else null, available: Boolean = true,
    removable: Boolean = avatar, badge: String? = null, since: String = "2026-10-19 10:00:00+00"): String = buildJsonObject {
    put("profile", buildJsonObject {
        put("id", id); put("username", username); put("member_since", since)
        put("badge", badge?.let(::JsonPrimitive) ?: JsonNull)
        put("guest", guest); put("has_avatar", avatar)
        put("avatar_version", version?.let(::JsonPrimitive) ?: JsonNull); put("can_remove_avatar", removable)
        put("ignored", true); put("moderation", buildJsonObject { put("can_ban", true) })
    })
    put("avatars_available", available)
}.toString()

/** null removes a required key; JsonNull represents an explicit JSON null. */
internal fun changedProfile(field: String, value: JsonElement?, source: String = profileBody()): String {
    val root = Json.parseToJsonElement(source).jsonObject.toMutableMap()
    if (field == "avatars_available") {
        if (value == null) root.remove(field) else root[field] = value
    } else {
        val profile = root.getValue("profile").jsonObject.toMutableMap()
        if (value == null) profile.remove(field) else profile[field] = value
        root["profile"] = JsonObject(profile)
    }
    return JsonObject(root).toString()
}
