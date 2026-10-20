package io.github.thiesi.chitchat.feature.realtime

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.typing.TypingController
import io.github.thiesi.chitchat.core.typing.TypingTarget
import io.github.thiesi.chitchat.core.typing.typingAccount

@Composable
internal fun TypingStatus(controller: TypingController?, session: SessionState, target: TypingTarget,
    viewing: Boolean, publishing: Boolean) {
    if (controller == null) return
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    val account = session.typingAccount()
    val visible = viewing && account != null && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && focused &&
        session.shareTyping == true && (target is TypingTarget.Direct || session.chatPolicy != null) &&
        !session.refreshRequired && !session.storageWarning
    LaunchedEffect(controller, session.server, session.generation, session.user?.id, session.user?.sessionVersion,
        target, visible, publishing) { controller.select(session, target, visible, visible && publishing) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { controller.release(session, target) }
    DisposableEffect(controller, session.server, session.generation, session.user?.id, session.user?.sessionVersion, target) {
        onDispose { controller.release(session, target) }
    }
    val people = state.people.takeIf { visible && state.target == target && state.account == account }
        .orEmpty().filterNot { target is TypingTarget.Room && it.id in session.chatPolicy?.ignoredUserIds.orEmpty() }
    if (people.isNotEmpty()) {
        val label = when (people.size) {
            1 -> stringResource(R.string.typing_one, people[0].username)
            2 -> stringResource(R.string.typing_two, people[0].username, people[1].username)
            else -> stringResource(R.string.typing_several)
        }
        Text(label, Modifier.padding(horizontal = 12.dp).testTag("typing_status")
            .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
