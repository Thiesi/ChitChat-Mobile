package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.Lantern
import kotlinx.coroutines.launch

/** A conversation header bounded to [maxHeight] so the composer always stays visible (A-04).
 * When its content overflows, a bottom fade shows it continues and More scrolls one viewport,
 * becoming Back to top at the end. The column keeps its scroll semantics. When it fits, neither
 * appears. Content, status precedence and the bound itself are the caller's.
 */
@Composable
internal fun BoundedHeader(maxHeight: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val overflow = scroll.maxValue > 0
    // Derived, so scrolling recomposes only when the label flips, not on every pixel.
    val atEnd by remember { derivedStateOf { scroll.value >= scroll.maxValue } }
    val colors = ChitChatTheme.colors
    Box(modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("bounded_header")) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp)
            .padding(bottom = if (overflow) Lantern.target else 0.dp), content = content)
        if (overflow) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.ground))))
            TextButton(onClick = { scope.launch {
                if (atEnd) scroll.animateScrollTo(0)
                else scroll.animateScrollTo((scroll.value + scroll.viewportSize).coerceAtMost(scroll.maxValue))
            } }, modifier = Modifier.align(Alignment.BottomEnd).heightIn(min = Lantern.target).testTag("header_more")) {
                Text(stringResource(if (atEnd) R.string.header_back_to_top else R.string.header_more), color = colors.signatureText)
            }
        }
    }
}
