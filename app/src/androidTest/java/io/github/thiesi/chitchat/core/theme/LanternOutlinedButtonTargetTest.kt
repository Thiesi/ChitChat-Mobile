package io.github.thiesi.chitchat.core.theme

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A-floor: the shared outlined button (about 20 call sites) gives every caller a touch target of at least 48dp each
 * way, whatever its drawn layout height. Reports both, so a layout-versus-touch difference is visible.
 */
class LanternOutlinedButtonTargetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theTouchTargetIsAtLeast48dp() {
        compose.setContent { ChitChatTheme(dark = false) { LanternOutlinedButton(onClick = {}, Modifier.testTag("outlined")) { Text("Go") } } }
        val node = compose.onNodeWithTag("outlined").fetchSemanticsNode()
        val density = node.layoutInfo.density
        val dp = { px: Float -> with(density) { px.toDp().value } }
        val min = with(density) { 48.dp.toPx() } - 0.5f
        val report = "layout=${dp(node.layoutInfo.width.toFloat())}x${dp(node.layoutInfo.height.toFloat())}dp " +
            "touch=${dp(node.touchBoundsInRoot.width)}x${dp(node.touchBoundsInRoot.height)}dp"
        Log.i("CCMTarget", "LanternOutlinedButton $report")
        assertTrue("LanternOutlinedButton touch target must be at least 48dp: $report",
            node.touchBoundsInRoot.width >= min && node.touchBoundsInRoot.height >= min)
    }
}
