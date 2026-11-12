package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** F2/P-216: an overflowing header's More is its own band; no header control draws under it. */
class BoundedHeaderLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun header(fontScale: Float, controls: Int) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            ChitChatTheme(dark = false) {
                Column(Modifier.width(320.dp)) {
                    BoundedHeader(160.dp) {
                        Column(Modifier.testTag("content")) {
                            Text("Room 43")
                            repeat(controls) { i -> TextButton(onClick = {}, modifier = Modifier.testTag("control:$i")) { Text("Saved room drafts ($i)") } }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("below"))
                }
            }
        }
    }

    private fun noControlUnderTheBand(controls: Int) {
        val band = compose.onNodeWithTag("header_more").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("bounded_header").fetchSemanticsNode().boundsInRoot
        assertEquals("More is the header's bottom band", header.bottom, band.bottom, 1f)
        for (i in 0 until controls) {
            // boundsInRoot is clipped to the scrolling viewport, so this is what actually draws.
            val control = compose.onNodeWithTag("control:$i").fetchSemanticsNode().boundsInRoot
            if (control.height > 0f) assertTrue("control:$i bottom ${control.bottom} above band ${band.top}", control.bottom <= band.top + 0.5f)
        }
    }

    @Test fun overflowingHeaderKeepsControlsOffTheMoreBandAtDefault() { header(1f, 6); noControlUnderTheBand(6) }

    @Test fun overflowingHeaderKeepsControlsOffTheMoreBandAt200() { header(2f, 6); noControlUnderTheBand(6) }

    @Test fun fittingHeaderReservesNothing() {
        header(1f, 1)
        compose.onNodeWithTag("header_more").assertDoesNotExist()
        val header = compose.onNodeWithTag("bounded_header").fetchSemanticsNode().boundsInRoot
        // The content's own bottom, not a button's 40dp visual bounds inside its 48dp touch target.
        val content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals("No band or reserve below fitting content", content.bottom, header.bottom, 1f)
    }
}
