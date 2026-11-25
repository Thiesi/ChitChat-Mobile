package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.core.theme.Lantern

/**
 * One input component through IME resize: no focus loss from swapping compact/expanded fields.
 *
 * #331b (TL, PL): the visible label child stops being visible once the field holds text, so a filled field carries the
 * label as its description, which an accessibility service finds as a visible child of the field. Only while filled:
 * while empty the label child is visible, and a description too would name the field twice. Exactly the label string,
 * never the draft, a count or state words.
 */
@Composable
internal fun MessageInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean,
    label: String, compact: Boolean, modifier: Modifier = Modifier, readOnly: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(value, onValueChange, modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) {
            if (value.isNotEmpty()) contentDescription = label },
        enabled = enabled, readOnly = readOnly, maxLines = if (compact) 1 else 4, interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(value, inner, enabled, false, VisualTransformation.None,
                interaction, label = { Text(label) }, contentPadding = PaddingValues(8.dp),
                container = { OutlinedTextFieldDefaults.Container(enabled, false, interaction, shape = Lantern.field) })
        })
}

/** Same native field with explicit selection/composition for conversation completion. */
@Composable
internal fun MessageInput(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, enabled: Boolean,
    label: String, compact: Boolean, modifier: Modifier = Modifier, readOnly: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(value, onValueChange, modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) {
            if (value.text.isNotEmpty()) contentDescription = label },
        enabled = enabled, readOnly = readOnly, maxLines = if (compact) 1 else 4, interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(value.text, inner, enabled, false, VisualTransformation.None,
                interaction, label = { Text(label) }, contentPadding = PaddingValues(8.dp),
                container = { OutlinedTextFieldDefaults.Container(enabled, false, interaction, shape = Lantern.field) })
        })
}
