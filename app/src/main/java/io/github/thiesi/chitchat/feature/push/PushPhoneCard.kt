package io.github.thiesi.chitchat.feature.push

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.push.DistributorFailure
import io.github.thiesi.chitchat.core.push.PushPhoneStatus
import io.github.thiesi.chitchat.core.push.PushSubscribeRefusal
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge
import io.github.thiesi.chitchat.core.theme.lanternSurfaceBorder
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import io.github.thiesi.chitchat.core.ui.installationName

private const val DISTRIBUTOR_GUIDE = "https://unifiedpush.org/users/distributors/"

/** This phone's status card and the Turn on walk-through. [direct] is the account's confirmed DM alert choice. */
@Composable
internal fun PushPhoneCard(phone: PushPhoneViewModel, session: SessionState, direct: Boolean, enabled: Boolean) {
    val state by phone.state.collectAsStateWithLifecycle()
    val status by phone.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = ChitChatTheme.colors
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    LaunchedEffect(session.generation, session.user?.id) { phone.open(session) }
    // A delivery app installed while away shows up on return; no polling and no package receiver.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { phone.open(session) }
    // The `direct` channel appears when the person turns Direct messages on while this phone receives.
    LaunchedEffect(direct, status) { if (direct) phone.directChosen() }
    var asking by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asking = false
        if (granted) { allowed = true; phone.proceed(session, direct) } else phone.permissionDenied()
    }
    var confirmOff by remember { mutableStateOf(false) }
    val canTurnOn = phone.canTurnOn(session)
    val ready = enabled && !state.busy

    Card(Modifier.fillMaxWidth().testTag("push_status"), shape = MaterialTheme.shapes.large, border = lanternSurfaceBorder()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.push_this_phone), style = MaterialTheme.typography.titleMedium)
            StatusLine(statusText(status, state, session), attention = needsAttention(status, state), tag = "push_status_text")
            if (!allowed && (status != PushPhoneStatus.Off || state.permissionDenied)) {
                StatusLine(stringResource(R.string.push_status_blocked), attention = true, tag = "push_blocked")
                LanternOutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.push_open_settings)) }
            }
            if (state.startFailed) Text(stringResource(R.string.push_start_failed))
            if (state.turnOffUnconfirmed) Text(stringResource(R.string.push_turned_off_local))
            if (!canTurnOn && status == PushPhoneStatus.Off) Text(stringResource(R.string.push_unavailable), color = colors.textMuted)
            val instead = status == PushPhoneStatus.On && state.deliveryAppInstead
            if (instead) Text(stringResource(R.string.push_google_app_installed), color = colors.textMuted,
                modifier = Modifier.testTag("push_google_app_installed"))
            if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
            val showTurnOn = canTurnOn && (instead || status != PushPhoneStatus.On && status != PushPhoneStatus.Waiting)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showTurnOn) PrimaryButton(
                    onClick = { phone.turnOn(session) }, enabled = ready,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("push_turn_on")) {
                    Text(stringResource(if (status == PushPhoneStatus.Off) R.string.push_turn_on else R.string.push_turn_on_again))
                }
                if (status != PushPhoneStatus.Off && status !is PushPhoneStatus.DistributorFailed) TextButton(
                    onClick = { confirmOff = true }, enabled = ready,
                    // Alone, the label lines up with the status text; the 48dp target stays.
                    contentPadding = if (showTurnOn) ButtonDefaults.TextButtonContentPadding
                        else PaddingValues(start = 0.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("push_phone_off")) { Text(stringResource(R.string.push_turn_off)) }
            }
            Text(stringResource(R.string.push_lock_screen), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
    }

    when (val step = state.step) {
        TurnOnStep.Explain -> if (!asking) AlertDialog(onDismissRequest = phone::cancel,
            modifier = Modifier.testTag("push_explain").lanternDialogEdge(),
            title = { Text(stringResource(R.string.push_explain_title)) },
            text = { Text(stringResource(R.string.push_explain_body)) },
            confirmButton = { TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    asking = true
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else phone.proceed(session, direct)
            }, enabled = !state.busy, modifier = Modifier.testTag("push_explain_continue")) { Text(stringResource(R.string.push_continue)) } },
            dismissButton = { TextButton(onClick = phone::cancel) { Text(stringResource(R.string.cancel)) } })
        is TurnOnStep.Choose -> {
            var picked by remember(step) { mutableStateOf(step.distributors.firstOrNull { it.packageName == step.current }) }
            AlertDialog(onDismissRequest = phone::cancel, modifier = Modifier.testTag("push_choose").lanternDialogEdge(),
                title = { Text(stringResource(R.string.push_choose_title)) },
                text = { Column {
                    step.distributors.forEach { distributor ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(picked === distributor, role = Role.RadioButton,
                            onClick = { picked = distributor }).testTag("push_distributor:${distributor.packageName}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = picked === distributor, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(distributor.label)
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = { picked?.let { phone.choose(session, it, direct) } },
                    enabled = picked != null && !state.busy, modifier = Modifier.testTag("push_choose_confirm")) {
                    Text(stringResource(R.string.push_continue)) } },
                dismissButton = { TextButton(onClick = phone::cancel) { Text(stringResource(R.string.cancel)) } })
        }
        // No delivery app, but Play services: Google delivery, only on an explicit "Use Google".
        is TurnOnStep.UseGoogle -> AlertDialog(onDismissRequest = phone::cancel,
            modifier = Modifier.testTag("push_google").lanternDialogEdge(),
            title = { Text(stringResource(R.string.push_none_title)) },
            text = { Column {
                Text(stringResource(R.string.push_google_body))
                TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, DISTRIBUTOR_GUIDE.toUri())) }
                    catch (_: ActivityNotFoundException) { /* No browser: the explanation above still stands. */ }
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("push_google_guide")) { Text(stringResource(R.string.push_google_link)) }
            } },
            confirmButton = { TextButton(onClick = { phone.useGoogle(session, direct) }, enabled = !state.busy,
                modifier = Modifier.testTag("push_use_google")) { Text(stringResource(R.string.push_use_google)) } },
            dismissButton = { TextButton(onClick = phone::cancel) { Text(stringResource(R.string.cancel)) } })
        // Neither a delivery app nor Play services (or a build without the embedded distributor).
        TurnOnStep.NoDistributor -> AlertDialog(onDismissRequest = phone::cancel,
            modifier = Modifier.testTag("push_none").lanternDialogEdge(),
            title = { Text(stringResource(R.string.push_none_title)) },
            text = { Text(stringResource(R.string.push_none_body)) },
            confirmButton = { TextButton(onClick = {
                phone.cancel()
                try { context.startActivity(Intent(Intent.ACTION_VIEW, DISTRIBUTOR_GUIDE.toUri())) }
                catch (_: ActivityNotFoundException) { /* No browser: the explanation above still stands. */ }
            }) { Text(stringResource(R.string.push_none_link)) } },
            dismissButton = { TextButton(onClick = phone::cancel) { Text(stringResource(R.string.push_close)) } })
        TurnOnStep.None -> Unit
    }

    if (confirmOff) AlertDialog(onDismissRequest = { confirmOff = false },
        modifier = Modifier.testTag("push_phone_off_dialog").lanternDialogEdge(),
        title = { Text(stringResource(R.string.push_turn_off_title)) },
        text = { Text(stringResource(R.string.push_remove_body)) },
        confirmButton = { TextButton(onClick = { confirmOff = false; phone.turnOff(session) },
            modifier = Modifier.testTag("push_phone_off_confirm")) { Text(stringResource(R.string.push_turn_off)) } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.cancel)) } })
}

/** States that need the person's attention carry the decorative warning glyph; the words say it already. */
private fun needsAttention(status: PushPhoneStatus, state: PushPhoneState) = when (status) {
    PushPhoneStatus.Off -> state.noDistributor
    PushPhoneStatus.Waiting, PushPhoneStatus.On -> false
    else -> true
}

@Composable
private fun StatusLine(text: String, attention: Boolean, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (attention) Icon(painterResource(R.drawable.ic_warning), contentDescription = null,
            Modifier.size(20.dp).testTag("$tag:warning"), tint = ChitChatTheme.colors.warning)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun statusText(status: PushPhoneStatus, state: PushPhoneState, session: SessionState): String = when (status) {
    PushPhoneStatus.Off -> stringResource(if (state.noDistributor) R.string.push_status_no_distributor else R.string.push_status_off)
    PushPhoneStatus.Waiting -> stringResource(R.string.push_status_waiting)
    PushPhoneStatus.On -> state.distributor?.let { stringResource(R.string.push_status_on, it) } ?: stringResource(R.string.push_status_on_unnamed)
    PushPhoneStatus.NeedsSetup -> stringResource(R.string.push_status_needs_setup)
    PushPhoneStatus.Unconfirmed -> stringResource(R.string.push_status_unconfirmed)
    is PushPhoneStatus.Refused -> when (status.reason) {
        PushSubscribeRefusal.CONFLICT -> stringResource(R.string.push_refused_conflict)
        PushSubscribeRefusal.LIMIT -> stringResource(R.string.push_refused_limit)
        PushSubscribeRefusal.PROVIDER_UNAVAILABLE -> stringResource(R.string.push_refused_provider)
        PushSubscribeRefusal.DESTINATION -> stringResource(R.string.push_refused_destination, installationName(session))
        PushSubscribeRefusal.RATE_LIMITED -> stringResource(R.string.rate_limited)
        PushSubscribeRefusal.OTHER -> stringResource(R.string.push_refused_other)
    }
    is PushPhoneStatus.DistributorFailed -> stringResource(when (status.reason) {
        DistributorFailure.NETWORK -> R.string.push_distributor_network
        DistributorFailure.ACTION_REQUIRED -> R.string.push_distributor_action
        DistributorFailure.OTHER -> R.string.push_distributor_other
    })
}
