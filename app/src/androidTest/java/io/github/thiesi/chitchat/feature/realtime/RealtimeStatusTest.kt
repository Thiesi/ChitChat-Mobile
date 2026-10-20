package io.github.thiesi.chitchat.feature.realtime

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.BroadcastNotice
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.realtime.RealtimeState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealtimeStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectionDoesNotPretendChatIsAutomaticallyCurrentAndOfflineIsExplicit() {
        val state = mutableStateOf(RealtimeState(phase = RealtimePhase.CONNECTING))
        compose.setContent { MaterialTheme { RealtimeStatus(state.value, {}, {}) } }
        compose.onNodeWithText("Connecting session events. Room chat uses Refresh.").assertExists()
        compose.runOnIdle { state.value = state.value.copy(phase = RealtimePhase.LIVE) }
        compose.onNodeWithText("Session events connected. Room chat uses Refresh.").assertExists()
        compose.runOnIdle { state.value = state.value.copy(phase = RealtimePhase.OFFLINE) }
        compose.onNodeWithText("Offline. Loaded room chat may be out of date.").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test fun tlsFailureHasNoTrustBypassAndRetryIsDeliberate() {
        var retries = 0
        compose.setContent { MaterialTheme { RealtimeStatus(RealtimeState(phase = RealtimePhase.ERROR,
            error = ApiFailure(FailureKind.TLS)), { retries++ }, {}) } }
        compose.onNodeWithText("Secure event connection failed. Check the server certificate; it was not bypassed.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun announcementCanBeReadAndDismissedWithoutBecomingAChatRow() {
        val state = mutableStateOf(RealtimeState(phase = RealtimePhase.LIVE, generation = 3,
            broadcasts = listOf(BroadcastNotice(17, null, "Private fixture announcement", Instant.EPOCH))))
        var dismissed = 0L
        compose.setContent { MaterialTheme { RealtimeStatus(state.value, {}, { id ->
            dismissed = id; state.value = state.value.copy(broadcasts = emptyList())
        }) } }
        compose.onNodeWithText("Server announcement").assertExists()
        compose.onNodeWithText("Read announcement").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText("Private fixture announcement") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("Dismiss announcement") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(17L, dismissed) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Private fixture announcement").assertDoesNotExist()
    }

    @Test fun otherRoomAnnouncementsAreNotDisplayedAndGenerationClearingClosesDialog() {
        val state = mutableStateOf(RealtimeState(phase = RealtimePhase.LIVE, generation = 3, selectedRoomId = 42,
            broadcasts = listOf(BroadcastNotice(17, 43, "Other room", Instant.EPOCH),
                BroadcastNotice(18, null, "Current notice", Instant.EPOCH))))
        compose.setContent { MaterialTheme { RealtimeStatus(state.value, {}, {}) } }
        compose.onNodeWithText("Other room").assertDoesNotExist()
        compose.onNodeWithText("Read announcement").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle { state.value = RealtimeState(generation = 4) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Current notice").assertDoesNotExist()
    }
}
