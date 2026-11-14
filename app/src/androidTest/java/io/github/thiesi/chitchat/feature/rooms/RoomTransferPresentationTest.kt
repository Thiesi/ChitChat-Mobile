package io.github.thiesi.chitchat.feature.rooms

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.rooms.RoomTransferMember
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Synthetic P-217 states for the presentation lead's J review: no network, real composables. The font scale is a
 * real Configuration on the host activity, so the dialog windows inherit it exactly as they do a person's setting.
 */
class RoomTransferPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private val members = listOf(RoomTransferMember(8, "Fixture moderator", "moderator"),
        RoomTransferMember(9, "Another fixture", "member"), RoomTransferMember(10, "Third fixture", "member"))
    private val base = RoomTransferUi(42, "Lounge", ownerActing = true, load = TransferLoad.LOADED,
        owner = RoomTransferMember(7, "Viewer", "owner"), candidates = members)
    private val scenes = listOf("row", "none", "failed", "picker", "confirm", "success")
    // Each scene's key text; it must be strictly taller at 2.0 than at default (the scale reached the window).
    private val probe = mapOf("row" to "rooms_owner_explanation", "none" to "rooms_transfer_none", "failed" to "rooms_owner_explanation",
        "picker" to "rooms_transfer_title", "confirm" to "rooms_transfer_confirm_title", "success" to "rooms_transfer_success")

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        val heights = mutableMapOf<String, Float>()
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                var scene by mutableStateOf(scenes.first())
                scenario.onActivity { activity ->
                    assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                    // Only the font scale may change: a disturbed density would silently shrink every dp measurement.
                    assertEquals("The platform density is untouched",
                        InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.densityDpi,
                        activity.resources.configuration.densityDpi)
                    activity.setContent { ChitChatTheme(dark = dark) { Scene(scene) } }
                }
                for (each in scenes) {
                    scene = each; compose.waitForIdle()
                    when (each) {
                        "row" -> compose.onNodeWithTag("rooms_transfer_open").assertHeightIsAtLeast(48.dp)
                        "failed" -> compose.onNodeWithTag("rooms_transfer_retry").assertHeightIsAtLeast(48.dp)
                        "picker" -> compose.onNodeWithTag("rooms_transfer_picker").assertExists()
                        "confirm" -> compose.onNodeWithTag("rooms_transfer_make_owner").assertExists()
                    }
                    val height = compose.onNodeWithTag(probe.getValue(each)).getUnclippedBoundsInRoot().height.value
                    if (fontScale == 1f) heights[each] = height
                    else assertTrue("$each: the 2.0 font scale reached the captured window", height > heights.getValue(each))
                    capture("transfer-$label-$name-$each")
                }
            }
        }
    }

    @Composable
    private fun Scene(scene: String) {
        when (scene) {
            "picker" -> RoomTransferPicker(base.copy(pickerOpen = true), enabled = true, onFilter = {}, onChoose = {},
                onClose = {}, onCheckRoom = {}) { "" }
            "confirm" -> RoomTransferConfirm(base.copy(pickerOpen = true, chosen = members[0], confirming = true), {}, {})
            else -> {
                val transfer = when (scene) {
                    "none" -> base.copy(candidates = emptyList())
                    "failed" -> base.copy(load = TransferLoad.FAILED, candidates = emptyList())
                    "success" -> RoomTransferUi(42, "Lounge", ownerActing = true, load = TransferLoad.HIDDEN,
                        outcome = TransferOutcome.SUCCESS, outcomeName = "Fixture moderator")
                    else -> base
                }
                // The success state is a moderator now: no owner explanation, Leave room instead.
                val owner = scene != "success"
                AlertDialog(onDismissRequest = {}, title = { Text("Lounge") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.rooms_presence_disclosure))
                        RoomTransferSection(transfer, owner, enabled = true, onOpen = {}, onRetry = {})
                        if (!owner) TextButton(onClick = {}) { Text(stringResource(R.string.rooms_leave)) }
                    }
                }, confirmButton = { TextButton(onClick = {}) { Text(stringResource(R.string.rooms_close)) } })
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-transfer-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
