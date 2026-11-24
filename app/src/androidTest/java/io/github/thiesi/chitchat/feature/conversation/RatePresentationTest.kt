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
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.isRoot
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
 * announcements counted from real accessibility events: one on appearance, one per refused tap, none on swaps; a refusal
 * whose form resolves to hidden hides the keyboard once (the line shows, the dot clears, focus and draft kept), and a
 * first tap on a never-focused field bringing the keyboard up hides the line behind the dot with no hide and no
 * announcement. Only first native taps on unfocused fields (TL), never the already-focused re-tap.
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
    // One test per scale (PL): a failure in one never skips another's capture.
    @Test fun roomSendLightDefault() = flow("rate-room-send-light-default", dm = false, dark = false, 1f, reply = true,
        expect = FULL, details = true, visibleTap = true)
    @Test fun roomSendLightFont200() = flow("rate-room-send-light-font200", dm = false, dark = false, 2f, reply = true,
        expect = SHORT, details = true)
    @Test fun roomSendDarkDefault() = flow("rate-room-send-dark-default", dm = false, dark = true, 1f, reply = true, expect = FULL)
    @Test fun roomSendDarkFont200() = flow("rate-room-send-dark-font200", dm = false, dark = true, 2f, reply = true, expect = SHORT)
    // 2. DM composer with the send line: light and dark × default and 200% (short at 200%, as the rule picks).
    @Test fun dmSendLightDefault() = flow("rate-dm-send-light-default", dm = true, dark = false, 1f, expect = FULL)
    @Test fun dmSendLightFont200() = flow("rate-dm-send-light-font200", dm = true, dark = false, 2f, expect = SHORT)
    @Test fun dmSendDarkDefault() = flow("rate-dm-send-dark-default", dm = true, dark = true, 1f, expect = FULL)
    @Test fun dmSendDarkFont200() = flow("rate-dm-send-dark-font200", dm = true, dark = true, 2f, expect = SHORT)
    // 3. Room composer at 200% landscape (PL ruling; TL: first taps on an unfocused field only, never the already-focused
    // re-tap). Case A: the keyboard up, the first refusal resolves to hidden and hides it once (…-landscape-after-tap).
    // Case B: the refusal with the keyboard down shows the line; a first tap on the field then brings the keyboard up,
    // hidden behind the Details dot with no hide and no announcement (…-landscape-ime); a refused Send then hides once.
    @Test fun roomSendLandscapeFirstRefusalHides() =
        flow(null, dm = false, dark = false, 2f, landscape = true, expect = HIDDEN, landscapeCase = 'A')
    @Test fun roomSendLandscapeOnlyARefusalHides() =
        flow(null, dm = false, dark = false, 2f, landscape = true, expect = HIDDEN, landscapeCase = 'B')
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


    private fun flow(capture: String?, dm: Boolean, dark: Boolean, fontScale: Float, expect: RateLineForm, reply: Boolean = false,
        ping: Boolean = false, known: Boolean = true, landscape: Boolean = false, keyboard: Boolean = false,
        noStatusRow: Boolean = false, details: Boolean = false, replyLater: Boolean = false, visibleTap: Boolean = false,
        landscapeCase: Char? = null) {
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
        val waitText = fullLine(dm, ping, known)
        val detailsLabel = text(if (dm) R.string.dm_details else R.string.conversation_details)
        // TL/PL: the wait line carries every announcement; counted from real accessibility events, connected before launch.
        LiveAnnouncements { node -> node.contentDescription?.toString()?.trimEnd() == waitText }.use { heard ->
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
            // Compose's accessibility-event gate on the attached screen, before any counted change (reset on close).
            scenario.onActivity { heard.attach(it.window.decorView) }
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
            // The draft is prefilled through the model, so the baseline already holds text (PL: an empty field measures
            // shorter) and the field is never focused before its first tap. Then the composer's arrangement without the
            // line: the chip settled, the keyboard confirmed down, idle (TL).
            val input = if (dm) "dm_composer" else "conversation_composer"
            val send = if (dm) "dm_send" else "conversation_send"
            val draft = if (ping) "/ping Peer fixture" else if (dm) "Unsent direct fixture" else "Unsent fixture"
            compose.runOnIdle { if (dm) thread.updateDraft(draft) else room.updateDraft(draft) }
            keyboardDown()
            val before = arrangement(input, send)
            if (landscapeCase != null) {
                landscape(landscapeCase, room, sessions, api, heard, input, send, detailsLabel, before)
                return@use
            }
            // A native first tap brings the keyboard up; then the server's own refusal, with a known wait's deadline as the
            // repository records it.
            compose.tapNativeInput(input, { imeBottom })
            compose.waitForIdle()
            val beforeKeyboard = arrangement(input, send) // The landscape-ime capture's state, still without the line.
            val budget = if (dm) RateBudget.DIRECT_MESSAGE_SEND else if (ping) RateBudget.ROOM_PING else RateBudget.ROOM_SEND
            compose.runOnIdle {
                val refusal = ApiFailure(FailureKind.HTTP, 429, "rate_limited", if (known) 300 else null)
                if (dm) api.directs.sendFailure = refusal else api.rooms.sendFailure = refusal
                if (known) sessions.state.value = sessions.state.value.copy(rateWaits = mapOf(budget to RateWait(
                    android.os.SystemClock.elapsedRealtime() + 300_000, sessions.state.value.generation, checkNotNull(sessions.state.value.user).id)))
            }
            // TL (after run 45299066691): the carrier node already exists, at least 1dp, before the notice appears.
            val heightBeforeNotice = lineHeight()
            // Announcements are counted between delivered sentinels (PL, TL: the positive control). A mark that times out is
            // recorded and only fails after the capture (verify), so it can't cost the J its images.
            val opened = heard.mark(compose, sentinel)
            compose.onNodeWithTag(send).performNativeTap()
            compose.waitUntil(5_000) { (if (dm) thread.state.value.sendLimit else room.state.value.sendLimit) != null }
            compose.waitForIdle()
            lineOrDot(name, "with the keyboard up", detailsLabel)
            // TL diagnostic (run 45244641951): the carrier's height when the notice appears; a zero-size node announces nothing.
            val heightAtAppearance = lineHeight()
            val drawnAtAppearance = lineDrawn()
            val appeared = heard.mark(compose, sentinel)
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
            val swapped = heard.mark(compose, sentinel)
            val expectedArrangement = if (keyboard) beforeKeyboard else before
            // Capture first, so a failing assert (announcements included) still leaves its image (PL).
            if (capture != null) capture(capture)
            // Details is captured before any assert too (PL): the full line first only while short or hidden, never twice.
            var inDetails = -1
            if (details) {
                compose.onNodeWithContentDescription(detailsLabel).performNativeTap()
                compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                inDetails = compose.onAllNodes(hasContentDescription(fullLine(dm, ping, known)) and hasAnyAncestor(isDialog()))
                    .fetchSemanticsNodes().size
                if (expect != FULL) capture("rate-room-send-light-font200-details", dialog = true)
                androidx.test.espresso.Espresso.pressBack()
                compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            }
            // TL condition (a) and PL: none before the notice, exactly one for its appearance, none for the keyboard or form
            // swaps since, each between two delivered sentinels.
            heard.verify()
            check(heightBeforeNotice >= oneDp()) { "$name: the carrier was ${heightBeforeNotice}px before the notice (expected 1dp or more)" }
            check(opened == 0) { "$name: $opened announcements before the refusal ${heard.spoken()}" }
            check(appeared - opened == 1) {
                "$name: ${appeared - opened} announcements for the appearance (line height ${heightAtAppearance}px, drawn=$drawnAtAppearance) ${heard.spoken()}" }
            // The carrier has size when the notice appears: the drawn line, or the hidden 1dp strip (TL, PL).
            check(heightAtAppearance >= context.resources.displayMetrics.density - 0.5f) {
                "$name: the carrier was ${heightAtAppearance}px tall at appearance (drawn=$drawnAtAppearance)" }
            check(swapped - appeared == 0) { "$name: ${swapped - appeared} announcements for the keyboard or form swaps ${heard.spoken()}" }
            check(heard.texts() == listOf(waitText)) { "$name: the counted texts ${heard.texts()} (expected the full line once) ${heard.spoken()}" }
            // PL (J on af1481d): never assertIsDisplayed alone, which passes on a sliver. The line (when drawn), the composer
            // and Send are each unclipped and inside the window, and above the keyboard top when it's up.
            formIs(name, expect, detailsLabel)
            oneWaitNode(name, waitText)
            if (expect != HIDDEN) unclipped(name, "rate_wait_line")
            unclipped(name, input)
            unclipped(name, send)
            // PL refinement: the composer's field width, arrangement and field height never change because of the line.
            val after = arrangement(input, send)
            check(after == expectedArrangement) {
                "$name: the composer changed (width, Send below, field height) $expectedArrangement -> $after" }
            if (noStatusRow) check(compose.onAllNodesWithTag("status_slot").fetchSemanticsNodes().isEmpty()) {
                "$name: a composer wait shows no status row" }
            if (details) check(inDetails == (if (expect != FULL) 1 else 0)) { "$name: Details has $inDetails wait lines, form=$expect" }
            if (visibleTap) {
                // PL ruling (a): a refused tap with the line visible re-announces once; the line stays as it is.
                val shown = form
                val beforeTap = heard.mark(compose, sentinel)
                compose.onNodeWithTag(send).performNativeTap()
                compose.waitUntil(5_000) { room.state.value.sendLimitRepeats == 1 && !room.state.value.busy }
                compose.waitForIdle()
                val afterTap = heard.mark(compose, sentinel)
                heard.verify()
                check(afterTap - beforeTap == 1) { "$name: ${afterTap - beforeTap} announcements for one refused tap ${heard.spoken()}" }
                check(heard.texts() == listOf(waitText, "$waitText ")) { "$name: appearance plus one refused tap, in order: ${heard.texts()}" }
                check(form == shown && lineDrawn()) {
                    "$name: the line changed on a refused tap: $shown -> $form" }
            }
            if (keyboard) keyboardDown()
        }
        }
    }

    /** The two landscape cases; first native taps on an unfocused field only (TL). */
    private fun landscape(case: Char, room: ConversationViewModel, sessions: TestSessions, api: NativeChatFixtures,
        heard: LiveAnnouncements, input: String, send: String, detailsLabel: String, before: Triple<Int, Boolean, Int>) {
        val name = if (case == 'A') "rate-room-send-light-font200-landscape-after-tap" else "rate-room-send-light-font200-landscape-ime"
        // The draft was prefilled through the model before the baseline, so the field is never focused before the first tap.
        compose.runOnIdle {
            api.rooms.sendFailure = ApiFailure(FailureKind.HTTP, 429, "rate_limited", 300)
            sessions.state.value = sessions.state.value.copy(rateWaits = mapOf(RateBudget.ROOM_SEND to RateWait(
                android.os.SystemClock.elapsedRealtime() + 300_000, sessions.state.value.generation, checkNotNull(sessions.state.value.user).id)))
        }
        compose.waitForIdle()
        check(compose.onAllNodes(isFocused()).fetchSemanticsNodes().isEmpty()) { "$name: something is focused before the first tap" }
        // TL: the carrier exists, at least 1dp, before the notice (checked after the captures, below).
        val heightBeforeNotice = lineHeight()
        // Counted between delivered sentinels; a timed-out mark fails only after the capture (verify).
        val opened = heard.mark(compose, sentinel)
        if (case == 'A') {
            // Case A: a native first tap on the unfocused field brings the keyboard up (the strict proof); the refusal's
            // form resolves to hidden, so the one-shot hide runs.
            compose.tapNativeInput(input, { imeBottom })
            compose.waitForIdle()
            compose.onNodeWithTag(send).performNativeTap()
            compose.waitUntil(5_000) { room.state.value.sendLimit != null }
            compose.waitUntil(5_000) { imeBottom == 0 }
            compose.waitForIdle()
            capture(name)
            val refused = heard.mark(compose, sentinel)
            heard.verify()
            check(opened == 0) { "$name: $opened announcements before the refusal ${heard.spoken()}" }
            check(refused - opened == 1) { "$name: ${refused - opened} announcements for the first refusal ${heard.spoken()}" }
            val full = fullLine(dm = false, ping = false, known = true)
            check(heard.texts() == listOf(full)) { "$name: the counted texts ${heard.texts()} (expected the full line once)" }
            check(lineDrawn()) { "$name: no line after the hide" }
            unclipped(name, "rate_wait_line")
            unclipped(name, input)
            unclipped(name, send)
            check(detailsState(detailsLabel) == null) { "$name: the Details dot stayed: ${detailsState(detailsLabel)}" }
            check(room.state.value.draft == "Unsent fixture") { "$name: the draft changed" }
            focusedOnly(name, input, "after the hide")
            oneWaitNode(name, fullLine(dm = false, ping = false, known = true))
            check(arrangement(input, send) == before) { "$name: the composer changed after the hide ${arrangement(input, send)} vs $before" }
        } else {
            // Case B: the refusal with the keyboard down shows the line; tapping Send moves no focus.
            compose.onNodeWithTag(send).performNativeTap()
            compose.waitUntil(5_000) { room.state.value.sendLimit != null }
            compose.waitForIdle()
            // Observed now, asserted after the capture (PL: capture before the asserts).
            val focusAfterSend = compose.onAllNodes(isFocused()).fetchSemanticsNodes().size
            val imeAfterSend = imeBottom
            val linesAfterSend = if (lineDrawn()) 1 else 0
            val arrangementAfterSend = arrangement(input, send)
            val refused = heard.mark(compose, sentinel)
            // A native first tap on the never-focused field: the keyboard comes up, the line hides behind the dot, no hide.
            compose.tapNativeInput(input, { imeBottom })
            compose.waitForIdle()
            capture(name)
            val returned = heard.mark(compose, sentinel)
            check(focusAfterSend == 0) { "$name: tapping Send moved focus ($focusAfterSend focused nodes)" }
            check(imeAfterSend == 0) { "$name: the keyboard came up on Send" }
            check(linesAfterSend == 1) { "$name: no line with the keyboard down" }
            check(arrangementAfterSend == before) { "$name: the composer changed with the line $arrangementAfterSend vs $before" }
            check(imeBottom > 0) { "$name: the keyboard was hidden without a refusal" }
            formIs(name, HIDDEN, detailsLabel)
            oneWaitNode(name, fullLine(dm = false, ping = false, known = true))
            unclipped(name, input)
            unclipped(name, send)
            val floor = kotlin.math.round(48 * context.resources.displayMetrics.density).toInt()
            check(arrangement(input, send).third >= floor - 1) { "$name: the field is below 48dp: ${arrangement(input, send)}" }
            heard.verify()
            check(opened == 0) { "$name: $opened announcements before the refusal ${heard.spoken()}" }
            check(refused - opened == 1) { "$name: ${refused - opened} announcements for the refusal ${heard.spoken()}" }
            check(returned - refused == 0) { "$name: ${returned - refused} announcements for the keyboard's return ${heard.spoken()}" }
            val full = fullLine(dm = false, ping = false, known = true)
            check(heard.texts() == listOf(full)) { "$name: the counted texts ${heard.texts()} (expected the full line once)" }
            // A refused Send while hidden hides the keyboard once and announces once.
            compose.onNodeWithTag(send).performNativeTap()
            compose.waitUntil(5_000) { room.state.value.sendLimitRefusals == 2 && !room.state.value.busy }
            compose.waitUntil(5_000) { imeBottom == 0 }
            compose.waitForIdle()
            val tapped = heard.mark(compose, sentinel)
            heard.verify()
            check(tapped - returned == 1) { "$name: ${tapped - returned} announcements for the refused tap ${heard.spoken()}" }
            check(heard.texts() == listOf(full, "$full ")) { "$name: refusal plus one refused tap, in order: ${heard.texts()}" }
            check(lineDrawn()) { "$name: no line after the hide" }
            check(detailsState(detailsLabel) == null) { "$name: the Details dot stayed after the hide" }
            focusedOnly(name, input, "after the refused tap's hide")
        }
        keyboardDown()
        check(heightBeforeNotice >= oneDp()) { "$name: the carrier was ${heightBeforeNotice}px before the notice (expected 1dp or more)" }
    }

    private fun focusedOnly(name: String, input: String, moment: String) {
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        check(focused.size == 1 && focused.single().config.getOrNull(SemanticsProperties.TestTag) == input) {
            "$name: focus isn't only on the field $moment: ${focused.map { it.config.getOrNull(SemanticsProperties.TestTag) }}" }
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

    private fun lineHeight() = compose.onNodeWithTag("rate_wait_line").fetchSemanticsNode().size.height
    private fun oneDp() = kotlin.math.round(context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /** The persistent wait line (one node: a 1dp strip with no notice and while hidden) shows its text. */
    private fun lineDrawn() =
        compose.onNodeWithTag("rate_wait_line").fetchSemanticsNode().size.height > 2 * context.resources.displayMetrics.density

    /**
     * PL and TL: outside Send's state description and Details, exactly one node in the conversation exposes the wait text
     * (the line, drawn or not).
     */
    private fun oneWaitNode(name: String, text: String) {
        val nodes = compose.onAllNodes(hasContentDescription(text, substring = false) and !hasAnyAncestor(isDialog()))
            .fetchSemanticsNodes() + compose.onAllNodes(hasContentDescription("$text ", substring = false) and !hasAnyAncestor(isDialog()))
            .fetchSemanticsNodes()
        check(nodes.size == 1 && nodes.single().config.getOrNull(SemanticsProperties.TestTag) == "rate_wait_line") {
            "$name: ${nodes.size} nodes expose the wait text: ${nodes.map { it.config.getOrNull(SemanticsProperties.TestTag) }}" }
    }

    /** TL condition 3 as amended: while a notice exists, the line is drawn, or hidden with the Details dot showing. */
    private fun lineOrDot(name: String, moment: String, detailsLabel: String) {
        val drawn = lineDrawn()
        check(drawn || detailsState(detailsLabel) != null) { "$name: no wait line and no Details dot $moment" }
    }

    private fun formIs(name: String, expect: RateLineForm, detailsLabel: String) {
        val drawn = when { !lineDrawn() -> HIDDEN; form?.first == true -> SHORT; else -> FULL }
        check(drawn == expect) { "$name: expected $expect, drew $drawn (form=$form)" }
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
        val window = appRoot().fetchSemanticsNode().size
        val detail = "$name: $tag visible=$visible size=${node.size} window=$window ime=$imeBottom"
        check(kotlin.math.abs(visible.width - node.size.width) <= 1f && kotlin.math.abs(visible.height - node.size.height) <= 1f) {
            "Clipped ($detail)" }
        check(visible.left >= 0f && visible.top >= 0f && visible.right <= window.width + 0.5f && visible.bottom <= window.height + 0.5f) {
            "Outside the window ($detail)" }
        if (imeBottom > 0) check(visible.bottom <= window.height - imeBottom + 0.5f) { "Under the keyboard ($detail)" }
    }

    /**
     * The activity's own root (PL): after the keyboard hides, a text-handle popup can add a second, tiny root (21×25 px on
     * run 45211530633), which made the plain root ambiguous for the capture.
     */
    private fun appRoot() = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("conversation_composer") or hasTestTag("dm_composer")))

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(context.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-rate-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = (if (dialog) compose.onNode(isDialog()) else appRoot()).captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
