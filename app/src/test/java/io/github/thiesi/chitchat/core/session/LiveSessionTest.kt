package io.github.thiesi.chitchat.core.session

import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Excluded from ordinary unit tests. Only :app:liveSessionTest explicitly runs this. */
class LiveSessionTest {
    @Test fun realPasswordSessionRestorationAndServerLogout() = runBlocking {
        val config = configuration()
        val address = (ServerAddress.parse(config.getValue("CHITCHAT_TEST_SERVER")) as? AddressResult.Valid)?.address
            ?: error("The opt-in integration server must be an HTTPS origin")
        val vault = TransientVault()
        val clients = mutableListOf<SessionHttp>()
        fun repository(storage: SessionVault = vault) = SessionRepository(storage) {
            SessionHttp(it).also(clients::add)
        }
        var active = repository()
        try {
            active.connect(address)
            assertEquals("Anonymous bootstrap must succeed", SessionPhase.ANONYMOUS, active.state.value.phase)
            active.signIn(config.getValue("CHITCHAT_TEST_USERNAME"), config.getValue("CHITCHAT_TEST_PASSWORD"))
            assertEquals("Use a dedicated password-only non-admin test account", SessionPhase.AUTHENTICATED, active.state.value.phase)
            assertTrue("The test account must not be an administrator", active.state.value.user!!.roles.none { it in setOf("admin", "super_admin") })
            val userId = active.state.value.user!!.id
            assertNotNull("A restorable HTTP cookie must have been returned", vault.value)
            // Exercise repository recreation and revalidation, not an Android process-death claim.
            clients.forEach(SessionHttp::close)
            active = repository()
            assertNull(active.state.value.user)
            active.connect(address)
            assertEquals(userId, active.state.value.user?.id)
            active.refresh()
            assertEquals(SessionPhase.AUTHENTICATED, active.state.value.phase)
            // Read-only M3a acceptance: no room memberships, reads, presence or messages are changed.
            val rooms = io.github.thiesi.chitchat.core.rooms.SessionRoomRepository(active)
            val directory = rooms.list()
            assertEquals("Room IDs must be unique", directory.size, directory.map { it.id }.toSet().size)
            assertTrue("Provide at least one visible room for directory/detail acceptance", directory.isNotEmpty())
            assertEquals("Room detail must match the requested authorized ID", directory.first().id, rooms.detail(directory.first().id).id)
            // Read-only M3b contracts: no read acknowledgment, send, presence or membership mutation.
            val conversations = io.github.thiesi.chitchat.core.conversation.SessionConversationRepository(active)
            val roomId = directory.first().id
            assertEquals(roomId, conversations.context(roomId).room.id)
            val page = conversations.history(roomId)
            assertTrue("Authorized history must remain in the requested room", page.messages.all { it.roomId == roomId })
            assertTrue("History IDs must be ascending and unique", page.messages.zipWithNext().all { (a, b) -> a.id < b.id })
            assertTrue("Ping history must remain private to this account", conversations.pings(roomId).all {
                it.roomId == roomId && userId in setOf(it.sender.id, it.target.id)
            })
            val revoked = vault.value!!
            active.signOut()
            assertEquals(SessionPhase.SIGNED_OUT, active.state.value.phase)
            assertNull(vault.value)
            assertFalse("The server must confirm logout", active.state.value.logoutUnconfirmed)
            val stale = repository(TransientVault().apply { value = revoked })
            active = stale
            stale.connect(address)
            assertNull("The logged-out HTTP cookie must not restore an account", stale.state.value.user)
            assertEquals(SessionPhase.INVALIDATED, stale.state.value.phase)
        } finally {
            active.signOut()
            clients.forEach(SessionHttp::close)
        }
    }

    private fun configuration(): Map<String, String> {
        val path = System.getProperty("chitchat.liveConfig") ?: error("Run only via :app:liveSessionTest")
        val file = File(path)
        check(file.isFile) { "Create the ignored .env.integration file before running opt-in live tests" }
        return parseLiveConfiguration(file.readLines())
    }

    private class TransientVault : SessionVault {
        var value: SavedSession? = null
        override suspend fun read() = value
        override suspend fun save(session: SavedSession) { value = session }
        override suspend fun clear() { value = null }
    }
}
