package io.github.thiesi.chitchat.feature.account

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real provider I/O, only new fixture documents; no user documents or account data. */
@SdkSuppress(minSdkVersion = 29)
class DocumentExportDestinationTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private fun document() = (resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "chitchat-export-fixture-${UUID.randomUUID()}.json")
        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ChitChatFixtureTests")
    }) ?: error("Unable to create fixture document")).also { uri ->
        // MediaStore insertion creates a row, not bytes. SAF would already have created the empty file.
        resolver.openOutputStream(uri, "wt")!!.use { }
    }

    @Test fun authorizedExportWritesExactUtf8AndClosesTheProviderStream() = runBlocking {
        val uri = document()
        try {
            val bytes = "{\"fixture\":\"Grüße\"}".encodeToByteArray()
            DocumentExportDestination(resolver, uri).write(bytes) {}
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun retiredAccountNeverStartsWriting() = runBlocking {
        val uri = document()
        try {
            try {
                DocumentExportDestination(resolver, uri).write("private-fixture".encodeToByteArray()) { throw CancellationException("Retired") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(0, resolver.openInputStream(uri)!!.use { it.readBytes().size })
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun accountRetirementDuringChunkedWriteStopsBeforeRemainingContent() = runBlocking {
        val uri = document()
        try {
            var checks = 0
            try {
                DocumentExportDestination(resolver, uri).write(ByteArray(100_000) { 7 }) {
                    if (++checks == 3) throw CancellationException("Retired")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(32 * 1024, resolver.openInputStream(uri)!!.use { it.readBytes().size })
        } finally { resolver.delete(uri, null, null) }
    }
}
