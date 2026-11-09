package io.github.thiesi.chitchat.core.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class PreparedAttachmentPayloadTest {
    @Test fun knownSha256VectorUsesOnlyTheEventualFileBytes() = runTest {
        val payload = PreparedAttachmentPayload.create("fixture.txt", "text/plain", "abc".encodeUtf8())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", payload.sha256)
        assertEquals(3L, payload.sizeBytes)
        assertEquals("abc".encodeUtf8(), payload.bytes)
    }

    @Test fun segmentedMultiChunkBinaryInputKeepsItsExactDigestAndFrozenBytes() = runTest {
        val source = ByteArray(24_593) { (it * 37).toByte() }
        val expected = source.toByteString()
        val segmented = Buffer().write(source).snapshot()
        source.fill(0)
        val payload = PreparedAttachmentPayload.create("fixture.bin", null, segmented)
        assertEquals(expected, payload.bytes)
        assertEquals(expected.sha256().hex(), payload.sha256)
        assertEquals(expected.size.toLong(), payload.sizeBytes)
    }

    @Test fun changedBytesChangeEvidenceButDisplayHintsDoNot() = runTest {
        val bytes = "same eventual bytes".encodeUtf8()
        val first = PreparedAttachmentPayload.create("first.txt", null, bytes)
        val renamed = PreparedAttachmentPayload.create("other.dat", "application/octet-stream", bytes)
        val changed = PreparedAttachmentPayload.create("first.txt", null, "different bytes".encodeUtf8())
        assertEquals(first.sha256, renamed.sha256)
        assertNotEquals(first.sha256, changed.sha256)
        assertSame(bytes, first.bytes)
    }

    @Test fun unknownAndOctetStreamTypesRemainUnknownForPolicyRatherThanBeingSniffed() = runTest {
        for (mime in listOf(null, "application/octet-stream")) {
            val payload = PreparedAttachmentPayload.create("fixture.bin", mime, "bytes".encodeUtf8())
            assertEquals(mime, payload.mimeType)
            assertEquals("fixture.bin", payload.filename)
        }
    }

    @Test fun emptyAndOverBudgetInputsKeepLocalPreparationFailureKinds() = runTest {
        for ((bytes, kind) in listOf(
            ByteString.EMPTY to PreparationFailureKind.EMPTY,
            ByteArray(MAX_ATTACHMENT_SNAPSHOT_BYTES.toInt() + 1).toByteString() to PreparationFailureKind.CLIENT_BUDGET,
        )) {
            try {
                PreparedAttachmentPayload.create("private-name", null, bytes)
                fail("Invalid preparation must not publish a payload")
            } catch (failure: AttachmentPreparationFailure) {
                assertEquals(kind, failure.kind)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains("private-name"))
            }
        }
    }

    @Test fun invalidHintsFailWithoutPublishingPrivateValues() = runTest {
        for ((name, mime) in listOf("../private-file" to null, "private-file" to "private/type;secret=value")) {
            try {
                PreparedAttachmentPayload.create(name, mime, "private-body".encodeUtf8())
                fail("Invalid metadata must not be adopted")
            } catch (failure: IllegalArgumentException) {
                assertFalse(failure.toString().contains(name))
                assertFalse(failure.toString().contains("secret"))
                assertFalse(failure.toString().contains("private-body"))
            }
        }
    }

    @Test fun cancellationBeforeResultDeliveryCannotPublishAPayload() = runTest {
        var published = false
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            PreparedAttachmentPayload.create("fixture", null, "bytes".encodeUtf8()).also { published = true }
        }
        pending.cancel()
        try { pending.await(); fail("Cancelled preparation must not publish") }
        catch (_: CancellationException) { }
        assertFalse(published)
    }

    @Test fun defaultDebugStringContainsNoNameContentOrDigest() = runTest {
        val payload = PreparedAttachmentPayload.create("private-name", "text/plain", "private-body".encodeUtf8())
        val debug = payload.toString()
        for (privateValue in listOf("private-name", "private-body", payload.sha256)) assertFalse(debug.contains(privateValue))
    }
}
