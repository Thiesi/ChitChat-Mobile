package io.github.thiesi.chitchat.feature.conversation

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.thiesi.chitchat.core.ui.RateLineForm
import io.github.thiesi.chitchat.core.ui.RateLineForm.FULL
import io.github.thiesi.chitchat.core.ui.RateLineForm.HIDDEN
import io.github.thiesi.chitchat.core.ui.RateLineForm.SHORT
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.RateBudget
import io.github.thiesi.chitchat.core.session.RateWait
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.LiveAnnouncements
import io.github.thiesi.chitchat.testing.LiveFlushSentinel
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import io.github.thiesi.chitchat.testing.openTab
import io.github.thiesi.chitchat.testing.performNativeTap
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * #323b1 captures for the PL's J (her list, items 1–4, 11 and 12): the composer's own rate-wait line in the real app root
 * with the native keyboard, at a real Configuration font scale. The fake server refuses the send with `429 rate_limited`;
 * a known wait also has its deadline in the session (as the repository records it). `rate-{room|dm}-{send|ping}-…`.
 * Fake server writes, no live mutation.
 *
 * PL refinements (re-Js on b2c34c4 and 12c3a36) and TL conditions: per capture, which form was chosen (full; short where
 * the full line doesn't leave the timeline a 48dp row; hidden with the Details dot where not even the short form fits at
 * the composer's natural height), the line unclipped and above the keyboard, and the composer's field width, arrangement
 * and field height equal with and without the line; the line or the dot after the keyboard hides (room and DM); the full
 * line first in Details only while short or hidden; a reply started during the wait re-decides the form; Send's
 * announcements counted from real accessibility events: one on appearance, one per refused tap, none on swaps; a refused
 * tap while hidden hides the keyboard, shows the line and clears the dot, keeping focus, draft and reply.
 */
class RatePresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    /** The activity window's IME inset, read in composition (as the conversation journeys do) for the one native tap. */
    @Volatile private var imeBottom = 0
    /** The form the composer's wait line last drew (short?) and its visible text, from the observation-only probe. */
    @Volatile private var form: Pair<Boolean, String>? = null

    private val sentinel = androidx.compose.runtime.mutableIntStateOf(0)

    // 1. Room composer with the send line, a draft and a reply chip: light and dark × default and 200%. At 200% the full
    // line doesn't fit beside the composer with the chip: the short form, and (light) Details with the full line first.
    // Light default also takes a refused tap with the line visible: one more announcement, the line unchanged.
    @Test fun roomSendLight() = scales { name, factor -> flow("rate-room-send-light-$name", dm = false, dark = false, factor,
        reply = true, expect = if (factor > 1f) SHORT else FULL, details = true, visibleTap = factor == 1f) }
    @Test fun roomSendDark() = scales { name, factor -> flow("rate-room-send-dark-$name", dm = false, dark = true, factor,
        reply = true, expect = if (factor > 1f) SHORT else FULL) }
    // 2. DM composer with the send line: light and dark × default and 200% (short at 200%, as the rule picks).
    @Test fun dmSendLight() = scales { name, factor -> flow("rate-dm-send-light-$name", dm = true, dark = false, factor,
        expect = if (factor > 1f) SHORT else FULL) }
    @Test fun dmSendDark() = scales { name, factor -> flow("rate-dm-send-dark-$name", dm = true, dark = true, factor,
        expect = if (factor > 1f) SHORT else FULL) }
    // 3. Room composer at 200% landscape with the keyboard up: no line, the Details dot (hidden). Then a refused tap:
    // the keyboard hides and the line shows (rate-room-send-light-font200-landscape-after-tap).
    @Test fun roomSendLandscapeKeyboard() =
        flow("rate-room-send-light-font200-landscape-ime", dm = false, dark = false, 2f, landscape = true, keyboard = true,
            expect = HIDDEN, hiddenTap = true)
    // 4. The ping line (default light).
    @Test fun roomPing() = flow("rate-room-ping-light-default", dm = false, dark = false, 1f, ping = true, expect = FULL)
    // 11. The unknown-wait send line (light default).
    @Test fun roomSendUnknownWait() = flow("rate-room-send-unknown-light-default", dm = false, dark = false, 1f, known = false, expect = FULL)
    // 12. A conversation with a composer wait and no status row (asserted).
    @Test fun roomConversationNoStatusRow() =
        flow("rate-room-conversation-light-default", dm = false, dark = false, 1f, noStatusRow = true, expect = FULL)
    // PL: room at 200% with the notice shown and no chip, then a reply started: the composer stays equal and the form is
    // re-decided (here, to short). Asserts only, no capture.
    @Test fun roomReplyStartedDuringTheWait() =
        flow(null, dm = false, dark = false, 2f, replyLater = true, expect = SHORT)

    private fun scales(run: (String, Float) -> Unit) { run("default", 1f); run("font200", 2f) }

    private fun flow(capture: String?, dm: Boolean, dark: Boolean, fontScale: Float, expect: RateLineForm, reply: Boolean = false,
        ping: Boolean = false, known: Boolean = true, landscape: Boolean = false, keyboard: Boolean = false,
        noStatusRow: Boolean = false, details: Boolean = false, replyLater: Boolean = false, visibleTap: Boolean = false,
        hiddenTap: Boolean = false) {
        val name = capture ?: "rate-room-reply-during-wait"
        form = null
        FontScaleTestActivity.requestedFontScale = fontScale
        val sessions = TestSessions()
        val api = NativeChatFixtures(sessions)
        val settingsStore = object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server =
                (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
                theme = if (dark) ThemePreference.DARK else ThemePreference.LIGHT))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun savePureBlack(enabled: Boolean) = Unit
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
        }
        val sendLabel = text(if (dm) R.string.dm_send else R.string.conversation_send)
        val detailsLabel = text(if (dm) R.string.dm_details else R.string.conversation_details)
        // TL/PL: Send carries every announcement; counted from real accessibility events, connected before launch.
        LiveAnnouncements { node -> node.contentDescription?.toString() == sendLabel }.use { heard ->
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            if (landscape) {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(5_000) {
                    var orientation = 0
                    scenario.onActivity { orientation = it.resources.configuration.orientation }
                    orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
            }
            lateinit var room: ConversationViewModel
            lateinit var thread: DirectThreadViewModel
            lateinit var notices: NotificationCenterViewModel
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                room = ViewModelProvider(activity, ConversationViewModel.factory(api.rooms, mutations = api.authorChanges))[ConversationViewModel::class.java]
                thread = ViewModelProvider(activity, DirectThreadViewModel.factory(api.directs, mutations = api.authorChanges))[DirectThreadViewModel::class.java]
                val inbox = ViewModelProvider(activity, DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
                notices = ViewModelProvider(activity, NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
                val targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
                val settings = ViewModelProvider(activity, SettingsViewModel.factory(settingsStore))[SettingsViewModel::class.java]
                val auth = ViewModelProvider(activity, AuthViewModel.factory(sessions))[AuthViewModel::class.java]
                activity.setContent {
                    val bottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
                    androidx.compose.runtime.SideEffect { imeBottom = bottom }
                    androidx.compose.runtime.CompositionLocalProvider(io.github.thiesi.chitchat.core.ui.LocalRateLineProbe provides
                        { isShort, visible -> form = isShort to visible }) {
                        Box(Modifier.fillMaxSize()) {
                            TestChitChatApp(settings, auth, conversationModel = room, directInbox = inbox,
                                directThread = thread, notifications = notices, messageTargets = targets)
                            LiveFlushSentinel(sentinel.intValue, Modifier.align(androidx.compose.ui.Alignment.TopStart))
                        }
                    }
                }
            }
            // Into the conversation, as the journeys do: sign in, Activity, the notification for the room or the DM.
            compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
            compose.runOnIdle {
                runBlocking { sessions.signIn("Viewer", "fixture-password") }
                sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy)
                runBlocking { if (dm) api.directs.send(8, "Authored direct fixture", null) else api.rooms.send(43, "Authored fixture", null) }
            }
            compose.onNodeWithTag("bar_title").performNativeTap()
            compose.openTab("activity").performNativeTap()
            compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy }
            val notice = if (dm) "notification_view:55" else "notification_view:60"
            compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag(notice))
            compose.onNodeWithTag(notice).performNativeTap()
            if (dm) {
                compose.waitUntil(5_000) { thread.state.value.peerId == 8L && thread.state.value.loaded && !thread.state.value.busy }
                compose.onNodeWithContentDescription(text(R.string.dm_details)).performNativeTap()
                compose.onNode(hasText(text(R.string.dm_latest)) and hasAnyAncestor(isDialog())).performScrollTo().performNativeTap()
                compose.waitUntil(5_000) { thread.state.value.let { it.loaded && !it.busy && !it.stale } }
            } else {
                compose.waitUntil(5_000) { room.state.value.roomId == 43L && room.state.value.loaded && !room.state.value.busy }
                compose.onNodeWithContentDescription(text(R.string.conversation_details)).performNativeTap()
                compose.onNode(hasText(text(R.string.conversation_latest)) and hasAnyAncestor(isDialog())).performScrollTo().performNativeTap()
                compose.waitUntil(5_000) { room.state.value.let { it.loaded && !it.busy && !it.stale && !it.reading } }
                if (reply) startReply(room)
            }
            // The composer's arrangement without the line: the chip settled, the keyboard confirmed down, idle (TL).
            val input = if (dm) "dm_composer" else "conversation_composer"
            val send = if (dm) "dm_send" else "conversation_send"
            keyboardDown()
            val before = arrangement(input, send)
            // The draft, then the server's own refusal; a known wait's deadline as the repository records it.
            compose.tapNativeInput(input, { imeBottom }).performTextReplacement(
                if (ping) "/ping Peer fixture" else if (dm) "Unsent direct fixture" else "Unsent fixture")
            compose.waitForIdle()
            val beforeKeyboard = arrangement(input, send) // The landscape-ime capture's state, still without the line.
            val budget = if (dm) RateBudget.DIRECT_MESSAGE_SEND else if (ping) RateBudget.ROOM_PING else RateBudget.ROOM_SEND
            compose.runOnIdle {
                val refusal = ApiFailure(FailureKind.HTTP, 429, "rate_limited", if (known) 300 else null)
                if (dm) api.directs.sendFailure = refusal else api.rooms.sendFailure = refusal
                if (known) sessions.state.value = sessions.state.value.copy(rateWaits = mapOf(budget to RateWait(
                    android.os.SystemClock.elapsedRealtime() + 300_000, sessions.state.value.generation, checkNotNull(sessions.state.value.user).id)))
            }
            heard.flush(compose, sentinel)
            check(heard.count == 0) { "$name: announced before the refusal ${heard.spoken()}" }
            compose.onNodeWithTag(send).performNativeTap()
            compose.waitUntil(5_000) { (if (dm) thread.state.value.sendLimit else room.state.value.sendLimit) != null }
            compose.waitForIdle()
            lineOrDot(name, "with the keyboard up", detailsLabel)
            // TL condition (a) and PL: one announcement when the notice appears, visible or hidden.
            heard.flush(compose, sentinel)
            check(heard.count == 1) { "$name: ${heard.count} announcements on appearance ${heard.spoken()}" }
            if (!keyboard) {
                keyboardDown()
                lineOrDot(name, "after the keyboard hid", detailsLabel) // TL condition 3.
            }
            if (replyLater) {
                // PL: the notice shown with no chip, then a reply: re-decided from the new layout, the composer unchanged.
                startReply(room)
                keyboardDown()
                lineOrDot(name, "after the reply started", detailsLabel)
            }
            heard.flush(compose, sentinel)
            check(heard.count == 1) { "$name: a keyboard or form swap announced ${heard.spoken()}" }
            val expectedArrangement = if (keyboard) beforeKeyboard else before
            // Capture first, so a failing assert still leaves its image (PL).
            if (capture != null) capture(capture)
            // PL (J on af1481d): never assertIsDisplayed alone, which passes on a sliver. The line (when drawn), the composer
            // and Send are each unclipped and inside the window, and above the keyboard top when it's up.
            formIs(name, expect, detailsLabel)
            if (expect != HIDDEN) unclipped(name, "rate_wait_line")
            unclipped(name, input)
            unclipped(name, send)
            // PL refinement: the composer's field width, arrangement and field height never change because of the line.
            val after = arrangement(input, send)
            check(after == expectedArrangement) {
                "$name: the composer changed (width, Send below, field height) $expectedArrangement -> $after" }
            if (noStatusRow) check(compose.onAllNodesWithTag("status_slot").fetchSemanticsNodes().isEmpty()) {
                "$name: a composer wait shows no status row" }
            if (details) {
                // The full line is first in Details only while the short form (or none) shows; never twice.
                compose.onNodeWithContentDescription(detailsLabel).performNativeTap()
                compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                val inDetails = compose.onAllNodes(hasContentDescription(fullLine(dm, ping, known)) and hasAnyAncestor(isDialog()))
                    .fetchSemanticsNodes().size
                val shortShown = expect != FULL
                if (shortShown) capture("rate-room-send-light-font200-details", dialog = true)
                check(inDetails == (if (shortShown) 1 else 0)) { "$name: Details has $inDetails wait lines, form=$expect" }
                androidx.test.espresso.Espresso.pressBack()
                compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            }
            if (visibleTap) {
                // PL ruling (a): a refused tap with the line visible re-announces once; the line stays as it is.
                val shown = form
                compose.onNodeWithTag(send).performNativeTap()
                compose.waitUntil(5_000) { room.state.value.sendLimitRepeats == 1 && !room.state.value.busy }
                compose.waitForIdle()
                heard.flush(compose, sentinel)
                check(heard.count == 2) { "$name: ${heard.count} announcements after one refused tap ${heard.spoken()}" }
                check(form == shown && compose.onAllNodesWithTag("rate_wait_line").fetchSemanticsNodes().size == 1) {
                    "$name: the line changed on a refused tap: $shown -> $form" }
            }
            if (hiddenTap) {
                // PL ruling (b): a refused tap while hidden hides the keyboard (focus, draft and reply kept), the line shows
                // by the rule and the dot clears; one announcement.
                check(detailsState(detailsLabel) != null) { "$name: no Details dot while hidden" }
                compose.onNodeWithTag(send).performNativeTap()
                compose.waitUntil(5_000) { room.state.value.sendLimitRepeats == 1 && !room.state.value.busy }
                compose.waitUntil(5_000) { imeBottom == 0 }
                compose.waitForIdle()
                capture("rate-room-send-light-font200-landscape-after-tap")
                check(compose.onAllNodesWithTag("rate_wait_line").fetchSemanticsNodes().size == 1) { "$name: no line after the tap" }
                unclipped(name, "rate_wait_line")
                unclipped(name, input)
                unclipped(name, send)
                check(detailsState(detailsLabel) == null) { "$name: the Details dot stayed: ${detailsState(detailsLabel)}" }
                check(room.state.value.draft == "Unsent fixture") { "$name: the draft changed" }
                check(compose.onNodeWithTag(input).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true) {
                    "$name: focus left the field" }
                check(arrangement(input, send) == before) { "$name: the composer changed after the tap ${arrangement(input, send)} vs $before" }
                heard.flush(compose, sentinel)
                check(heard.count == 2) { "$name: ${heard.count} announcements after one refused tap ${heard.spoken()}" }
            }
            if (keyboard && !hiddenTap) keyboardDown()
        }
        }
    }

    private fun keyboardDown() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { imeBottom == 0 }
        compose.waitForIdle()
    }

    private fun fullLine(dm: Boolean, ping: Boolean, known: Boolean): String {
        val wait = context.resources.getQuantityString(R.plurals.wait_about_minutes, 5, 5)
        return when {
            !known -> text(R.string.rate_wait_messages_unknown)
            ping && !dm -> context.getString(R.string.rate_wait_pings, wait)
            else -> context.getString(R.string.rate_wait_messages, wait)
        }
    }

    private fun startReply(room: ConversationViewModel) {
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("message:101"))
        compose.onNodeWithText("Authored fixture").performNativeTap()
        compose.onNodeWithTag("message_reply_action").performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { room.state.value.composerReply != null }
    }

    private fun detailsState(label: String) =
        compose.onNodeWithContentDescription(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    /** TL condition 3 as amended: while a notice exists, the line is drawn, or hidden with the Details dot showing. */
    private fun lineOrDot(name: String, moment: String, detailsLabel: String) {
        val lines = compose.onAllNodesWithTag("rate_wait_line").fetchSemanticsNodes().size
        check(lines == 1 || (lines == 0 && detailsState(detailsLabel) != null)) { "$name: $lines wait lines and no Details dot $moment" }
    }

    private fun formIs(name: String, expect: RateLineForm, detailsLabel: String) {
        val lines = compose.onAllNodesWithTag("rate_wait_line").fetchSemanticsNodes().size
        val drawn = when { lines == 0 -> HIDDEN; form?.first == true -> SHORT; else -> FULL }
        check(drawn == expect) { "$name: expected $expect, drew $drawn (lines=$lines, form=$form)" }
        if (expect == HIDDEN) check(detailsState(detailsLabel) != null) { "$name: hidden without the Details dot" }
    }

    /** The composer's field width, whether Send sits on a row below the field, and the field's height, in px. */
    private fun arrangement(input: String, send: String): Triple<Int, Boolean, Int> {
        val field = compose.onNodeWithTag(input).fetchSemanticsNode().boundsInWindow
        val button = compose.onNodeWithTag(send).fetchSemanticsNode().boundsInWindow
        return Triple(kotlin.math.round(field.width).toInt(), button.top >= field.bottom - 0.5f, kotlin.math.round(field.height).toInt())
    }

    private fun unclipped(name: String, tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val visible = node.boundsInWindow
        val window = compose.onRoot().fetchSemanticsNode().size
        val detail = "$name: $tag visible=$visible size=${node.size} window=$window ime=$imeBottom"
        check(kotlin.math.abs(visible.width - node.size.width) <= 1f && kotlin.math.abs(visible.height - node.size.height) <= 1f) {
            "Clipped ($detail)" }
        check(visible.left >= 0f && visible.top >= 0f && visible.right <= window.width + 0.5f && visible.bottom <= window.height + 0.5f) {
            "Outside the window ($detail)" }
        if (imeBottom > 0) check(visible.bottom <= window.height - imeBottom + 0.5f) { "Under the keyboard ($detail)" }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(context.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-rate-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
