package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.reports.ReportFacts
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** `reports/message.php` (moderation-reports.md). */
@Serializable internal class MessageReportRequest(
    @SerialName("message_kind") val kind: String,
    @SerialName("message_id") val messageId: Long,
    val category: String,
    val details: String?,
)

/** `reports/user.php`: no [context] is a global report; a room context is never turned into a global one. */
@Serializable internal class UserReportRequest(
    @SerialName("user_id") val userId: Long,
    val category: String,
    val details: String?,
    val context: UserReportContext?,
)

@Serializable internal class UserReportContext(@SerialName("room_id") val roomId: Long)

/** A 201's `{case:{id,…}}`, read leniently: the report exists whatever the body says. */
internal fun reportCaseId(body: String): Long? = try {
    val case = (apiJson.parseToJsonElement(body) as? JsonObject)?.get("case") as? JsonObject
    (case?.get("id") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
} catch (_: IllegalArgumentException) { null }

/** Strict profile facts: both fields JSON Booleans, else unknown (null). Never decides the global update state. */
internal fun reportFacts(canReport: JsonElement?, reportedByMe: JsonElement?): ReportFacts? {
    fun strict(value: JsonElement?) = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    return ReportFacts(strict(canReport) ?: return null, strict(reportedByMe) ?: return null)
}
