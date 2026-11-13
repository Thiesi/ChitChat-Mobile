package io.github.thiesi.chitchat.feature.status

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.realtime.BroadcastNotice
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.realtime.RealtimeState
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.feature.realtime.AnnouncementCard
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The real slot over fixture session and realtime state; time is the test's virtual clock. */
class StatusSlotTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private val signedIn = SessionState(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
        phase = SessionPhase.AUTHENTICATED, user = SessionUser(7, "Viewer", listOf("member"), 1, false),
        installationName = "The Common Room")

    private var session by mutableStateOf(signedIn)
    private var realtime by mutableStateOf(RealtimeState(phase = RealtimePhase.LIVE, generation = 1))
    private var now = 0L
    private var retries = 0; private var refreshes = 0; private var storage = 0

    private fun show(dark: Boolean = false, fontScale: Float = 1f) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            ChitChatTheme(dark = dark) { Surface { Column(Modifier.width(360.dp)) {
                StatusSlot(session, realtime, { retries++ }, { refreshes++ }, { storage++ }, nowMillis = { now })
                AnnouncementCard(realtime, { id -> realtime = realtime.copy(broadcasts = realtime.broadcasts.filter { it.id != id }) })
            } } }
        }
    }

    @Test fun liveAloneShowsNothingAndEachRowHasItsCopyAndAction() {
        show()
        compose.onNodeWithTag("status_slot").assertDoesNotExist()
        realtime = realtime.copy(phase = RealtimePhase.OFFLINE)
        compose.onNodeWithText(text(R.string.status_offline_title), substring = true).assertExists()
        compose.onNodeWithTag("status_action").assertDoesNotExist()
        realtime = realtime.copy(phase = RealtimePhase.ERROR, error = ApiFailure(FailureKind.TLS))
        compose.onNodeWithText(text(R.string.status_tls_title, "The Common Room"), substring = true).assertExists()
        compose.onNodeWithTag("status_action").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("Retry is the existing retry", 1, retries)
        realtime = realtime.copy(error = ApiFailure(FailureKind.INCOMPATIBLE))
        compose.onNodeWithText(text(R.string.status_incompatible_title, "The Common Room"), substring = true).assertExists()
        realtime = realtime.copy(error = ApiFailure(FailureKind.NETWORK))
        compose.onNodeWithText(text(R.string.status_stopped_title), substring = true).assertExists()
        realtime = realtime.copy(phase = RealtimePhase.LIVE, error = null)
        session = signedIn.copy(installationName = null)
        realtime = realtime.copy(phase = RealtimePhase.ERROR, error = ApiFailure(FailureKind.TLS))
        compose.onNodeWithText(text(R.string.status_tls_title, "chat.example.test"), substring = true).assertExists()
    }

    @Test fun refreshKeepsItsGateAndWaitsShowOnlyTheServersNumber() {
        show()
        now = 0
        session = signedIn.copy(refreshRequired = true, retryAtMillis = 12_000, error = ApiFailure(FailureKind.HTTP, 429, "rate_limited", retryAfterSeconds = 12))
        compose.onNodeWithText(text(R.string.status_refresh_title), substring = true).assertExists()
        compose.onNodeWithTag("status_action").assertIsNotEnabled()
        session = session.copy(refreshRequired = false)
        compose.onNodeWithText(text(R.string.status_rate_limited, text(R.string.wait_seconds, 12))).assertExists()
        session = session.copy(error = ApiFailure(FailureKind.HTTP, 503, "unavailable"))
        compose.onNodeWithText(text(R.string.status_rate_limited_unknown)).assertExists()
        session = signedIn.copy(storageWarning = true)
        compose.onNodeWithTag("status_action").performClick()
        assertEquals(1, storage)
    }

    @Test fun reconnectingShowsAfterFiveSecondsAndBackOnlineDismissesItself() {
        compose.mainClock.autoAdvance = false
        show()
        realtime = realtime.copy(phase = RealtimePhase.RECONNECTING)
        compose.mainClock.advanceTimeBy(4_900)
        compose.onNodeWithTag("status_slot").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText(text(R.string.status_unreachable_title, "The Common Room"), substring = true).assertExists()
        realtime = realtime.copy(phase = RealtimePhase.LIVE)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(text(R.string.status_back_online), substring = true).assertExists()
        compose.mainClock.advanceTimeBy(2_100)
        compose.onNodeWithTag("status_slot").assertDoesNotExist()
        realtime = realtime.copy(phase = RealtimePhase.RECONNECTING)
        compose.mainClock.advanceTimeBy(1_000)
        realtime = realtime.copy(phase = RealtimePhase.LIVE)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(text(R.string.status_back_online), substring = true).assertDoesNotExist()
    }

    @Test fun anAnnouncementIsACardReadAndDismissedSeparatelyFromStatus() {
        realtime = realtime.copy(broadcasts = listOf(BroadcastNotice(17, null, "Private fixture announcement", Instant.EPOCH),
            BroadcastNotice(18, 43, "Other room", Instant.EPOCH)))
        show()
        compose.onNodeWithTag("status_slot").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.realtime_global_notice)).assertExists()
        compose.onNodeWithText("Other room").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.realtime_view_notice)).performClick()
        compose.onNode(hasText("Private fixture announcement") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText(text(R.string.realtime_dismiss_notice)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithTag("announcement_card").assertDoesNotExist()
    }

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    /** Every block and caption row at the default and 2.0 font sizes, plus the slot inside a Details dialog. */
    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        var scale by mutableStateOf(1f)
        var details by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                ChitChatTheme(dark = dark) { Surface { Column(Modifier.width(360.dp)) {
                    if (details) AlertDialog(onDismissRequest = {}, title = { Text(text(R.string.conversation_details)) },
                        text = { Column { StatusSlot(session, realtime, {}, {}, {}, nowMillis = { now }) } },
                        confirmButton = { TextButton(onClick = {}) { Text(text(R.string.rooms_close)) } })
                    else StatusSlot(session, realtime, {}, {}, {}, nowMillis = { now })
                } } }
            }
        }
        val rate = ApiFailure(FailureKind.HTTP, 429, "rate_limited", retryAfterSeconds = 150)
        val scenes: List<Pair<String, () -> Unit>> = listOf(
            "storage" to { session = signedIn.copy(storageWarning = true); realtime = RealtimeState(RealtimePhase.LIVE, 1) },
            "offline" to { session = signedIn; realtime = RealtimeState(RealtimePhase.OFFLINE, 1) },
            "tls" to { realtime = RealtimeState(RealtimePhase.ERROR, 1, error = ApiFailure(FailureKind.TLS)) },
            "incompatible" to { realtime = RealtimeState(RealtimePhase.ERROR, 1, error = ApiFailure(FailureKind.INCOMPATIBLE)) },
            "stopped" to { realtime = RealtimeState(RealtimePhase.ERROR, 1, error = ApiFailure(FailureKind.NETWORK)) },
            "refresh" to { realtime = RealtimeState(RealtimePhase.LIVE, 1); session = signedIn.copy(refreshRequired = true) },
            "rate" to { now = 0; session = signedIn.copy(retryAtMillis = 150_000, error = rate) },
            "rate-unknown" to { session = signedIn.copy(retryAtMillis = 30_000, error = ApiFailure(FailureKind.HTTP, 503, "unavailable")) },
        )
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            scale = fontScale
            for ((scene, apply) in scenes) {
                apply(); compose.waitForIdle()
                compose.onNodeWithTag("status_slot").assertExists()
                capture("status-$label-$name-$scene")
            }
        }
        scale = 1f; session = signedIn; realtime = RealtimeState(RealtimePhase.OFFLINE, 1); details = true
        compose.waitForIdle()
        compose.onNode(hasTestTag("status_slot") and hasAnyAncestor(isDialog())).assertExists()
        capture("status-$label-default-details", dialog = true)
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-status-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
