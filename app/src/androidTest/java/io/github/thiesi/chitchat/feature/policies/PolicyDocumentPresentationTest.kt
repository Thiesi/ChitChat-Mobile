package io.github.thiesi.chitchat.feature.policies

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.policies.PolicyDocument
import io.github.thiesi.chitchat.core.policies.PolicyDocumentName
import io.github.thiesi.chitchat.core.policies.PolicyReads
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The document screen (O3a2) at a real Configuration font scale, light and dark: a hosted document with every element,
 * external and not published at both sizes; the http link's dialog, loading and failure at 200%.
 */
class PolicyDocumentPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private val hosted = PolicyDocument.Hosted("""
        # Community rules

        Welcome to **Example Chat**. These rules keep the place friendly.

        ## Be kind
        - Treat everyone with respect, even when you disagree.
        - No harassment, hate speech or *threats*.
          1. A first warning
          2. Then a timeout

        ### Details
        Use `/me` for actions. <b>Raw HTML</b> stays literal, and ![an image](https://chat.example.test/x.png) is dropped.

        ## Questions
        Ask in the [help desk](https://chat.example.test/help) or write to <mailto:admin@chat.example.test>.

        Older posts live in the [old archive](http://chat.example.test/old).
    """.trimIndent(), 3, "2026-11-08T12:00:00+00:00", null)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        val session = SessionState(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address, generation = 1)
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            for ((state, document) in listOf("hosted" to hosted, "external" to PolicyDocument.External("https://example.org/privacy"),
                "unpublished" to PolicyDocument.NotPublished)) {
                val model = PolicyDocumentViewModel(object : PolicyReads {
                    override suspend fun policyDocument(name: PolicyDocumentName, expected: SessionState) = document
                })
                ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                        model.open(PolicyDocumentName.RULES, session)
                        activity.setContent { ChitChatTheme(dark = dark) { Surface { Box(Modifier.fillMaxSize().testTag("policy_host")
                            .verticalScroll(rememberScrollState()).padding(16.dp)) {
                            PolicyDocumentScreen(model, PolicyDocumentName.RULES, "Example Chat")
                        } } } }
                    }
                    compose.waitUntil(5_000) { model.state.value?.status !is PolicyDocumentStatus.Loading }
                    capture("policy-$label-$name-$state")
                    if (state == "hosted" && fontScale == 2f) { // The http link's explicit choice: no Open button.
                        compose.onNode(hasText("old archive", substring = true) and hasAnyAncestor(hasTestTag("policy_body")))
                            .performScrollTo().performFirstLinkClick()
                        compose.onNodeWithTag("policy_link_open").assertDoesNotExist()
                        captureNode(compose.onNode(isDialog()), "policy-$label-$name-httpdialog")
                    }
                }
            }
            if (fontScale == 2f) for ((state, reads) in listOf(
                "loading" to object : PolicyReads { override suspend fun policyDocument(name: PolicyDocumentName, expected: SessionState): PolicyDocument = awaitCancellation() },
                "failed" to object : PolicyReads { override suspend fun policyDocument(name: PolicyDocumentName, expected: SessionState): PolicyDocument =
                    throw ApiFailure(FailureKind.TIMEOUT) })) {
                val model = PolicyDocumentViewModel(reads)
                ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        model.open(PolicyDocumentName.PRIVACY, session)
                        activity.setContent { ChitChatTheme(dark = dark) { Surface { Box(Modifier.fillMaxSize().testTag("policy_host").padding(16.dp)) {
                            PolicyDocumentScreen(model, PolicyDocumentName.PRIVACY, "Example Chat")
                        } } } }
                    }
                    if (state == "failed") compose.waitUntil(5_000) { model.state.value?.status is PolicyDocumentStatus.Failed }
                    compose.onNodeWithTag(if (state == "failed") "policy_retry" else "policy_loading").assertIsDisplayed()
                    capture("policy-$label-$name-$state")
                }
            }
        }
    }

    private fun capture(name: String) = captureNode(compose.onNodeWithTag("policy_host"), name)

    private fun captureNode(node: androidx.compose.ui.test.SemanticsNodeInteraction, name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-policy-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
