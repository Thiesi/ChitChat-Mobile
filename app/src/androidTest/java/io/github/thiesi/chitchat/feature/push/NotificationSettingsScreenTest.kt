package io.github.thiesi.chitchat.feature.push

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.push.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The real settings screen and view model against a fake Web Push API; no network or device push. */
class NotificationSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ops = Fake()
    private val model = NotificationSettingsViewModel(ops)
    private val phoneFake = FakePhone()
    private val distributors = FakeDistributors()
    private val channels = mutableListOf<Boolean>()
    private val phone = PushPhoneViewModel(phoneFake, distributors, { channels += it })
    private val store = ViewModelStore().also { it.put("push", model); it.put("phone", phone) }
    @After fun stop() = store.clear()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(direct: Boolean?, dark: Boolean = false) {
        ops.direct = direct
        val signedIn = ops.session.value // Frozen before composition, like the root's rendered session.
        compose.setContent { ChitChatTheme(dark = dark) {
            Column(Modifier.verticalScroll(rememberScrollState())) { NotificationSettingsScreen(model, signedIn, phone) }
        } }
        compose.waitUntil(5_000) { model.state.value.settings != null }
    }

    @Test fun unofferedDmChoiceIsHiddenNotShownAsOff() {
        show(direct = null)
        compose.onNodeWithTag("push_mentions").assertExists()
        compose.onNodeWithTag("push_direct").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.push_status_off)).assertExists()
        compose.onNodeWithTag("push_turn_on").assertExists()
        compose.onNodeWithTag("push_phone_off").assertDoesNotExist()
    }

    @Test fun turnOnExplainsFirstThenGuidesWhenNoDeliveryAppIsInstalled() {
        grantNotifications()
        show(direct = null)
        compose.onNodeWithTag("push_turn_on").performClick()
        compose.onNodeWithTag("push_explain").assertExists()
        compose.onNodeWithText(text(R.string.push_explain_body)).assertExists()
        assertEquals(0, phoneFake.starts)
        compose.onNodeWithTag("push_explain_continue").performClick()
        compose.waitUntil(5_000) { phone.state.value.step == TurnOnStep.NoDistributor }
        compose.onNodeWithTag("push_none").assertExists()
        compose.onNodeWithText(text(R.string.push_close)).performClick()
        compose.onNodeWithText(text(R.string.push_status_no_distributor)).assertExists()
        assertEquals(0, phoneFake.starts)
        assertTrue("No channels without a delivery app", channels.isEmpty())
    }

    @Test fun aRefusalShowsItsCopyAndOffersTurnOnAgainAndTurnOff() {
        phoneFake.status.value = PushPhoneStatus.Refused(PushSubscribeRefusal.CONFLICT)
        show(direct = null)
        compose.onNodeWithText(text(R.string.push_refused_conflict)).assertExists()
        compose.onNodeWithText(text(R.string.push_turn_on_again)).assertExists()
        compose.onNodeWithTag("push_phone_off").performClick()
        compose.onNodeWithTag("push_phone_off_confirm").performClick()
        compose.waitUntil(5_000) { phoneFake.turnOffs == 1 }
        assertEquals("Nothing is retried", 0, phoneFake.starts)
    }

    @Test fun aChoiceIsSentOnceAndShownOnlyAfterTheServerConfirms() {
        show(direct = false)
        compose.onNodeWithTag("push_direct").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)).performClick()
        compose.waitUntil(5_000) { model.state.value.settings?.preferences?.direct == true }
        compose.onNodeWithTag("push_direct").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onNodeWithText(text(R.string.push_direct_from_now)).assertExists()
        assertEquals(1, ops.writes.get())
        assertNull("Quiet hours stay off, sent explicitly", ops.lastWrite?.quietHours)
    }

    @Test fun removeAsksFirstAndRevokesById() {
        show(direct = null)
        compose.onNodeWithTag("push_remove:9").performScrollTo().performClick()
        compose.onNodeWithTag("push_remove_dialog").assertExists()
        compose.onNodeWithTag("push_remove_confirm").performClick()
        compose.waitUntil(5_000) { ops.revoked == listOf(9L) }
        compose.onNodeWithTag("push_device:9").assertDoesNotExist()
    }

    private class Fake : PushOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false),
            webPush = WebPushDiscovery.Available(PushRecord.create("https://vapid.example", 1, 0).publicKey)))
        var direct: Boolean? = null
        val writes = AtomicInteger(); @Volatile var lastWrite: PushPreferences? = null
        @Volatile var revoked = emptyList<Long>()
        private var devices = listOf(PushDevice(9, "Mozilla/5.0 (X11; Linux x86_64) Firefox/128.0", null, false,
            "2026-07-18T09:12:00+00:00", "2026-08-01T21:03:00+00:00"))
        override suspend fun settings(expected: SessionState) = PushSettings(PushPreferences(true, true, direct, null), devices)
        override suspend fun update(expected: SessionState, preferences: PushPreferences): PushPreferences {
            writes.incrementAndGet(); lastWrite = preferences; return preferences }
        override suspend fun revoke(expected: SessionState, deviceId: Long): List<PushDevice> {
            revoked = revoked + deviceId; devices = devices.filterNot { it.id == deviceId }; return devices }
    }

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    /** Card captures for the presentation lead; every scene also asserts its copy. Not a visual pass by itself. */
    private fun presentation(dark: Boolean) {
        distributors.installed = listOf(PushDistributor("org.example.delivery", "Fixture Delivery"))
        grantNotifications()
        show(direct = null, dark = dark)
        val label = if (dark) "dark" else "light"
        val scenes = listOf(
            "off" to (PushPhoneStatus.Off to R.string.push_status_off),
            "waiting" to (PushPhoneStatus.Waiting to R.string.push_status_waiting),
            "needs-setup" to (PushPhoneStatus.NeedsSetup to R.string.push_status_needs_setup),
            "refused" to (PushPhoneStatus.Refused(PushSubscribeRefusal.LIMIT) to R.string.push_refused_limit))
        for ((name, scene) in scenes) {
            phoneFake.status.value = scene.first
            compose.onNodeWithText(text(scene.second)).assertExists()
            capture(compose.onNodeWithTag("push_status"), "push-$label-$name")
        }
        phoneFake.status.value = PushPhoneStatus.On
        compose.waitUntil(5_000) { phone.state.value.distributor != null }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.push_status_on, "Fixture Delivery")).assertExists()
        capture(compose.onNodeWithTag("push_status"), "push-$label-on")
        phoneFake.status.value = PushPhoneStatus.Off
        compose.onNodeWithTag("push_turn_on").performClick()
        capture(compose.onNode(isDialog()), "push-$label-explain")
        compose.onNodeWithText(text(R.string.cancel)).performClick()
    }

    /** Granted up front, so no test shows the system prompt (that prompt is manual acceptance) and captures are stable. */
    private fun grantNotifications() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun capture(node: SemanticsNodeInteraction, name: String) {
        // Dialog window capture needs API28 with the pinned Compose library; earlier levels keep the assertions only.
        if (android.os.Build.VERSION.SDK_INT < 28) return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-push-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private class FakePhone : PushPhone {
        override val status = MutableStateFlow<PushPhoneStatus>(PushPhoneStatus.Off)
        @Volatile var starts = 0
        @Volatile var turnOffs = 0
        override suspend fun start(expected: SessionState): Boolean { starts++; return true }
        override suspend fun turnOff(expected: SessionState): Boolean { turnOffs++; status.value = PushPhoneStatus.Off; return true }
        override suspend fun turnOffLocally() { status.value = PushPhoneStatus.Off }
    }

    private class FakeDistributors : PushDistributors {
        @Volatile var installed = emptyList<PushDistributor>()
        override fun installed() = installed
        override fun current(): String? = installed.firstOrNull()?.packageName
        override fun choose(packageName: String) = Unit
    }
}
