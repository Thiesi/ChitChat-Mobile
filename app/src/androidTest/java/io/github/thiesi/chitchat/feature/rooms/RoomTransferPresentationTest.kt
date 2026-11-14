package io.github.thiesi.chitchat.feature.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.rooms.RoomTransferMember
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import org.junit.Rule
import org.junit.Test

/** Synthetic P-217 states for the presentation lead's J review: no network, real composables. */
class RoomTransferPresentationTest {
    @get:Rule val compose = createComposeRule()

    private val members = listOf(RoomTransferMember(8, "Fixture moderator", "moderator"),
        RoomTransferMember(9, "Another fixture", "member"), RoomTransferMember(10, "Third fixture", "member"))
    private val base = RoomTransferUi(42, "Lounge", ownerActing = true, load = TransferLoad.LOADED,
        owner = RoomTransferMember(7, "Viewer", "owner"), candidates = members)

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        var scale by mutableStateOf(1f)
        var scene by mutableStateOf("row")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                ChitChatTheme(dark = dark) {
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
            }
        }
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            scale = fontScale
            for (each in listOf("row", "none", "failed", "picker", "confirm", "success")) {
                scene = each; compose.waitForIdle()
                when (each) {
                    "row" -> compose.onNodeWithTag("rooms_transfer_open").assertHeightIsAtLeast(48.dp)
                    "failed" -> compose.onNodeWithTag("rooms_transfer_retry").assertHeightIsAtLeast(48.dp)
                    "none" -> compose.onNodeWithTag("rooms_transfer_none").assertExists()
                    "picker" -> compose.onNodeWithTag("rooms_transfer_picker").assertExists()
                    "confirm" -> compose.onNodeWithTag("rooms_transfer_make_owner").assertExists()
                    "success" -> compose.onNodeWithTag("rooms_transfer_success").assertExists()
                }
                capture("transfer-$label-$name-$each")
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
