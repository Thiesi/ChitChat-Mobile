package io.github.thiesi.chitchat.feature.push

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.push.PushDevice
import io.github.thiesi.chitchat.core.push.QuietHours
import io.github.thiesi.chitchat.core.push.deviceTimeZone
import io.github.thiesi.chitchat.core.push.isTimeZone
import io.github.thiesi.chitchat.core.push.pushDeviceLabel
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge
import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton
import io.github.thiesi.chitchat.core.theme.lanternSurfaceBorder
import io.github.thiesi.chitchat.core.ui.InlineError
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** You → Notifications. The account's push choices and devices; this phone's delivery comes later. */
@Composable
fun NotificationSettingsScreen(model: NotificationSettingsViewModel, session: SessionState) {
    val state by model.state.collectAsStateWithLifecycle()
    val displayedSession = session
    val activity = LocalActivity.current
    LaunchedEffect(session.server, session.generation, session.user?.id, session.user?.sessionVersion) { model.open(displayedSession) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) model.retire() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.open(displayedSession) }
    if (!model.belongsTo(displayedSession)) return
    val colors = ChitChatTheme.colors
    var removing by remember { mutableStateOf<PushDevice?>(null) }
    val settings = state.settings
    val canChoose = model.canChoose() && !session.busy
    Column(Modifier.fillMaxWidth().testTag("notification_settings"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth().testTag("push_status"), shape = MaterialTheme.shapes.large, border = lanternSurfaceBorder()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.push_this_phone), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.push_status_off), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.push_turn_on_later), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        if (state.loading && settings == null) CircularProgressIndicator()
        state.error?.let { failure ->
            InlineError(stringResource(if (settings == null) R.string.push_load_failed else pushWriteError(failure)))
        }
        if (state.unconfirmed) Text(stringResource(R.string.push_unconfirmed), Modifier.testTag("push_unconfirmed"))
        if (settings == null && !state.loading || state.unconfirmed) TextButton(onClick = { model.refresh(displayedSession) },
            enabled = !state.busy && !session.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("push_refresh")) {
            Text(stringResource(if (settings == null) R.string.push_try_again else R.string.push_refresh))
        }
        if (settings != null) {
            val prefs = settings.preferences
            Text(stringResource(R.string.push_what_you_get), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            PushSwitch(stringResource(R.string.push_mentions), null, prefs.mentioned, canChoose, "push_mentions") {
                model.choose(displayedSession, prefs.copy(mentioned = it))
            }
            PushSwitch(stringResource(R.string.push_pings), null, prefs.pinged, canChoose, "push_pings") {
                model.choose(displayedSession, prefs.copy(pinged = it))
            }
            // Shown only when the server returns the Boolean preference; missing or null hides it.
            prefs.direct?.let { direct ->
                PushSwitch(stringResource(R.string.push_direct), stringResource(R.string.push_direct_hint), direct, canChoose, "push_direct") {
                    model.choose(displayedSession, prefs.copy(direct = it))
                }
                if (direct) Text(stringResource(R.string.push_direct_from_now), style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted)
            }
            Text(stringResource(R.string.push_always_on), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)

            Text(stringResource(R.string.push_quiet_hours), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            QuietHoursEditor(prefs.quietHours, canChoose) { model.choose(displayedSession, prefs.copy(quietHours = it)) }
            Text(stringResource(R.string.push_quiet_server), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            Text(stringResource(R.string.push_quiet_dropped), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            Text(stringResource(R.string.push_honesty), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)

            Text(stringResource(R.string.push_devices), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            val devices = settings.devices.sortedByDescending { it.currentSession }
            if (devices.isEmpty()) Text(stringResource(R.string.push_no_devices), color = colors.textMuted)
            devices.forEach { device ->
                Row(Modifier.fillMaxWidth().testTag("push_device:${device.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (device.currentSession) stringResource(R.string.push_this_phone)
                            else pushDeviceLabel(device) ?: stringResource(R.string.push_unknown_device), maxLines = 2)
                        Text(stringResource(R.string.push_device_dates, pushDate(device.createdAt),
                            device.lastUsedAt?.let(::pushDate) ?: stringResource(R.string.push_never_used)),
                            style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    }
                    TextButton(onClick = { if (canChoose) removing = device }, enabled = canChoose,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("push_remove:${device.id}")) {
                        Text(stringResource(if (device.currentSession) R.string.push_turn_off else R.string.push_remove))
                    }
                }
            }
        }
    }
    removing?.let { device ->
        AlertDialog(onDismissRequest = { removing = null }, modifier = Modifier.testTag("push_remove_dialog").lanternDialogEdge(),
            title = { Text(stringResource(if (device.currentSession) R.string.push_turn_off_title else R.string.push_remove_title)) },
            text = { Text(stringResource(R.string.push_remove_body)) },
            confirmButton = { TextButton(onClick = { removing = null; model.remove(displayedSession, device) },
                modifier = Modifier.testTag("push_remove_confirm")) {
                Text(stringResource(if (device.currentSession) R.string.push_turn_off else R.string.push_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun PushSwitch(label: String, hint: String?, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch,
        onValueChange = onChange).testTag(tag), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ChitChatTheme.colors.textMuted) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Whole hours plus an IANA zone; turning it on starts 22–07 in the device's zone. Off clears all three. */
@Composable
private fun QuietHoursEditor(current: QuietHours?, enabled: Boolean, onChange: (QuietHours?) -> Unit) {
    PushSwitch(stringResource(R.string.push_quiet_toggle), current?.let { stringResource(R.string.push_quiet_window,
        "%02d:00".format(it.start), "%02d:00".format(it.end), it.timezone) }, current != null, enabled, "push_quiet") { on ->
        onChange(if (on) QuietHours(22, 7, deviceTimeZone().takeIf(::isTimeZone) ?: "UTC") else null)
    }
    if (current == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HourChoice(stringResource(R.string.push_quiet_start), current.start, enabled, "push_quiet_start") {
            onChange(QuietHours(it, current.end, current.timezone))
        }
        HourChoice(stringResource(R.string.push_quiet_end), current.end, enabled, "push_quiet_end") {
            onChange(QuietHours(current.start, it, current.timezone))
        }
    }
    var zone by remember(current.timezone) { mutableStateOf(current.timezone) }
    val valid = isTimeZone(zone.trim())
    OutlinedTextField(zone, { zone = it }, label = { Text(stringResource(R.string.push_quiet_zone)) }, singleLine = true,
        enabled = enabled, isError = !valid, modifier = Modifier.fillMaxWidth().testTag("push_quiet_zone"),
        supportingText = { if (!valid) Text(stringResource(R.string.push_quiet_zone_invalid)) })
    TextButton(onClick = { onChange(QuietHours(current.start, current.end, zone.trim())) },
        enabled = enabled && valid && zone.trim() != current.timezone, modifier = Modifier.heightIn(min = 48.dp).testTag("push_quiet_zone_save")) {
        Text(stringResource(R.string.push_quiet_zone_save))
    }
}

@Composable
private fun HourChoice(label: String, hour: Int, enabled: Boolean, tag: String, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        LanternOutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
            Text("$label ${"%02d:00".format(hour)}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..23).forEach { h -> DropdownMenuItem(text = { Text("%02d:00".format(h)) }, onClick = { open = false; if (h != hour) onChoose(h) }) }
        }
    }
}

private fun pushDate(value: String): String = runCatching {
    OffsetDateTime.parse(value).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrDefault(value)

private fun pushWriteError(failure: ApiFailure) = when {
    failure.status == 429 -> R.string.rate_limited
    failure.kind == FailureKind.NETWORK || failure.kind == FailureKind.TIMEOUT -> R.string.push_save_network
    failure.code == "validation_error" -> R.string.push_save_invalid
    else -> R.string.push_save_failed
}
