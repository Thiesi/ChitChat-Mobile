package io.github.thiesi.chitchat.core.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.mutations.AuthorChangeKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.RateBudget
import io.github.thiesi.chitchat.core.session.RateLimitNotice
import io.github.thiesi.chitchat.core.session.RateWait
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * #323b2 (PL list 5–8): the shared message-mutation wait in the edit dialog (on its refusal line), the delete dialog and
 * the reaction picker (above the emoji rows), and in an edit dialog opened during a wait a reaction started (the known
 * wait shows at opening; opening sends nothing). Captures `rate-mutation-*` for the PL's J, taken before asserts. The
 * ViewModel and gate behaviour (definite, nothing unknown, no request during the wait, the budgets gating each other)
 * is covered by the JVM tests; this is presentation only. No network.
 */
class RateMutationPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_000_000L

    /** A known wait of five minutes on room_message_mutation, on the injected monotonic clock. */
    private val session = SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false),
        rateWaits = mapOf(RateBudget.ROOM_MESSAGE_MUTATION to RateWait(now + 300_000, 1, 7)))
    private val full get() = context.getString(R.string.rate_wait_mutations,
        context.resources.getQuantityString(R.plurals.wait_about_minutes, 5, 5))

    private fun show(fontScale: Float, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        FontScaleTestActivity.requestedFontScale = fontScale
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                activity.setContent { CompositionLocalProvider(LocalMonotonicClock provides { now }) {
                    ChitChatTheme(dark = dark) { content() }
                } }
            }
            compose.waitForIdle()
            block?.invoke()
        }
    }
    private var block: (() -> Unit)? = null

    private val scales = listOf("default" to 1f, "font200" to 2f)

    @androidx.compose.runtime.Composable
    private fun Line(notice: RateLimitNotice?, tag: String) = RateWaitDialogLine(session,
        mutationNotice(session, notice, RateBudget.ROOM_MESSAGE_MUTATION), R.string.rate_wait_mutations,
        R.string.rate_wait_mutations_unknown, tag)

    private fun edit(kind: AuthorChangeKind, notice: RateLimitNotice?) =
        @androidx.compose.runtime.Composable {
            MessageAuthorChangeDialog(AuthorDialogPresentation(kind, "Proposed private edit", "Original private body", null,
                checked = false, pendingMessageId = null), targetCurrent = true, available = true, busy = false,
                onChange = {}, onConfirm = {}, onDismiss = {}, rateLine = { Line(notice, "author_rate_line") })
        }

    /** 5: the edit dialog after a refused Save; 7: an edit dialog opened during a wait a reaction started (no notice yet). */
    @Test fun editDialogs() {
        for (dark in listOf(false, true)) for ((name, scale) in scales) {
            val label = if (dark) "dark" else "light"
            for ((scene, notice) in listOf("edit" to RateLimitNotice(RateBudget.ROOM_MESSAGE_MUTATION, true), "edit-opened" to null)) {
                block = {
                    capture("rate-mutation-$scene-$label-$name")
                    assertLine("author_rate_line")
                }
                show(scale, dark, edit(AuthorChangeKind.EDIT, notice))
            }
        }
    }

    /** 6: the delete dialog with the line (default light). */
    @Test fun deleteDialog() {
        block = { capture("rate-mutation-delete-light-default"); assertLine("author_rate_line") }
        show(1f, false, edit(AuthorChangeKind.DELETE, RateLimitNotice(RateBudget.ROOM_MESSAGE_MUTATION, true)))
    }

    /** 8: the reaction picker with the line above the emoji rows. */
    @Test fun reactionPicker() {
        for (dark in listOf(false, true)) for ((name, scale) in scales) {
            val label = if (dark) "dark" else "light"
            block = { capture("rate-mutation-reactions-$label-$name"); assertLine("reaction_rate_line") }
            show(scale, dark) {
                MessageReactionPicker(Any(), 101, emptyList(), deleted = false, viewer = 7, busy = false, checked = false,
                    changed = false, unknownMessageId = null, dialogTag = "reactions_dialog", choicesTag = "reaction_choices",
                    canCheck = false, canReview = false, error = null, canChoose = { _, _ -> true }, onChoose = { _, _ -> },
                    onCheck = {}, onReview = {}, onDismiss = {},
                    rateLine = { Line(RateLimitNotice(RateBudget.ROOM_MESSAGE_MUTATION, true), "reaction_rate_line") })
            }
        }
    }

    /** The persistent carrier (PL): the full line as its description, polite live region, and the visible text. */
    private fun assertLine(tag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(full, node.config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(context.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-rate-mutation-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        try { java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
