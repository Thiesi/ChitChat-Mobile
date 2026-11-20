package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.session.PushTeardown
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5: share copies' layout and lifetime (TL decisions of 2026-11-08 and 2026-11-20; privacy-policy.md). */
class ShareCopiesTest {
    private val root: File = Files.createTempDirectory("ccm-share-copies").toFile()
    private var now = 1_000_000_000L
    private val revoked = mutableListOf<String>()
    private val copies = ShareCopies(root, { now }, { revoked += it.name })
    private val alice = ShareCopies.accountKey("https://chat.example.test", 7)
    private val bob = ShareCopies.accountKey("https://chat.example.test", 8)

    @After fun cleanUp() { root.deleteRecursively() }

    private fun written(account: String, name: String = "report.pdf"): ShareCopies.Copy =
        copies.create(account, name).also { it.file.writeText("bytes") }

    private fun ShareCopies.Copy.exists() = file.exists()

    @Test fun eachCopyHasItsOwnRandomFolderUnderItsAccountAndASanitizedName() {
        val first = written(alice, "../../secret.pdf")
        val second = written(alice, "../../secret.pdf")
        assertEquals(File(root, alice), first.directory.parentFile)
        assertTrue(first.directory != second.directory)
        assertEquals(32, first.directory.name.length)
        assertFalse(first.file.name.contains("/") || first.file.name.contains(".."))
        assertEquals(first.directory, first.file.parentFile)
        // No identifier in the path: the key is a hash.
        assertFalse(alice.contains("example"))
        assertEquals(32, alice.length)
    }

    @Test fun expiredHandedOffCopiesGoAtTheNextSweepAndInFlightOnesNeverDo() {
        val old = written(alice); copies.handedOff(old)
        val fresh = written(alice); copies.handedOff(fresh)
        val writing = written(alice)
        old.directory.setLastModified(now - ShareCopies.TTL_MILLIS - 1)
        writing.directory.setLastModified(now - ShareCopies.TTL_MILLIS - 1)
        copies.sweep()
        assertFalse(old.exists())
        assertTrue("Within its 24 hours", fresh.exists())
        assertTrue("Still being written", writing.exists())
        assertEquals(listOf("report.pdf"), revoked)
    }

    @Test fun discardDeletesAtOnceAndRevokes() {
        val copy = written(alice)
        copies.discard(copy)
        assertFalse(copy.directory.exists())
        assertEquals(listOf("report.pdf"), revoked)
    }

    @Test fun anAccountsEndDeletesItsWholeFolderIncludingInFlightCopies() {
        val handed = written(alice); copies.handedOff(handed)
        val writing = written(alice)
        val other = written(bob); copies.handedOff(other)
        copies.deleteAccount(alice)
        assertFalse(File(root, alice).exists())
        assertFalse(handed.exists() || writing.exists())
        assertTrue(other.exists())
        assertEquals(2, revoked.size)
    }

    @Test fun keepOnlyDeletesOtherAccountsButNeverAnInFlightCopy() {
        val mine = written(alice); copies.handedOff(mine)
        val theirs = written(bob); copies.handedOff(theirs)
        val theirsWriting = written(bob)
        copies.keepOnly(alice)
        assertTrue(mine.exists())
        assertFalse(theirs.exists())
        assertTrue("Its owner's fence discards it", theirsWriting.exists())
        copies.keepOnly(null)
        assertFalse(mine.exists())
    }

    @Test fun theStartupSweepUsesTheStoredAccountUnlessASessionEventAlreadyDecided() {
        val mine = written(alice); copies.handedOff(mine)
        val theirs = written(bob); copies.handedOff(theirs)
        copies.startupSweep(alice)
        assertTrue(mine.exists())
        assertFalse(theirs.exists())
        // The race: a fast sign-in to Bob is published before a slow start-up sweep that read Alice from the vault.
        val racing = ShareCopies(root, { now })
        val bobs = racing.create(bob, "late.pdf").also { it.file.writeText("x"); racing.handedOff(it) }
        racing.keepOnly(bob)
        racing.startupSweep(alice)
        assertTrue("A slow start never deletes the newer account's copies", bobs.exists())
    }

    @Test fun theStartupSweepWithNoStoredAccountDeletesEverythingNotInFlight() {
        val handed = written(alice); copies.handedOff(handed)
        val writing = written(alice)
        copies.startupSweep(null)
        assertFalse(handed.exists())
        assertTrue(writing.exists())
    }

    // The session hook (TL, 2026-11-20): exactly push's points and predicate.
    private class RecordingPush : PushTeardown {
        val calls = mutableListOf<String>()
        override fun retire(origin: String?, userId: Long?): Long? { calls += "retire $userId"; return 5 }
        override fun sessionChanged(origin: String, userId: Long?, signedInAt: Long?) { calls += "changed $userId" }
    }

    @Test fun theTeardownFollowsTheAccountLifecycle() {
        val push = RecordingPush()
        val teardown = ShareCopyTeardown(push, copies)
        val origin = "https://chat.example.test"
        val mine = written(alice); copies.handedOff(mine)
        // A cold start, then the restore of the same account (published with its saved sign-in time): kept.
        teardown.sessionChanged(origin, 7, 123L)
        assertTrue(mine.exists())
        // A revalidation while signed in publishes the same account again: kept.
        teardown.sessionChanged(origin, 7, 123L)
        assertTrue(mine.exists())
        // A switch to another account deletes the old account's folder.
        val theirs = written(bob); copies.handedOff(theirs)
        teardown.sessionChanged(origin, 7, 123L)
        assertFalse(theirs.exists())
        // A sign-out (retire) deletes it, and push still gets its own call and answer.
        assertEquals(5L, teardown.retire(origin, 7))
        assertFalse(mine.exists())
        assertEquals(listOf("changed 7", "changed 7", "changed 7", "retire 7"), push.calls)
    }

    @Test fun noSessionUnderPushsPredicateKeepsNothingButInFlightCopies() {
        val teardown = ShareCopyTeardown(RecordingPush(), copies)
        val handed = written(alice); copies.handedOff(handed)
        val writing = written(alice)
        // A user without a sign-in time isn't a session for push either.
        teardown.sessionChanged("https://chat.example.test", 7, null)
        assertFalse(handed.exists())
        assertTrue(writing.exists())
    }
}
