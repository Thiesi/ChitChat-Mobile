package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.network.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentPreviewMetadataTest {
    private val sha = "a".repeat(64)
    private val base = """"id":12,"name":"private-image.png","mime_type":"image/png","size_bytes":1250000,"sha256":"$sha","previewable":true"""
    private fun preview(url: String = "/api/v1/attachments/download.php?id=12&variant=preview",
        width: Int = 1024, height: Int = 512, bytes: Long = 42000, type: String = "image/webp", digest: String = sha) =
        AttachmentPreview(url, width, height, type, bytes, digest)
    private fun decode(extra: String = "") = apiJson.decodeFromString(MessageAttachment.serializer(), "{$base$extra}")
    private fun accepted(preview: AttachmentPreview, direct: Boolean = false) =
        requireValidAttachmentMetadata(MessageAttachment(12,"private-image.png","image/png",1250000,sha,true,
            preview = preview), direct)
    private fun refused(preview: AttachmentPreview, direct: Boolean = false) {
        try { accepted(preview, direct); fail("Unsafe preview metadata accepted") }
        catch (failure: ApiFailure) { assertEquals(FailureKind.INCOMPATIBLE, failure.kind) }
    }
    private fun malformed(extra: String) {
        try { decode(extra); fail("Malformed metadata accepted") } catch (_: SerializationException) { }
    }

    @Test fun missingGeometryIsUnknownAndNeverInventsAPreviewFromTheOldFlag() {
        val value = decode()
        assertFalse(value.dimensionsAdvertised); assertNull(value.displayDimensions)
        assertNull(value.preview); assertTrue(value.previewable)
        val encoded = apiJson.encodeToJsonElement(MessageAttachment.serializer(),value).jsonObject
        assertFalse("width" in encoded); assertFalse("height" in encoded)
    }

    @Test fun explicitNullGeometryIsAdvertisedButHasNoUsableRatio() {
        val value = decode(",\"width\":null,\"height\":null,\"preview\":null")
        assertTrue(value.dimensionsAdvertised); assertNull(value.displayDimensions); assertNull(value.preview)
        val encoded = apiJson.encodeToJsonElement(MessageAttachment.serializer(),value).jsonObject
        assertEquals(JsonNull,encoded["width"]); assertEquals(JsonNull,encoded["height"])
        assertTrue(apiJson.decodeFromJsonElement(MessageAttachment.serializer(),encoded).dimensionsAdvertised)
    }

    @Test fun orientedGeometryAndReturnedPreviewSurviveRoundTripUnchanged() {
        val descriptor = apiJson.encodeToJsonElement(AttachmentPreview.serializer(),preview())
        val value = decode(",\"width\":2048,\"height\":1024,\"preview\":$descriptor")
        assertTrue(value.dimensionsAdvertised); assertEquals(2048,value.displayDimensions!!.width)
        assertEquals(1024,value.displayDimensions!!.height); accepted(value.preview!!)
        val again = apiJson.decodeFromString(MessageAttachment.serializer(),apiJson.encodeToString(MessageAttachment.serializer(),value))
        assertEquals(value.width,again.width); assertEquals(value.height,again.height)
        assertEquals(descriptor,apiJson.encodeToJsonElement(AttachmentPreview.serializer(),again.preview!!))
        assertEquals(1250000L,again.sizeBytes); assertEquals(42000L,again.preview!!.sizeBytes)
        assertEquals("MessageAttachment",again.toString()); assertEquals("AttachmentPreview",again.preview.toString())
    }

    @Test fun partialGeometryDoesNotAdvertiseOrInventTheMissingAxis() {
        for (field in listOf(",\"width\":2048",",\"height\":1024",",\"width\":null")) {
            val value = decode(field)
            assertFalse(value.dimensionsAdvertised); assertNull(value.width); assertNull(value.height)
            assertNull(value.displayDimensions)
        }
    }

    @Test fun nonpositiveHeaderGeometryFallsBackWithoutDiscardingTheOriginalFile() {
        for ((w,h) in listOf(0 to 400,-1 to 400,400 to 0,400 to -1)) {
            val value = decode(",\"width\":$w,\"height\":$h")
            assertTrue(value.dimensionsAdvertised); assertNull(value.displayDimensions)
            assertEquals(12L,value.id); assertEquals(1250000L,value.sizeBytes)
        }
        val huge = decode(",\"width\":2147483647,\"height\":1")
        assertEquals(Int.MAX_VALUE,huge.displayDimensions!!.width) // A framing hint, never a bitmap allocation.
    }

    @Test fun quotedBooleanFractionalAndOverflowGeometryIsNotAnIntegerContract() {
        for (value in listOf("\"100\"","true","1.5","2147483648","{}","[]"))
            malformed(",\"width\":$value,\"height\":100")
    }

    @Test fun directMetadataPreservesPresenceAndPreviewAcrossEnrichment() {
        val descriptor = apiJson.encodeToJsonElement(AttachmentPreview.serializer(),preview(
            "/api/v1/direct-messages/attachments/download.php?variant=preview&id=12"))
        val raw = "{$base,\"message_id\":80,\"width\":2048,\"height\":1024,\"preview\":$descriptor}"
        val wire = apiJson.decodeFromString(DirectAttachment.serializer(),raw)
        assertEquals(80L,wire.messageId); val value = wire.model()
        requireValidAttachmentMetadata(value,direct=true)
        assertTrue(value.dimensionsAdvertised); assertEquals(2048,value.displayDimensions!!.width)
        assertEquals(descriptor,apiJson.encodeToJsonElement(AttachmentPreview.serializer(),value.preview!!))
        val again = apiJson.decodeFromString(DirectAttachment.serializer(),apiJson.encodeToString(DirectAttachment.serializer(),wire))
        assertEquals(80L,again.messageId); assertTrue(again.model().dimensionsAdvertised)
        assertEquals(value.preview!!.url,again.model().preview!!.url)
    }

    @Test fun directExplicitNullAndLegacyMetadataKeepTheirDifferentPresence() {
        val legacy = apiJson.decodeFromString(DirectAttachment.serializer(),"{$base,\"message_id\":80}").model()
        val current = apiJson.decodeFromString(DirectAttachment.serializer(),"{$base,\"message_id\":80,\"width\":null,\"height\":null}").model()
        assertFalse(legacy.dimensionsAdvertised); assertTrue(current.dimensionsAdvertised)
        assertNull(legacy.preview); assertNull(current.preview); assertNull(current.displayDimensions)
    }

    @Test fun directMessageIdMustStayAnActualInteger() {
        for (value in listOf("\"80\"","true","null","1.5")) {
            try { apiJson.decodeFromString(DirectAttachment.serializer(),"{$base,\"message_id\":$value}"); fail("Noninteger message ID") }
            catch (_: SerializationException) { }
        }
    }

    @Test fun previewPathsAreBoundToTheExactAttachmentAndNamespace() {
        accepted(preview())
        accepted(preview("/api/v1/attachments/download.php?variant=preview&id=12"))
        accepted(preview("/api/v1/direct-messages/attachments/download.php?id=12&variant=preview"),direct=true)
        refused(preview(),direct=true)
        refused(preview("/api/v1/direct-messages/attachments/download.php?id=12&variant=preview"))
        refused(preview("/api/v1/attachments/download.php?id=13&variant=preview"))
    }

    @Test fun foreignAbsoluteAndAuthorityUrlsNeverBecomeFileHints() {
        for (url in listOf("https://other.example/api/v1/attachments/download.php?id=12&variant=preview",
            "https://fixture.example/api/v1/attachments/download.php?id=12&variant=preview",
            "//other.example/api/v1/attachments/download.php?id=12&variant=preview",
            "http://fixture.example/api/v1/attachments/download.php?id=12&variant=preview",
            "file:///api/v1/attachments/download.php?id=12&variant=preview")) refused(preview(url))
    }

    @Test fun normalizedEncodedOrUnrelatedPathsCannotBorrowDownloadAuthorization() {
        for (path in listOf("api/v1/attachments/download.php","/api/v1/attachments/../attachments/download.php",
            "/api/v1/attachments/%2e%2e/attachments/download.php","/api/v1/%61ttachments/download.php",
            "/api/v1/attachments//download.php","/api/v1/session.php","/api/v1/attachments/download.php/extra"))
            refused(preview("$path?id=12&variant=preview"))
    }

    @Test fun duplicateUnknownEncodedAndWrongVariantQueriesAreRejected() {
        for (query in listOf("id=12","id=12&variant=original","id=12&variant=preview&inline=1",
            "id=12&id=12","id=12&variant=preview&variant=preview","%69d=12&variant=preview",
            "id=%31%32&variant=preview","id=012&variant=preview","id=12&variant=%70review",
            "id=12&variant=preview#fragment","id=12&variant=preview&","id=12;variant=preview"))
            refused(preview("/api/v1/attachments/download.php?$query"))
    }

    @Test fun controlCharactersAndUnboundedUrlsAreRejected() {
        for (url in listOf("/api/v1/attachments/download.php?id=12&variant=preview\r\nX: y",
            "/api/v1/attachments/download.php?id=12&variant=preview ","/".repeat(257))) refused(preview(url))
    }

    @Test fun previewGeometryEncodedBudgetMimeAndDigestHaveTheirOwnBounds() {
        accepted(preview(width=1,height=1024,bytes=4L*1024*1024))
        for (value in listOf(preview(width=0),preview(height=0),preview(width=1025),preview(height=1025),
            preview(bytes=0),preview(bytes=4L*1024*1024+1),preview(type="image/png"),
            preview(digest=""),preview(digest="g".repeat(64)),preview(digest="a".repeat(63)))) refused(value)
    }

    @Test fun incompleteOrNonobjectPreviewCannotMasqueradeAsALegacyNull() {
        for (raw in listOf("true","[]","\"url\"","{}")) malformed(",\"preview\":$raw")
        val descriptor = apiJson.encodeToJsonElement(AttachmentPreview.serializer(),preview()).jsonObject
        for (key in listOf("url","width","height","mime_type","size_bytes","sha256"))
            malformed(",\"preview\":${JsonObject(descriptor-key)}")
    }

    @Test fun previewNumbersAndStringsKeepTheirActualJsonTypes() {
        val descriptor = apiJson.encodeToJsonElement(AttachmentPreview.serializer(),preview()).jsonObject
        for (key in listOf("width","height","size_bytes"))
            malformed(",\"preview\":${JsonObject(descriptor+(key to JsonPrimitive("100")))}")
        for (key in listOf("url","mime_type","sha256"))
            malformed(",\"preview\":${JsonObject(descriptor+(key to JsonPrimitive(100)))}")
    }

    @Test fun optionalEnrichmentCannotHideThePositiveUploadAcknowledgment() {
        val room = """{"message":{"id":80,"room_id":42,"sender_id":7,"type":"attachment","deleted":false,"attachment":{"preview":"invalid"}}}"""
        assertEquals(80L,apiJson.decodeFromString(RoomUploadReply.serializer(),room).message.id)
        val direct = """{"message":{"id":80,"sender":{"id":7},"recipient":{"id":8},"outgoing":true,"attachment":{"preview":"invalid"}}}"""
        assertEquals(80L,apiJson.decodeFromString(DirectUploadReply.serializer(),direct).message.id)
    }
}
