package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R

/**
 * Send when back online: the line above the composer while a draft waits, with Cancel (48dp).
 * P-108: Cancel stays beside the text only while the text fits in two lines at the width left beside it;
 * otherwise the text takes the full width and Cancel gets its own end-aligned row below. The choice is
 * the measured line count at the current width and font, never a font-scale threshold. Reading order is
 * always notice, then Cancel.
 */
@Composable
internal fun WaitingToSendNotice(text: String, onCancel: () -> Unit) {
    val style = MaterialTheme.typography.bodyMedium
    val buttonStyle = MaterialTheme.typography.labelLarge
    val label = stringResource(R.string.cancel)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("waiting_notice")) {
        // A Material text button is its label plus 12dp padding each side, at least 58dp wide.
        val cancelWidth = with(density) {
            maxOf(58.dp.roundToPx(), measurer.measure(label, buttonStyle).size.width + 24.dp.roundToPx())
        }
        val inlineWidth = (constraints.maxWidth - cancelWidth).coerceAtLeast(0)
        val inline = inlineWidth > 0 && measurer.measure(text, style, constraints = Constraints(maxWidth = inlineWidth)).lineCount <= 2
        val cancel = @Composable {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("waiting_cancel")) { Text(label) }
        }
        if (inline) Row(Modifier.fillMaxWidth().testTag("waiting_inline"), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = style)
            cancel()
        } else Column(Modifier.fillMaxWidth().testTag("waiting_stacked")) {
            Text(text, Modifier.fillMaxWidth(), style = style)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { cancel() }
        }
    }
}
