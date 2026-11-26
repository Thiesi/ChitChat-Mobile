package io.github.thiesi.chitchat.feature.reports

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.account.IgnoreOperations
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.profile.BasicProfile
import io.github.thiesi.chitchat.core.profile.ProfileSnapshot
import io.github.thiesi.chitchat.core.reports.ReportCategory
import io.github.thiesi.chitchat.core.reports.ReportFacts
import io.github.thiesi.chitchat.core.reports.ReportOperations
import io.github.thiesi.chitchat.core.reports.ReportRefusal
import io.github.thiesi.chitchat.core.reports.ReportResult
import io.github.thiesi.chitchat.core.reports.ReportTarget
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.ReceivedDirects
import io.github.thiesi.chitchat.core.ui.ReceivedFiles
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.dm
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.core.ui.ReceivedRooms
import io.github.thiesi.chitchat.feature.account.IgnorePersonViewModel
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadScreen
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.profile.ProfileDialog
import io.github.thiesi.chitchat.feature.profile.ProfileState
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Reporting R3c for J (PL list of 2026-11-22): the person report, from the profile card to the stack, in the real room and
 * DM screens at a real Configuration font scale. Scripted report results; the form opens as the card's tap opens it
 * ([ReportViewModel.requestPerson] with the card's frozen facts). `report-person-{theme}-{scale}-{name}.png`.
 */
class PersonReportPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private class Shot(val name: String, val node: String = "report_dialog",
        /** Which of light-default, light-font200 and dark-default this shot is taken in. */
        val at: Set<String> = ALL,
        val dm: Boolean = false, val roomId: Long? = 42, val picture: Boolean = true, val username: String = "Bob",
        val results: List<ReportResult> = emptyList(), val facts: ReportFacts? = null, val hold: Boolean = false,
        val landscape: Boolean = false,
        /** The profile card alone: the entry (null) or the caption (true). */
        val card: Boolean? = null,
        val act: PersonReportPresentationTest.(Fixture) -> Unit = {})

    class Fixture(val reports: ReportViewModel)

    private val unknown = ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))

    private fun shots(): List<Shot> = listOf(
        Shot("card-entry", node = "profile_dialog", card = false),
        Shot("card-reported", node = "profile_dialog", card = true),
        // PL: at 200% the row and the caption sit below the fold; scrolled to, to see them wrap.
        Shot("card-entry-end", node = "profile_dialog", at = FONT200, card = false, act = {
            compose.onNodeWithTag("profile_report").performScrollTo() }),
        Shot("card-reported-end", node = "profile_dialog", at = FONT200, card = true, act = {
            compose.onNodeWithTag("profile_reported").performScrollTo() }),
        Shot("form-room"),
        Shot("form-room-end", act = { end() }),
        Shot("form-room-landscape", at = FONT200, landscape = true),
        Shot("form-room-landscape-end", at = FONT200, landscape = true, act = { end() }),
        // Filled with the keyboard up: a click focuses the field and brings up the IME, which the dialog window sits above.
        Shot("form-room-keyboard", at = FONT200, act = {
            choose(ReportCategory.OFFENSIVE_USERNAME)
            // NativeInput's contract (TL ruling, #374): the keyboard from one native tap (strict IME proof), then the input.
            compose.onNodeWithTag("report_details").performScrollTo()
            compose.tapNativeInput("report_details", imeBottom = {
                io.github.thiesi.chitchat.core.ui.focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 } })
            compose.onNodeWithTag("report_details").performTextInput("Uses a slur as their name")
        }),
        Shot("form-global", at = DEFAULT, dm = true, roomId = null),
        Shot("form-global-end", at = FONT200, dm = true, roomId = null, act = { end() }),
        Shot("form-nopicture", at = DEFAULT, picture = false),
        Shot("form-nopicture-end", at = FONT200, picture = false, act = { end() }),
        Shot("form-longname", at = FONT200, username = "Bartholomew_the_extraordinarily_long_username_of_the_night_shift"),
        Shot("sending", at = DEFAULT, hold = true, act = { fill(); send() }),
        Shot("rate", at = DEFAULT, results = listOf(ReportResult.RateLimited(900)), act = { fill(); send(); notice(it) }),
        Shot("failed", at = DEFAULT, results = listOf(ReportResult.Invalid), act = { fill(); send(); notice(it) }),
        Shot("snapshot", at = DEFAULT + FONT200, results = listOf(ReportResult.SnapshotUnavailable), act = {
            fill(); send(); await("report_notice"); toNotice() }),
        Shot("picture-gone", at = DEFAULT, results = listOf(ReportResult.PictureGone), act = {
            choose(ReportCategory.OFFENSIVE_PROFILE_PICTURE); send(); notice(it) }),
        Shot("unknown", results = listOf(unknown), act = { fill(); send(); await("report_check"); endAtFont200(it) }),
        Shot("didnotarrive", results = listOf(unknown), facts = ReportFacts(canReport = true, reportedByMe = false), act = {
            fill(); send(); await("report_check")
            compose.onNodeWithTag("report_check").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            await("report_notice"); endAtFont200(it)
        }),
        // PL: "Your report didn't arrive." shown in full (the didnotarrive shots show the form's top or its end).
        Shot("didnotarrive-notice", results = listOf(unknown), facts = ReportFacts(canReport = true, reportedByMe = false), act = {
            fill(); send(); await("report_check")
            compose.onNodeWithTag("report_check").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            await("report_notice"); toNotice()
        }),
        Shot("sheet-room", node = "report_sheet", act = { fill(); send(); await("report_sheet") }),
        Shot("sent-room", node = "report_host", at = LIGHT, act = { fill(); send(); done() }),
        Shot("sheet-dm", node = "report_sheet", dm = true, roomId = null, act = { fill(); send(); await("report_sheet") }),
        Shot("sent-dm", node = "report_host", at = LIGHT, dm = true, roomId = null, act = { fill(); send(); done() }),
        Shot("already-sheet", node = "report_sheet", at = LIGHT, results = listOf(ReportResult.AlreadyReported), act = {
            fill(); send(); await("report_sheet") }),
        Shot("already", node = "report_host", at = LIGHT, results = listOf(ReportResult.AlreadyReported), act = { fill(); send(); done() }),
        Shot("refused", node = "report_host", at = LIGHT, results = listOf(ReportResult.Refused(ReportRefusal.NOT_ALLOWED)), act = {
            fill(); send(); await("report_outcome") }),
        Shot("notfound", node = "report_host", at = LIGHT, results = listOf(ReportResult.Refused(ReportRefusal.NOT_FOUND)), act = {
            fill(); send(); await("report_outcome") }),
        Shot("review", node = "report_host", at = LIGHT, hold = true, act = {
            fill(); send()
            compose.waitUntil(5_000) { it.reports.state.value?.phase == ReportPhase.Sending }
            compose.runOnIdle { it.reports.backgrounded() }; await("report_review")
        }),
    )

    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun await(tag: String) = compose.waitUntil(5_000) { present(tag) }
    private var scale = "default"
    private fun choose(category: ReportCategory) = compose.onNodeWithTag("report_category:${category.wire}").performScrollTo().performClick()
    private fun fill() {
        choose(ReportCategory.OFFENSIVE_USERNAME)
        compose.onNodeWithTag("report_details").performScrollTo().performTextInput("Uses a slur as their name")
    }
    // As R2c: Send through its semantics action, no geometry (captures don't test input).
    private fun send() = compose.onNodeWithTag("report_send").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
    private fun notice(fixture: Fixture) = compose.waitUntil(5_000) { (fixture.reports.state.value?.phase as? ReportPhase.Editing)?.notice != null }
    /** Every scroller around the who-sees line to its true end, as R2c's form-room-end. */
    private fun end() {
        compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasTestTag("report_who"))).fetchSemanticsNodes().forEach { node ->
            compose.onNode(androidx.compose.ui.test.SemanticsMatcher("scroller ${node.id}") { it.id == node.id })
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        }
    }
    private fun endAtFont200(@Suppress("UNUSED_PARAMETER") fixture: Fixture) { if (scale == "font200") end() }
    /**
     * The in-form notice as the person sees it: it brings itself into view when it appears (PL and TL, 2026-11-22), with
     * no keyboard closing and no scroll by the test; at 200% the taller snapshot notice shows from its top.
     */
    private fun toNotice() = compose.waitForIdle()
    /** The sheet's Done: the line stays in the stack. */
    private fun done() { await("report_sheet"); compose.onNodeWithTag("report_sheet_done").performClick(); await("report_outcome")
        compose.waitUntil(5_000) { !present("report_sheet") } }

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val theme = if (dark) "dark" else "light"
        for ((scaleName, fontScale) in listOf("default" to 1f, "font200" to 2f)) for (shot in shots().filter { "$theme-$scaleName" in it.at }) {
            FontScaleTestActivity.requestedFontScale = fontScale
            scale = scaleName
            val session = ReceivedFiles.session().apply { value = value.copy(chatPolicy =
                io.github.thiesi.chitchat.core.conversation.ChatPolicy(emptySet(), null, io.github.thiesi.chitchat.core.conversation.DisplayPreferences())) }
            val rooms = ReceivedRooms(session).apply { messages = listOf(roomMessage(3, "hello")) }
            val room = ConversationViewModel(rooms)
            val directs = ReceivedDirects(session).apply { rows = listOf(dm(3, "hi", null)) }
            val direct = DirectThreadViewModel(directs)
            val gate = CompletableDeferred<Unit>()
            val results = ArrayDeque(shot.results)
            val reports = ReportViewModel(object : ReportOperations {
                override val session = session
                override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?): ReportResult {
                    if (shot.hold) gate.await()
                    return synchronized(results) { results.removeFirstOrNull() } ?: ReportResult.Sent(1)
                }
                override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = shot.facts
            })
            val ignores = IgnorePersonViewModel(object : IgnoreOperations {
                override val session = session
                override suspend fun change(expected: SessionState, userId: Long, ignored: Boolean) = setOf(userId)
            })
            val profile = ProfileSnapshot(BasicProfile(8, shot.username, "2026-01-02T12:00:00Z", null, false, shot.picture,
                if (shot.picture) 3 else null, false), shot.roomId, true, ReportFacts(canReport = true, reportedByMe = shot.card == true))
            try {
                ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                    if (shot.landscape) {
                        scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                        compose.waitUntil(5_000) {
                            var orientation = 0
                            scenario.onActivity { orientation = it.resources.configuration.orientation }
                            orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        }
                    }
                    scenario.onActivity { activity ->
                        activity.setContent { ChitChatTheme(dark = dark) { CompositionLocalProvider(LocalReports provides reports) {
                            Surface(Modifier.fillMaxSize().testTag("report_host"), color = MaterialTheme.colorScheme.background) {
                                if (shot.card != null) ProfileDialog(ProfileState(snapshot = profile), true, {}, {},
                                    onReport = if (shot.card) null else ({}), reported = shot.card)
                                else if (shot.dm) DirectThreadScreen(direct, 8, {}) else ConversationScreen(room, 42, {}, ignores = ignores)
                            }
                        } } }
                    }
                    if (shot.card == null) {
                        compose.waitUntil(5_000) {
                            if (shot.dm) direct.state.value.loaded && !direct.state.value.busy
                            else room.state.value.loaded && !room.state.value.busy && room.state.value.context != null
                        }
                        val origin = if (shot.dm) ReportOrigin.Direct(8) else ReportOrigin.Room(42)
                        compose.runOnIdle { check(reports.requestPerson(ReportTarget.Person(8, shot.roomId), origin, shot.picture,
                            shot.username, reports.settled) == ReportOpen.OPENED) }
                        await("report_dialog")
                    } else await("profile_dialog")
                    shot.act(this, Fixture(reports))
                    capture("report-person-$theme-$scaleName-${shot.name}", shot.node)
                }
            } finally { gate.complete(Unit); compose.runOnIdle { room.cancel(); direct.background(); reports.close() } }
        }
    }

    private fun capture(name: String, node: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-person-report-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag(node).captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private companion object {
        val DEFAULT = setOf("light-default")
        val FONT200 = setOf("light-font200")
        val LIGHT = DEFAULT + FONT200
        val ALL = LIGHT + "dark-default"
    }
}
