package io.github.thiesi.chitchat.core.media

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.IntentCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.session.PushTeardown
import java.io.File
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5a: the share copies' FileProvider on a real device (TL, 2026-11-20). Only `cache/shared` resolves, intents carry
 * only a temporary read grant, and the end of the account's session revokes grants and deletes the copies.
 */
class ShareProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = ShareProvider.root(context)
    private val copies = ShareCopies(root, revoke = { ShareProvider.revoke(context, it) })
    private val origin = "https://chat.example.test"
    private val account = ShareCopies.accountKey(origin, 7)
    private val outsiders = mutableListOf<File>()

    @After fun cleanUp() { root.deleteRecursively(); outsiders.forEach { it.delete() } }

    private fun ready(mime: String?): TransferState.Ready {
        val copy = copies.create(account, "report.pdf").also { it.file.writeText("contents") }
        val ref = AttachmentRef(MessageAttachment(5, "report.pdf", mime.orEmpty(), 8, "a".repeat(64), previewable = false), false)
        return TransferState.Ready(ref, TransferPurpose.SHARE, copy, mime)
    }

    @Test fun onlyTheShareFolderIsReachable() {
        val copy = ready("application/pdf").copy
        val uri = ShareProvider.uriFor(context, copy.file)
        assertEquals("content", uri.scheme)
        assertEquals(ShareProvider.authority(context), uri.authority)
        context.contentResolver.openInputStream(uri)!!.use { assertEquals("contents", it.reader().readText()) }
        for (outside in listOf(File(context.cacheDir, "other.txt"), File(context.filesDir, "secret.txt"), File(context.cacheDir, "sharedx.txt"))) {
            outside.parentFile?.mkdirs(); outside.writeText("x"); outsiders += outside
            assertThrows(outside.name, IllegalArgumentException::class.java) { ShareProvider.uriFor(context, outside) }
        }
    }

    @Test fun intentsCarryOnlyATemporaryReadGrant() {
        val forbidden = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val share = ready("application/pdf")
        val chooser = ShareProvider.shareIntent(context, share)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, android.net.Uri::class.java)
        assertEquals(ShareProvider.uriFor(context, share.copy.file), uri)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        for (intent in listOf(chooser, send)) {
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and forbidden)
        }
        // Open: ACTION_VIEW with the declared type; a missing or octet-stream type gets a plain chooser.
        val view = ShareProvider.openIntent(context, ready("application/pdf"))
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("application/pdf", view.type)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, view.flags and forbidden)
        for (unknown in listOf(null, "application/octet-stream")) {
            val plain = ShareProvider.openIntent(context, ready(unknown))
            assertEquals(Intent.ACTION_CHOOSER, plain.action)
            assertEquals(0, plain.flags and forbidden)
        }
    }

    @Test fun aGrantedUriFailsAfterTheAccountsSessionEnds() {
        val share = ready("application/pdf")
        copies.handedOff(share.copy)
        val uri = ShareProvider.uriFor(context, share.copy.file)
        val receiver = "com.android.shell"
        val receiverUid = context.packageManager.getPackageUid(receiver, 0)
        context.grantUriPermission(receiver, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkUriPermission(uri, 0, receiverUid, Intent.FLAG_GRANT_READ_URI_PERMISSION))
        // Sign-out: the session teardown deletes the account's folder, revoking first.
        ShareCopyTeardown(PushTeardown.NONE, copies).retire(origin, 7)
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkUriPermission(uri, 0, receiverUid, Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertThrows(FileNotFoundException::class.java) { context.contentResolver.openInputStream(uri) }
        assertFalse(share.copy.file.exists())
    }
}
