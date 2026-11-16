package io.github.thiesi.chitchat.feature.rooms

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.PrimaryButton

/**
 * Find your people (onboarding.md step 5, onboarding-restyle.md §5, P-54): new accounts only. The server's open rooms,
 * each with its own confirmed Join; never pre-checked, never "join all", never replayed. Start chatting is the single
 * exit, pinned below the list (system Back does the same; no icon Back, a deliberate P-76 exception).
 */
@Composable
fun FindPeopleScreen(model: RoomsViewModel, userName: String, installation: String, onStartChatting: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    // Only a genuine app background cancels (M3a); navigating to Rooms keeps an in-flight join, whose result
    // reconciles through the list there. The Activity's lifecycle, not this entry's, tells the two apart.
    val activity = LocalActivity.current
    val owner = androidx.compose.runtime.remember { Any() }
    DisposableEffect(activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.foreground(owner)
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) model.background()
        }
        lifecycle?.addObserver(observer)
        if (lifecycle == null) model.foreground(owner)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val open = state.rooms.filter { it.visibility == "public" }
    Column(Modifier.fillMaxSize().testTag("find_people")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.find_people_heading, userName), Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.find_people_body, installation))
            Text(stringResource(R.string.find_people_notifications), color = ChitChatTheme.colors.textMuted)
            if (state.loaded && open.isEmpty())
                Text(stringResource(R.string.find_people_empty), Modifier.testTag("find_people_empty"))
            else if (!state.loaded && state.error != null) InlineError(roomError(state.error!!))
            open.forEach { room -> FindPeopleRow(room, state, model) }
        }
        PrimaryButton(onClick = onStartChatting, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("find_people_start")) {
            Text(stringResource(R.string.find_people_start))
        }
    }
}

@Composable
private fun FindPeopleRow(room: RoomInfo, state: RoomsUiState, model: RoomsViewModel) {
    val target = state.joinTarget == room.id
    Column(Modifier.fillMaxWidth().testTag("find_people_row_${room.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(room.name, fontWeight = FontWeight.SemiBold)
        if (room.infoLine.isNotBlank()) Text(room.infoLine, color = ChitChatTheme.colors.textMuted)
        when {
            room.isMember -> Text(stringResource(R.string.find_people_joined), // Confirmed by the reconciled list; ink, not green.
                Modifier.heightIn(min = 48.dp).testTag("find_people_joined_${room.id}").semantics { liveRegion = LiveRegionMode.Polite },
                fontWeight = FontWeight.SemiBold)
            target && state.busy -> LanternOutlinedButton(onClick = {}, enabled = false,
                modifier = Modifier.defaultMinSize(minHeight = 52.dp).testTag("find_people_join_${room.id}")) {
                Text(stringResource(R.string.find_people_joining))
            }
            target && (state.mutationUncertain || state.stale) -> {
                // Unconfirmed, or the directory couldn't be re-read: only an explicit list GET (Check room) settles it,
                // and every other Join waits meanwhile; never a resend.
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("find_people_failure_${room.id}")) {
                    if (state.mutationUncertain) {
                        Text(stringResource(R.string.find_people_uncertain))
                        // Why the other Joins are disabled (presentation lead J1; the uncertain case only).
                        Text(stringResource(R.string.find_people_check_first), color = ChitChatTheme.colors.textMuted)
                    } else state.error?.let { InlineError(roomError(it)) }
                }
                LanternOutlinedButton(onClick = model::refresh, enabled = !state.busy,
                    modifier = Modifier.defaultMinSize(minHeight = 52.dp).testTag("find_people_check_${room.id}")) {
                    Text(stringResource(R.string.find_people_check))
                }
            }
            else -> {
                // A definite refusal (already reconciled) shows inline; Join stays a fresh explicit choice.
                if (target) state.joinRefusal?.let { refusal ->
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("find_people_failure_${room.id}")) {
                        InlineError(roomError(refusal))
                    }
                }
                LanternOutlinedButton(onClick = { model.joinListed(room.id) },
                    enabled = !state.busy && !state.stale && !state.mutationUncertain,
                    modifier = Modifier.defaultMinSize(minHeight = 52.dp).testTag("find_people_join_${room.id}")) {
                    Text(stringResource(R.string.find_people_join))
                }
            }
        }
    }
}
