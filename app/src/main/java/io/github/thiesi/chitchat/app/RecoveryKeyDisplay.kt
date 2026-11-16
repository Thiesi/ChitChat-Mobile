package io.github.thiesi.chitchat.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.RecoveryKey
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the display shows once (recovery-key.md). The key lives only in memory; nothing here is saved. */
class RecoveryKeyView(
    val key: RecoveryKey,
    val title: Int,
    /** "Your old key no longer works." after Replace, or null. */
    val note: Int? = null,
    val createdAt: String?,
)

/**
 * The key display (recovery-key.md, mockup `RecoveryKey`; P-184/P-185). Full screen, shown once. While it's up, the
 * window is FLAG_SECURE (out of screenshots, recordings and the recents thumbnail) and excluded from autofill. Copy marks
 * the clip sensitive on API 33+; below 33 a line says the keyboard may keep it. Save to a file is an explicit document
 * choice with the unencrypted warning shown first. Continue and Back both ask "Saved your recovery key?".
 */
@Composable
internal fun RecoveryKeyDisplay(view: RecoveryKeyView, installation: String, host: String, username: String,
    onSaved: () -> Unit, sdkInt: Int = Build.VERSION.SDK_INT) {
    val activity = LocalActivity.current
    val root = LocalView.current
    DisposableEffect(activity, root) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val autofill = root.importantForAutofill
        root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            root.importantForAutofill = autofill
        }
    }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var fileFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileText = listOf(host, username, view.key.value, view.createdAt.orEmpty(),
        stringResource(R.string.recovery_key_file_warning)).filter { it.isNotEmpty() }.joinToString("\n") + "\n"
    val fileName = stringResource(R.string.recovery_key_file_name, host)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // No URI grant is kept and the file is never deleted later, as with exports.
            fileFailed = !runCatching {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(fileText.toByteArray()) }
                }
            }.isSuccess
        }
    }
    BackHandler { confirming = true }
    Surface(Modifier.fillMaxSize().testTag("recovery_key_display"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(view.title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.recovery_key_lead, installation))
                view.note?.let { Text(stringResource(it)) }
                KeyCard(view.key)
                // API 26–32 has no sensitive-clip flag: the keyboard may keep a copy (PL wording).
                KeyActions(clipboardLine = sdkInt < 33,
                    copy = { LanternOutlinedButton(onClick = { copied = copyKey(context, view.key, sdkInt) },
                        modifier = Modifier.testTag("recovery_key_copy")) { Text(stringResource(R.string.recovery_key_copy)) } },
                    save = { LanternOutlinedButton(onClick = { fileFailed = false; save.launch(fileName) },
                        modifier = Modifier.testTag("recovery_key_save_file")) { Text(stringResource(R.string.recovery_key_save_file)) } })
                if (copied) Text(stringResource(R.string.recovery_key_copied), Modifier.testTag("recovery_key_copied")
                    .semantics { liveRegion = LiveRegionMode.Polite })
                if (fileFailed) InlineError(stringResource(R.string.recovery_key_file_failed))
                PrimaryButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth().testTag("recovery_key_continue")) {
                    Text(stringResource(R.string.recovery_key_continue))
                }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.recovery_key_saved_title)) },
        text = { Text(stringResource(R.string.recovery_key_saved_body)) },
        dismissButton = { TextButton(onClick = { confirming = false }, Modifier.testTag("recovery_key_go_back")) {
            Text(stringResource(R.string.recovery_key_go_back))
        } },
        confirmButton = { TextButton(onClick = { confirming = false; onSaved() }, Modifier.testTag("recovery_key_saved")) {
            Text(stringResource(R.string.recovery_key_saved_confirm))
        } })
}

/**
 * Copy and Save to a file side by side when both fit, with the caveat lines under the row in button order; stacked, each
 * line under its own button, when they don't (PL J1 on #288). A measured switch, so the mode follows the real widths.
 */
@Composable
private fun KeyActions(clipboardLine: Boolean, copy: @Composable () -> Unit, save: @Composable () -> Unit) {
    val muted = ChitChatTheme.colors.textMuted
    val style = MaterialTheme.typography.bodySmall
    androidx.compose.ui.layout.Layout(content = {
        Box { copy() }
        Box { save() }
        if (clipboardLine) Text(stringResource(R.string.recovery_key_clipboard_history), Modifier.testTag("recovery_key_clipboard_line"),
            style = style, color = muted)
        Text(stringResource(R.string.recovery_key_file_unencrypted), Modifier.testTag("recovery_key_file_line"), style = style, color = muted)
    }, modifier = Modifier.fillMaxWidth().testTag("recovery_key_actions")) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = 8.dp.roundToPx(); val lineGap = 4.dp.roundToPx()
        val copyButton = measurables[0].measure(loose); val saveButton = measurables[1].measure(loose)
        val clip = if (clipboardLine) measurables[2].measure(loose) else null
        val file = measurables.last().measure(loose)
        val row = copyButton.width + gap + saveButton.width <= width
        val height = if (row) maxOf(copyButton.height, saveButton.height) + listOfNotNull(clip, file).sumOf { lineGap + it.height }
            else copyButton.height + (clip?.let { lineGap + it.height } ?: 0) + gap + saveButton.height + lineGap + file.height
        layout(width, height) {
            var y = 0
            if (row) {
                copyButton.placeRelative(0, 0); saveButton.placeRelative(copyButton.width + gap, 0)
                y = maxOf(copyButton.height, saveButton.height)
                clip?.let { y += lineGap; it.placeRelative(0, y); y += it.height }
                y += lineGap; file.placeRelative(0, y)
            } else {
                copyButton.placeRelative(0, 0); y = copyButton.height
                clip?.let { y += lineGap; it.placeRelative(0, y); y += it.height }
                y += gap; saveButton.placeRelative(0, y); y += saveButton.height
                y += lineGap; file.placeRelative(0, y)
            }
        }
    }
}

/** Six groups in the system monospace face, two rows of three that wrap at large text; TalkBack reads group by group. */
@Composable
private fun KeyCard(key: RecoveryKey) {
    val groups = key.value.split('-')
    Surface(color = ChitChatTheme.colors.raised, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("recovery_key_card")) {
        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3) {
            groups.forEachIndexed { index, group ->
                val spoken = stringResource(R.string.recovery_key_group, index + 1, group.toList().joinToString(", "))
                Text(group, Modifier.testTag("recovery_key_group_${index + 1}").clearAndSetSemantics { contentDescription = spoken },
                    fontFamily = FontFamily.Monospace, fontSize = 22.sp, color = ChitChatTheme.colors.text)
            }
        }
    }
}

/** Puts the key in the clipboard; sensitive on API 33+ (no preview, kept out of keyboard history). Never cleared later. */
internal fun copyKey(context: android.content.Context, key: RecoveryKey, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
    val clip = ClipData.newPlainText(context.getString(R.string.app_name), key.value)
    // ClipDescription.EXTRA_IS_SENSITIVE's value (API 33); the literal keeps API 26–32 builds free of an inlined-API use.
    if (sdkInt >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    return try { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) != null } catch (_: SecurityException) { false }
}
