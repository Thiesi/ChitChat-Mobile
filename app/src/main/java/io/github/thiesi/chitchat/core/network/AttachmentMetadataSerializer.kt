package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.media.AttachmentPreview
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable private class AttachmentMetadataWire(val id: Long, val name: String,
    @SerialName("mime_type") val mimeType: String, @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String, val previewable: Boolean, val width: Int? = null, val height: Int? = null,
    val preview: AttachmentPreview? = null)

/** Presence of both keys advertises geometry, even when both values are explicitly null. */
internal object MessageAttachmentSerializer : KSerializer<MessageAttachment> {
    override val descriptor: SerialDescriptor = AttachmentMetadataWire.serializer().descriptor
    override fun deserialize(decoder: Decoder): MessageAttachment {
        val json = decoder as? JsonDecoder ?: throw SerializationException("JSON attachment metadata required")
        val objectValue = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("Attachment object required")
        return read(json.json, objectValue)
    }
    internal fun read(json: Json, value: JsonObject): MessageAttachment {
        for (key in listOf("width", "height")) requireInteger(value[key], nullable = true)
        value["preview"]?.takeUnless { it == JsonNull }?.let { raw ->
            val preview = raw as? JsonObject ?: throw SerializationException("Preview object required")
            for (key in listOf("width", "height", "size_bytes")) requireInteger(preview[key], nullable = false)
            for (key in listOf("url", "mime_type", "sha256"))
                if ((preview[key] as? JsonPrimitive)?.isString != true) throw SerializationException("Preview string required")
        }
        val wire = json.decodeFromJsonElement(AttachmentMetadataWire.serializer(), JsonObject(value - "message_id"))
        val advertised = "width" in value && "height" in value
        return MessageAttachment(wire.id, wire.name, wire.mimeType, wire.sizeBytes, wire.sha256, wire.previewable,
            wire.width.takeIf { advertised }, wire.height.takeIf { advertised }, wire.preview, advertised)
    }
    override fun serialize(encoder: Encoder, value: MessageAttachment) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSON attachment metadata required")
        json.encodeJsonElement(write(json.json, value))
    }
    internal fun write(json: Json, value: MessageAttachment): JsonObject {
        val wire = AttachmentMetadataWire(value.id, value.name, value.mimeType, value.sizeBytes, value.sha256,
            value.previewable, value.width, value.height, value.preview)
        val encoded = json.encodeToJsonElement(AttachmentMetadataWire.serializer(), wire).jsonObject
        return buildJsonObject {
            encoded.filterKeys { it !in setOf("width", "height") }.forEach { (key, field) -> put(key, field) }
            if (value.dimensionsAdvertised) {
                put("width", value.width?.let(::JsonPrimitive) ?: JsonNull)
                put("height", value.height?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
    }
}

private fun requireInteger(value: JsonElement?, nullable: Boolean) {
    if (nullable && (value == null || value == JsonNull)) return
    val primitive = value as? JsonPrimitive
    if (primitive == null || primitive.isString || primitive.longOrNull == null)
        throw SerializationException("Integer attachment metadata required")
}

/** DM metadata is flattened on the wire; preserve the same attachment hints and their presence. */
internal object DirectAttachmentSerializer : KSerializer<DirectAttachment> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("DirectAttachment") {
        element<Long>("id"); element<Long>("message_id"); element<String>("name")
        element<String>("mime_type"); element<Long>("size_bytes"); element<String>("sha256")
        element<Boolean>("previewable"); element<Int?>("width", isOptional = true)
        element<Int?>("height", isOptional = true); element<AttachmentPreview?>("preview", isOptional = true)
    }
    override fun deserialize(decoder: Decoder): DirectAttachment {
        val json = decoder as? JsonDecoder ?: throw SerializationException("JSON attachment metadata required")
        val value = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("Attachment object required")
        val id = value["message_id"] as? JsonPrimitive
        val messageId = id?.takeUnless { it.isString }?.longOrNull ?: throw SerializationException("Numeric message ID required")
        val model = MessageAttachmentSerializer.read(json.json, value)
        return DirectAttachment(model.id, messageId, model.name, model.mimeType, model.sizeBytes, model.sha256,
            model.previewable, model.width, model.height, model.preview, model.dimensionsAdvertised)
    }
    override fun serialize(encoder: Encoder, value: DirectAttachment) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSON attachment metadata required")
        json.encodeJsonElement(JsonObject(MessageAttachmentSerializer.write(json.json, value.model()) +
            ("message_id" to JsonPrimitive(value.messageId))))
    }
}
