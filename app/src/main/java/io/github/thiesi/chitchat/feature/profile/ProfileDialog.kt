package io.github.thiesi.chitchat.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.serverInstant
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ProfileDialog(state: ProfileState, canRefresh: Boolean, onRefresh: () -> Unit, onClose: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(onDismissRequest = onClose, modifier = Modifier.testTag("profile_dialog"),
        title = { Text(stringResource(R.string.profile_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) { CircularProgressIndicator(); Text(stringResource(R.string.profile_loading)) }
                state.snapshot?.profile?.let { person ->
                    Text(profileInitial(person.username), style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.testTag("profile_initial"))
                    Text(person.username, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("profile_username"))
                    person.badge?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.testTag("profile_badge")) }
                    val date = serverInstant(person.memberSince)?.atZone(ZoneId.systemDefault())?.let {
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(it)
                    }
                    if (date != null) Text(stringResource(if (person.guest) R.string.profile_visit_since else R.string.profile_member_since, date))
                    // Metadata is no permission or image URL. Do not describe an unloaded avatar as absent.
                    Text(stringResource(R.string.profile_read_only))
                }
                state.error?.let { Text(profileError(it), color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(onClick = onRefresh, enabled = canRefresh && !state.loading,
                modifier = Modifier.heightIn(min = 48.dp).testTag("profile_refresh")) { Text(stringResource(R.string.profile_refresh)) }
        }, dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("profile_close")) { Text(stringResource(R.string.dm_close)) }
        })
}

/** Preserve supplementary Unicode characters rather than slicing a UTF-16 surrogate. */
internal fun profileInitial(username: String): String = username.trim().takeIf { it.isNotEmpty() }
    ?.let { String(Character.toChars(it.codePointAt(0))) } ?: "?"

@Composable
private fun profileError(failure: ApiFailure) = stringResource(when {
    failure.status in setOf(403,404) -> R.string.profile_unavailable
    failure.kind in setOf(FailureKind.NETWORK,FailureKind.TIMEOUT) -> R.string.profile_network
    failure.kind == FailureKind.TLS -> R.string.tls_error
    failure.kind == FailureKind.REDIRECT -> R.string.redirect_error
    failure.kind in setOf(FailureKind.INCOMPATIBLE,FailureKind.TOO_LARGE) -> R.string.incompatible_server
    failure.kind == FailureKind.EXPIRED || failure.status == 401 -> R.string.session_expired
    failure.kind == FailureKind.STORAGE || failure.code == "csrf_failed" -> R.string.session_refresh_needed
    failure.status == 429 -> R.string.rate_limited
    failure.status == 503 -> R.string.server_unavailable
    else -> R.string.request_rejected
})
