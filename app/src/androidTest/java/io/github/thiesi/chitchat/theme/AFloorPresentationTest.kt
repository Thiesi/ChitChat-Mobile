package io.github.thiesi.chitchat.theme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.BarTitle
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.BoundedHeader
import io.github.thiesi.chitchat.feature.direct.DirectPrivacyNotice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A-floor fixes F1–F3 (gap-audit.md): synthetic components at 2.0 font scale, no app state. */
class AFloorPresentationTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun content(fontScale: Float = 2f, body: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            ChitChatTheme(dark = false) { body() }
        }
    }
    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    @Test fun wordmarkStaysOneUncutLineAtDoubleFontScale() {
        content { Box(Modifier.width(120.dp)) { BarTitle(R.string.app_name) } }
        val result = layout("bar_wordmark")
        assertEquals(1, result.lineCount)
        assertFalse(result.hasVisualOverflow && result.isLineEllipsized(0))
        compose.onNodeWithTag("bar_wordmark", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertContentDescriptionEquals(text(R.string.app_name))
    }

    @Test fun longBarTitleIsOneLineAndKeepsItsFullTextInSemantics() {
        content { Box(Modifier.width(90.dp)) { BarTitle(R.string.message_search_title) } }
        assertEquals(1, layout("bar_title").lineCount)
        compose.onNodeWithTag("bar_title", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertTextEquals(text(R.string.message_search_title))
    }

    @Test fun overflowingHeaderOffersMoreWithoutMovingTheComposer() {
        content {
            Column(Modifier.size(360.dp, 640.dp)) {
                BoundedHeader(120.dp) { repeat(20) { Text("Header line $it") } }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(48.dp).testTag("composer"))
            }
        }
        val composer = compose.onNodeWithTag("composer").assertIsDisplayed().getUnclippedBoundsInRoot()
        compose.onNodeWithTag("header_more").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assertTextEquals(text(R.string.header_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Header line 0").assertIsNotDisplayed()
        assertEquals(composer, compose.onNodeWithTag("composer").assertIsDisplayed().getUnclippedBoundsInRoot())
        compose.onNodeWithTag("bounded_header").onChildren().filter(hasScrollAction()).assertCountEquals(1)
        repeat(20) {
            val label = compose.onNodeWithTag("header_more").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text)?.joinToString()
            if (label == text(R.string.header_back_to_top)) return@repeat
            compose.onNodeWithTag("header_more").performClick(); compose.waitForIdle()
        }
        compose.onNodeWithTag("header_more").assertTextEquals(text(R.string.header_back_to_top)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Header line 0").assertIsDisplayed()
        assertEquals(composer, compose.onNodeWithTag("composer").getUnclippedBoundsInRoot())
    }

    @Test fun fittingHeaderShowsNeitherFadeControlNorMore() {
        content(fontScale = 1f) { BoundedHeader(300.dp) { Text("One short line") } }
        compose.onNodeWithText("One short line").assertIsDisplayed()
        compose.onNodeWithTag("header_more").assertDoesNotExist()
    }

    @Test fun detailsMarkerSpeaksEachStateAndUncertainWins() {
        val label = text(R.string.dm_details)
        val marker = androidx.compose.runtime.mutableStateOf(false to false)
        compose.setContent { ChitChatTheme(dark = false) {
            DirectPrivacyNotice(null, title = R.string.dm_details, compact = true, needsAttention = true, expanded = false,
                onExpandedChange = {}, uncertain = marker.value.first, failed = marker.value.second)
        } }
        for ((state, expected) in listOf(
            (false to false) to R.string.dm_details_attention,
            (true to false) to R.string.details_attention_uncertain,
            (false to true) to R.string.details_attention_failed,
            (true to true) to R.string.details_attention_uncertain)) {
            compose.runOnIdle { marker.value = state }
            compose.onNodeWithContentDescription(label).assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(expected)))
            val kind = if (state.first) "uncertain" else if (state.second) "failed" else "generic"
            compose.onNodeWithTag("attention_badge:$kind", useUnmergedTree = true).assertExists()
        }
    }

    @Test fun blockedComposerKeepsItsDescriptionAndAddsTheMarkerState() {
        val blocked = text(R.string.conversation_send_unavailable_details)
        val cases = listOf(
            io.github.thiesi.chitchat.core.ui.AttentionKind.GENERIC to blocked,
            io.github.thiesi.chitchat.core.ui.AttentionKind.UNCERTAIN to "$blocked ${text(R.string.details_attention_uncertain)}",
            io.github.thiesi.chitchat.core.ui.AttentionKind.FAILED to "$blocked ${text(R.string.details_attention_failed)}")
        val kind = androidx.compose.runtime.mutableStateOf(cases.first().first)
        compose.setContent { ChitChatTheme(dark = false) {
            Text(io.github.thiesi.chitchat.core.ui.detailsStateDescription(kind.value, R.string.conversation_details_attention,
                R.string.conversation_send_unavailable_details), Modifier.testTag("description"))
        } }
        for ((state, expected) in cases) {
            compose.runOnIdle { kind.value = state }
            compose.onNodeWithTag("description").assertTextEquals(expected)
        }
    }
}

