package io.github.thiesi.chitchat.core.ui

import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

/**
 * A report-shaped form dialog (TL): the reporting form isn't on main yet, so this stands in for its shape (a context line,
 * eight 52dp radio rows, a three-line field, the who-sees text, Cancel and Send report) on the shared scaffold.
 */
@Composable
internal fun ReportShapedFormDialog() {
    var details by remember { mutableStateOf("") }
    FormDialog(onDismissRequest = {}, title = { Text("Report this message") }, modifier = Modifier.testTag("fixture_dialog"),
        bodyArrangement = Arrangement.spacedBy(12.dp),
        dismissButton = { TextButton(onClick = {}, modifier = Modifier.testTag("fixture_cancel")) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = {}, modifier = Modifier.testTag("fixture_send")) { Text("Send report") } }) {
        Text("Bob")
        Text("check out my channel, link in bio, cheap followers", maxLines = 1)
        Text("What's wrong?")
        for (index in 1..8) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = false, onClick = null)
            Column(Modifier.padding(start = 12.dp)) { Text("Category $index"); Text("A one-line helper") }
        }
        OutlinedTextField(details, { details = it }, minLines = 3, label = { Text("Anything else moderators should know? (optional)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fixture_details"))
        Text("${details.length} / 1000")
        Text("Lounge's owners and moderators, and the server's moderation team, will see this message's text, its time and your report.")
    }
}

/**
 * A fitting StepUp's shape (title, explanation and password field 16dp apart, Cancel and Verify and continue) as main's
 * M3 AlertDialog or on the scaffold, for the no-change comparison.
 */
@Composable
internal fun CompareDialog(form: Boolean) {
    val title: @Composable () -> Unit = { Text("Confirm it\u2019s you to export your personal data", Modifier.testTag("compare_title")) }
    val dismiss: @Composable () -> Unit = { TextButton(onClick = {}, modifier = Modifier.testTag("compare_cancel")) { Text("Cancel") } }
    val confirm: @Composable () -> Unit = { TextButton(onClick = {}, modifier = Modifier.testTag("compare_confirm")) { Text("Verify and continue") } }
    val text = "Re-enter your current password. The server grants sensitive actions for a short time in this session."
    val body: @Composable () -> Unit = {
        Text(text, Modifier.testTag("compare_body"))
        OutlinedTextField("", {}, Modifier.fillMaxWidth().testTag("compare_field"), label = { Text("Current password") }, singleLine = true)
    }
    if (form) FormDialog(onDismissRequest = {}, title = title, confirmButton = confirm, dismissButton = dismiss,
        modifier = Modifier.testTag("compare_dialog"), bodyArrangement = Arrangement.spacedBy(16.dp)) { body() }
    else AlertDialog(onDismissRequest = {}, title = title, confirmButton = confirm, dismissButton = dismiss,
        modifier = Modifier.testTag("compare_dialog"),
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { body() } })
}

/**
 * The focused window (the dialog's, once its field has focus): its height, its IME inset and whether the IME is shown.
 * The inset is the keyboard's overlap with that window, so a platform dialog moved clear of the keyboard reads 0 while
 * the IME is visible; visibility, not the inset, says the keyboard showed.
 */
internal data class FocusedWindowIme(val height: Int, val imeBottom: Int, val visible: Boolean) {
    val imeTop: Int get() = height - imeBottom
}

internal fun focusedWindowIme(): FocusedWindowIme {
    var result = FocusedWindowIme(0, 0, false)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val view = WindowInspector.getGlobalWindowViews().lastOrNull { it.isAttachedToWindow && it.hasWindowFocus() }
        val insets = view?.let(ViewCompat::getRootWindowInsets)
        if (view != null) result = FocusedWindowIme(view.height, insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0,
            insets?.isVisible(WindowInsetsCompat.Type.ime()) == true)
    }
    return result
}

/**
 * One native tap in the window's left margin, halfway between the window edge and [surface]'s left edge, at its middle
 * height: outside the dialog surface, inside the dialog's own window (landscape leaves that margin at 320dp wide).
 */
internal fun tapOutsideSurface(surface: androidx.compose.ui.test.SemanticsNodeInteraction) {
    val node = surface.fetchSemanticsNode()
    val bounds = node.boundsInWindow
    check(bounds.left >= 48f) { "No margin outside the surface to tap: $bounds" }
    val offset = node.positionOnScreen - node.positionInWindow
    val point = androidx.compose.ui.geometry.Offset(bounds.left / 2f, bounds.center.y) + offset
    io.github.thiesi.chitchat.testing.injectNativeTap(point, android.os.SystemClock::uptimeMillis) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(it, true)
    }
}

/** One scalar line per measured case (TL: numbers for the J record; scalars only, no text). */
internal fun recordFormDialogMetrics(line: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
        ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
    val folder = java.io.File(output, "ccm-form-dialog-presentation")
    check(folder.isDirectory || folder.mkdirs())
    java.io.File(folder, "form-dialog-metrics.txt").appendText(line + "\n")
}
