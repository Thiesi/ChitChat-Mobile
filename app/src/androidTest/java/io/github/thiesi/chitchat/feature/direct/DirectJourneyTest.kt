package io.github.thiesi.chitchat.feature.direct

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native UI with canonical fixture operations; HTTP/TLS is tested separately, no live DM claim. */
class DirectJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val api = NativeDirects()
    private val model = DirectThreadViewModel(api)
    @After fun stop() { compose.runOnIdle { model.background(); api.readGate?.complete(Unit) } }
    private fun content(ready: Boolean = true, loadFailure: Boolean = false, height: Int = 640) {
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, height.dp).imePadding()) { DirectThreadScreen(model, 8, {}) } } }
        compose.waitUntil(5_000) { if (loadFailure) model.state.value.error != null && !model.state.value.busy
            else model.state.value.loaded && (!ready || !model.state.value.busy) }
    }
    private fun send(body: String) {
        compose.onNodeWithTag("dm_composer").performTextInput(body)
        compose.onNodeWithTag("dm_send").assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun details() {
        val action = compose.onNode(hasClickAction() and (hasText("Conversation details") or hasContentDescription("Conversation details")))
        if (runCatching { action.assertIsDisplayed() }.isFailure) action.performScrollTo()
        action.assertIsDisplayed().performClick()
    }
    private fun notice(text: String) {
        val node = compose.onNodeWithText(text)
        if (runCatching { node.assertExists() }.isFailure) details()
        node.performScrollTo().assertIsDisplayed()
    }

    @Test fun privacyIsNativeAndMultilineSendWorksWithTheKeyboardOpen() {
        content()
        details()
        compose.onNodeWithText("Editing or deleting", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Canonical DM inspection is disabled", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        send("Native multiline fixture\nSecond line")
        compose.waitUntil(5_000) { api.sends == 1 && !model.state.value.busy }
        compose.runOnIdle { assertEquals("", model.state.value.draft); assertEquals(99L, model.state.value.confirmedSendId) }
        compose.runOnIdle { assertEquals(1, model.state.value.messages.count { it.id == 99L }) }
        // Credential-free fixture image for actual visual inspection, not live-server acceptance.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val output = java.io.File(target.getExternalFilesDir("screenshots") ?: target.cacheDir, "dm-fixture.png")
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("direct:99").assertIsDisplayed()
    }

    @Test fun unknownSendNeedsExplicitHistoryCheckAndDuplicateRiskConsent() {
        api.sendFailure = ApiFailure(FailureKind.TIMEOUT); content(); send("Unknown native fixture")
        compose.waitUntil(5_000) { model.state.value.uncertainSend }
        compose.onNodeWithTag("dm_send").assertIsNotEnabled()
        compose.runOnIdle { model.invalidate() }
        compose.waitUntil(5_000) { !model.state.value.busy && !model.state.value.stale }
        compose.onNodeWithText("Allow another send despite duplicate risk").assertDoesNotExist()
        notice("Check latest history")
        compose.onNodeWithText("Check latest history").performClick()
        compose.waitUntil(5_000) { model.state.value.checkedUnknown }
        notice("Allow another send despite duplicate risk")
        compose.onNodeWithText("Allow another send despite duplicate risk").performClick()
        compose.runOnIdle { assertEquals(1, api.sends); api.sendFailure = null }
        compose.onNodeWithTag("dm_send").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { api.sends == 2 && !model.state.value.busy }
    }

    @Test fun blockingStaysGenericAndCanonicalDeletionIsNotGuessedFromText() {
        api.available = false
        api.rows = listOf(row(18, "Message deleted.", attachment = MessageAttachment(3, "note.txt", "text/plain", 3, "f".repeat(64), false)),
            row(19, deleted = true))
        content()
        compose.onNodeWithTag("dm_timeline").performScrollToNode(hasText("Message deleted."))
        compose.onNodeWithText("Message deleted.").assertIsDisplayed()
        compose.onNodeWithText("note.txt", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("dm_timeline").performScrollToNode(hasText("Message deleted"))
        compose.onNodeWithText("Message deleted").assertIsDisplayed()
        compose.onNodeWithTag("dm_send").assertIsNotEnabled()
        details()
        compose.onNodeWithText("Block").performScrollTo().performClick()
        compose.onNodeWithTag("dm_confirm_block").performClick()
        compose.waitUntil(5_000) { model.state.value.context?.relationship?.blockedByMe == true && !model.state.value.busy }
        details()
        compose.onNodeWithText("Unblock").performScrollTo().performClick()
        compose.onNodeWithTag("dm_confirm_block").performClick()
        compose.waitUntil(5_000) { api.blocks == 2 && !model.state.value.busy }
        compose.runOnIdle { assertFalse(model.state.value.context!!.relationship.messagingAvailable) }
        if (!compose.onNodeWithText("Messaging is unavailable", substring = true).isDisplayed()) details()
        compose.onNodeWithText("Messaging is unavailable", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun wholePeerReadRaceKeepsLocalAttentionWhenTheUserScrollsAway() {
        api.rows = (1L..80L).map { row(it, read = null) }; api.readGate = CompletableDeferred()
        content(ready = false); compose.waitUntil(5_000) { api.reads == 1 }
        compose.onNodeWithTag("dm_timeline").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { !model.state.value.atLatest }
        compose.runOnIdle { api.rows += row(81, read = null); api.readGate!!.complete(Unit) }
        compose.waitUntil(5_000) { !model.state.value.busy && model.state.value.messages.lastOrNull()?.id == 81L }
        compose.runOnIdle {
            assertEquals(1, api.reads); assertTrue(model.state.value.messages.all { it.readAt != null })
            assertTrue(model.attention.value[8]?.ids?.contains(81) == true)
        }
        if (!compose.onNodeWithTag("dm_local_attention").isDisplayed()) details()
        compose.onNodeWithTag("dm_local_attention").performScrollTo().assertIsDisplayed()
    }

    @Test fun compositionRestorationUsesOnlyVmDraftAndLogoutClearsPrivateState() {
        val restore = StateRestorationTester(compose)
        restore.setContent { MaterialTheme { Box(Modifier.size(320.dp, 640.dp).imePadding()) { DirectThreadScreen(model, 8, {}) } } }
        compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy }
        compose.onNodeWithTag("dm_composer").performTextInput("Private VM draft fixture")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Private VM draft fixture").assertIsDisplayed()
        compose.runOnIdle { (api.session as MutableStateFlow<SessionState>).value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT) }
        compose.onNodeWithText("Private VM draft fixture").assertDoesNotExist()
        compose.runOnIdle { assertEquals("", model.state.value.draft); assertTrue(model.drafts.value.isEmpty()); assertTrue(model.state.value.messages.isEmpty()) }
    }

    @Test fun rateLimitExpiryReenablesTheExplicitActionWithoutEditingOrAutomaticPosting() {
        content()
        compose.runOnIdle { model.updateDraft("Rate wait fixture")
            val state = api.session as MutableStateFlow<SessionState>
            state.value = state.value.copy(retryAtMillis = System.currentTimeMillis() + 2000)
        }
        compose.onNodeWithTag("dm_send").assertIsNotEnabled()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("dm_send").assertIsEnabled() }.isSuccess }
        compose.runOnIdle { assertEquals(0, api.sends); assertEquals("Rate wait fixture", model.state.value.draft) }
    }

    @Test fun modalConversationDetailsCannotAcknowledgeAnArrivalBehindTheDialog() {
        content()
        details()
        compose.onNodeWithText("Canonical DM inspection is disabled", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { api.rows += row(19, read = null); model.invalidate() }
        compose.waitUntil(5_000) { model.state.value.messages.lastOrNull()?.id == 19L && !model.state.value.busy }
        compose.runOnIdle { assertEquals(0, api.reads); assertTrue(model.attention.value[8]?.ids?.contains(19) == true) }
        compose.onNodeWithText("Close").performClick()
        // At large fonts the arriving row can be below the preserved old anchor. Reveal it;
        // dismissing a modal is not permission to jump or acknowledge off-screen content.
        compose.onNodeWithTag("dm_timeline").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { api.reads == 1 }
    }

    @Test fun compactInitialFailureStillExposesSessionSafeExplicitRecovery() {
        api.contextFailure = ApiFailure(FailureKind.NETWORK)
        content(loadFailure = true, height = 180)
        compose.onNodeWithTag("dm_send").assertIsNotEnabled()
        details()
        compose.onNodeWithText("The network request failed.", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { api.contextFailure = null; assertEquals(0, api.sends); assertEquals(0, api.reads) }
        compose.onNodeWithText("Refresh").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy }
        compose.onNodeWithTag("dm_send").assertIsEnabled()
    }

    @Test fun nativeInboxSearchBackNavigationAndAccountSwitchKeepTheirOriginalScope() {
        val sessions = TestSessions(); val auth = AuthViewModel(sessions)
        val direct = NativeDirects(sessions.state); val inbox = DirectInboxViewModel(direct); val thread = DirectThreadViewModel(direct)
        direct.rows = (16L..18L).map { row(it, read = null) }
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
        })
        compose.setContent { Box(Modifier.size(320.dp, 640.dp)) { TestChitChatApp(settings, auth, directInbox = inbox, directThread = thread) } }
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Viewer")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture-password")
        closeSoftKeyboard()
        compose.onNodeWithText("Sign in").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.AUTHENTICATED }
        compose.onNodeWithText("Direct messages").performScrollTo().performClick()
        compose.waitUntil(5_000) { inbox.state.value.inbox != null && !inbox.state.value.busy }
        compose.onNodeWithTag("dm_inbox").performScrollToNode(hasText("3 unread"))
        compose.onNodeWithText("3 unread").assertIsDisplayed()
        compose.onNodeWithTag("dm_inbox").performScrollToNode(hasTestTag("dm_search_input"))
        compose.onNodeWithTag("dm_search_input").performScrollTo().performTextInput("Pe")
        compose.onNodeWithTag("dm_search_input").performImeAction()
        compose.waitUntil(5_000) { inbox.state.value.searched }
        compose.onNodeWithTag("dm_inbox").performScrollToNode(hasTestTag("dm_person:8"))
        compose.onNodeWithTag("dm_person:8").performClick()
        compose.waitUntil(5_000) { thread.state.value.loaded && !thread.state.value.busy }
        compose.onNodeWithTag("dm_composer").performTextInput("Scoped draft fixture")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("dm_inbox").assertIsDisplayed()
        compose.waitUntil(5_000) { !inbox.state.value.busy && inbox.state.value.inbox != null }
        compose.onNodeWithText("3 unread").assertDoesNotExist()
        compose.onNodeWithTag("dm_inbox").performScrollToNode(hasTestTag("dm_conversation:8"))
        compose.onNodeWithTag("dm_conversation:8").performClick()
        compose.waitUntil(5_000) { thread.state.value.loaded && !thread.state.value.busy }
        compose.onNodeWithText("Scoped draft fixture").assertIsDisplayed()
        val readsBeforeSwitch = direct.histories
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(generation = sessions.state.value.generation + 1,
            user = SessionUser(9, "Replacement", listOf("member"), 1, false)) }
        compose.waitUntil(5_000) { thread.state.value.peerId == null }
        compose.onNodeWithText("Scoped draft fixture").assertDoesNotExist(); compose.onNodeWithText("Back").assertDoesNotExist()
        compose.runOnIdle { assertEquals(readsBeforeSwitch, direct.histories); assertTrue(thread.drafts.value.isEmpty()); thread.background(); inbox.background() }
    }

    private class NativeDirects(override val session: StateFlow<SessionState> = MutableStateFlow(SessionState(generation = 1,
        phase = SessionPhase.AUTHENTICATED, user = SessionUser(7, "Viewer", listOf("member"), 1, false)))) : DirectOperations {
        val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "for up to 30 days", 30),
            RevisionPrivacy(false, "super_admin", true, true, false))
        @Volatile var rows = listOf(row(18))
        @Volatile var sends = 0; @Volatile var reads = 0; @Volatile var blocks = 0; @Volatile var histories = 0
        var sendFailure: ApiFailure? = null; var available = true; var blocked = false; var readGate: CompletableDeferred<Unit>? = null
        var contextFailure: ApiFailure? = null
        override suspend fun context(peerId: Long): DirectContext {
            contextFailure?.let { throw it }
            return DirectContext(MessagePerson(peerId, "Peer"), DirectRelationship(blocked, !blocked && available), privacy, DisplayPreferences(), null)
        }
        override suspend fun inbox(): DirectInbox {
            val last = rows.lastOrNull() ?: return DirectInbox(emptyList(), privacy, DisplayPreferences())
            return DirectInbox(listOf(DirectConversation(MessagePerson(8, "Peer"),
                DirectPreview(last.id, last.body, last.createdAt, last.outgoing, last.deleted, last.editedAt, last.attachment),
                rows.count { !it.outgoing && it.readAt == null })), privacy, DisplayPreferences())
        }
        override suspend fun search(prefix: String, limit: Int) = listOf(MessagePerson(8, "Peer"))
        override suspend fun history(peerId: Long, beforeId: Long?, limit: Int): DirectPage {
            histories++
            val page = rows.filter { peerId in setOf(it.sender.id, it.recipient.id) && (beforeId == null || it.id < beforeId) }.takeLast(limit)
            return DirectPage(page, page.firstOrNull()?.id, page.size == limit)
        }
        override suspend fun send(peerId: Long, body: String, replyId: Long?): Long {
            sends++; sendFailure?.let { throw it }; rows += row(99, body, read = null, outgoing = true); return 99
        }
        override suspend fun read(peerId: Long): Int {
            reads++; readGate?.await()
            val count = rows.count { !it.outgoing && it.readAt == null }
            rows = rows.map { row(it.id, it.body.orEmpty(), read = if (it.outgoing) it.readAt else "2026-10-20T10:01:00Z",
                outgoing = it.outgoing, deleted = it.deleted, attachment = it.attachment) }
            return count
        }
        override suspend fun setBlocked(peerId: Long, blocked: Boolean): DirectRelationship {
            blocks++; this.blocked = blocked; return DirectRelationship(blocked, !blocked && available)
        }
    }
    companion object {
        private fun row(id: Long, body: String = "Native history fixture", read: String? = "2026-10-20T10:01:00Z",
            outgoing: Boolean = false, deleted: Boolean = false, attachment: MessageAttachment? = null) = DirectMessage(id,
            MessagePerson(if (outgoing) 7 else 8, "Sender"), MessagePerson(if (outgoing) 8 else 7, "Recipient"),
            if (deleted) null else body, read, "2026-10-20T10:00:00Z", outgoing, deleted, null, outgoing, outgoing,
            null, emptyList(), emptyList(), attachment)
    }
}
