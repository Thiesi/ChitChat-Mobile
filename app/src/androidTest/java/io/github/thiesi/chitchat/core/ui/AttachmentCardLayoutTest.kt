package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.core.media.AttachmentPolicyObservation
import io.github.thiesi.chitchat.core.media.PhotoPrivacyFailure
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A-04 at 2.0 font in 360×640dp: only the card's status text scrolls; its actions and the composer stay visible. */
class AttachmentCardLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val facts = mutableStateOf(AttachmentCardFacts("a-rather-long-photo-file-name-from-the-camera.heic", "image/heic",
        2_400_000, AttachmentCardPhase.PRIVACY_CHOICE, privacyFailure = PhotoPrivacyFailure.UNSUPPORTED_IMAGE,
        observation = AttachmentPolicyObservation.TooLarge(2_400_000, 1_000_000)))

    private fun pane() = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
            ChitChatTheme(dark = false) {
                Column(Modifier.size(360.dp, 640.dp).testTag("pane")) {
                    Spacer(Modifier.height(192.dp)) // The bounded header's 30% share.
                    Spacer(Modifier.weight(1f))
                    AttachmentCard(facts.value, enabled = true, onRemove = {}, onSendAnyway = {}, onCancel = {},
                        maxHeight = 96.dp) // 15% of the pane, as the composers pass it.
                    Box(Modifier.fillMaxWidth().height(56.dp).testTag("composer"))
                }
            }
        }
    }
    private fun fullyInsidePane(tag: String) {
        val pane = compose.onNodeWithTag("pane").getUnclippedBoundsInRoot()
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("$tag bottom ${node.bottom} within ${pane.bottom}", node.bottom <= pane.bottom)
    }

    @Test fun privacyChoiceKeepsRemoveSendAnywayAndTheComposerVisible() {
        pane()
        listOf("attachment_card_remove", "attachment_card_privacy_remove", "attachment_card_send_anyway", "composer")
            .forEach(::fullyInsidePane)
        compose.onNodeWithTag("attachment_card_status", useUnmergedTree = true).assert(hasScrollAction())
    }

    @Test fun uploadingKeepsCancelAndTheComposerVisible() {
        compose.runOnIdle { } // Rule idle before content.
        facts.value = AttachmentCardFacts("notes.txt", "text/plain", 3_400_000, AttachmentCardPhase.UPLOADING, sentBytes = 1_200_000)
        pane()
        listOf("attachment_card_cancel", "attachment_card_progress", "composer").forEach(::fullyInsidePane)
    }

    @Test fun unreadableSettingsNoticeKeepsItsActionsAndTheComposerVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChitChatTheme(dark = false) {
                    Column(Modifier.size(360.dp, 640.dp).testTag("pane")) {
                        Spacer(Modifier.height(192.dp))
                        Spacer(Modifier.weight(1f))
                        AttachUnavailableNotice(AttachAvailability.SETTINGS_UNREADABLE, "chat.example.test", persisting = true,
                            canCheckAgain = true, onCheckAgain = {}, onDismiss = {}, maxHeight = 96.dp)
                        AttachmentCard(facts.value, enabled = true, onRemove = {}, onSendAnyway = {}, maxHeight = 96.dp)
                        Box(Modifier.fillMaxWidth().height(56.dp).testTag("composer"))
                    }
                }
            }
        }
        listOf("attach_check_again", "attachment_card_send_anyway", "composer").forEach(::fullyInsidePane)
    }
}
