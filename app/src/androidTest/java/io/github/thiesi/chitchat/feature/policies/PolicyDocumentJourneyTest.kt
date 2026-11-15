package io.github.thiesi.chitchat.feature.policies

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.policies.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.openTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** play-readiness.md §1 "Document screen" (P-176) and the About and privacy rows (O3a2). */
class PolicyDocumentJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val auth = AuthViewModel(sessions)
    private val reads = FakeReads()
    private val policies = PolicyDocumentViewModel(reads)
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) = Unit
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) = Unit
    })
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private val host = "chat.​example.​test" // The display-only dot break (#259).

    private fun install(discovery: PolicyDiscovery?) {
        sessions.nextConnect = { it.copy(public = it.public!!.copy(policies = discovery)) }
        compose.runOnUiThread { compose.activity.setContent { TestChitChatApp(settings, auth, policies = policies) } }
        compose.waitUntil(5_000) { sessions.state.value.public?.policies == discovery && sessions.state.value.public != null }
    }
    private val both = PolicyDiscovery(PolicyLink("https://chat.example.test/rules.php", 2, null),
        PolicyLink("https://chat.example.test/privacy.php", null, null), PolicyLink("https://example.org/imprint", null, null))

    @Test fun serverFoundShowsOnlyThePublishedDocumentsAndOpensTheHostedOne() {
        reads.next = PolicyDocument.Hosted("# Privacy\n\nWe keep **little**. See [the site](https://chat.example.test/more).", null,
            "2026-11-08T12:00:00+00:00", null)
        install(PolicyDiscovery(null, PolicyLink("https://chat.example.test/privacy.php", null, null), null))
        compose.onNodeWithTag("onboarding_rules").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_privacy_policy").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("policy_body").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.policy_privacy_title, host)).assertExists()
        compose.onNodeWithText("Privacy").assertExists() // The level-1 heading, rendered as a level-2 heading in the body.
        compose.onNodeWithTag("policy_caption").assertExists()
        compose.runOnIdle { assertEquals(listOf(PolicyDocumentName.PRIVACY), reads.requests) }
    }

    @Test fun noDiscoveryOrNoneShowsNoRows() {
        install(null)
        compose.onNodeWithTag("onboarding_sign_in").assertExists()
        compose.onNodeWithTag("onboarding_privacy_policy").assertDoesNotExist(); compose.onNodeWithTag("onboarding_rules").assertDoesNotExist()
    }

    /** Every link goes through the explicit choice; http gets the insecure line and no Open button. */
    @Test fun documentLinksOpenOnlyThroughTheExplicitChoice() {
        reads.next = PolicyDocument.Hosted("Read [secure](https://chat.example.test/a).\n\nOld [plain](http://chat.example.test/b).", 1, null, null)
        install(both)
        compose.onNodeWithTag("onboarding_rules").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("policy_body").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("secure", substring = true) and hasAnyAncestor(hasTestTag("policy_body"))).performFirstLinkClick()
        compose.onNodeWithTag("policy_link_dialog").assertExists(); compose.onNodeWithTag("policy_link_open").assertExists()
        compose.onNodeWithText(text(R.string.a11y_feedback_web, "chat.example.test")).assertExists()
        compose.onNodeWithTag("policy_link_cancel").performClick()
        compose.onNode(hasText("plain", substring = true) and hasAnyAncestor(hasTestTag("policy_body"))).performFirstLinkClick()
        compose.onNodeWithText(text(R.string.external_link_insecure, "chat.example.test")).assertExists()
        compose.onNodeWithText(text(R.string.external_link_insecure_title)).assertExists() // Nothing opens, so no "Open link".
        compose.onNodeWithTag("policy_link_open").assertDoesNotExist()
    }

    /**
     * Real documents mix https and http links in one paragraph: the SECOND link, targeted by its own annotation (not
     * "the first link"), gets the dialog for that link, in both orders (technical lead, PR #267).
     */
    @Test fun eachLinkInAMixedParagraphRoutesToItsOwnChoice() {
        reads.next = PolicyDocument.Hosted("Read [secure](https://chat.example.test/a) or [plain](http://insecure.example.test/b).\n\n" +
            "Old [legacy](http://insecure.example.test/c) and new [current](https://current.example.test/d).", 1, null, null)
        install(both)
        compose.onNodeWithTag("onboarding_rules").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("policy_body").fetchSemanticsNodes().isNotEmpty() }
        fun tapLink(paragraph: String, target: String) = compose.onNode(hasText(paragraph, substring = true) and
            hasAnyAncestor(hasTestTag("policy_body"))).performFirstLinkClick { range ->
            (range.item as? androidx.compose.ui.text.LinkAnnotation.Clickable)?.tag == target
        }
        tapLink("plain", "http://insecure.example.test/b") // The second link, after an https one.
        compose.onNodeWithText(text(R.string.external_link_insecure_title)).assertExists()
        compose.onNodeWithText(text(R.string.external_link_insecure, "insecure.example.test")).assertExists()
        compose.onNodeWithTag("policy_link_open").assertDoesNotExist()
        compose.onNodeWithTag("policy_link_cancel").performClick()
        tapLink("current", "https://current.example.test/d") // The second link, after an http one.
        compose.onNodeWithText(text(R.string.a11y_feedback_web, "current.example.test")).assertExists()
        compose.onNodeWithTag("policy_link_open").assertExists()
        compose.onNodeWithText(text(R.string.external_link_insecure_title)).assertDoesNotExist()
    }

    @Test fun externalDocumentsOpenOnlyHttpsAndExplainAnInsecureAddress() {
        reads.next = PolicyDocument.External("https://example.org/privacy")
        install(both)
        compose.onNodeWithTag("onboarding_privacy_policy").performScrollTo().performClick()
        compose.onNodeWithTag("policy_external_host").assertTextEquals("example.org")
        compose.onNodeWithTag("policy_open_external").performClick()
        compose.onNodeWithTag("policy_link_open").assertExists()
        compose.onNodeWithTag("policy_link_cancel").performClick()
        Espresso.pressBack()
        reads.next = PolicyDocument.External("http://example.org/rules")
        compose.onNodeWithTag("onboarding_rules").performScrollTo().performClick()
        compose.onNodeWithTag("policy_external_insecure").assertExists(); compose.onNodeWithTag("policy_open_external").assertDoesNotExist()
    }

    @Test fun notPublishedIsAPlainLineAndAFailureOffersRetryAsAFreshGet() {
        reads.next = PolicyDocument.NotPublished
        install(both)
        compose.onNodeWithTag("onboarding_privacy_policy").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.policy_not_published, host)).assertExists()
        Espresso.pressBack()
        reads.failNext = ApiFailure(FailureKind.TIMEOUT)
        reads.next = PolicyDocument.Hosted("Back now.", 3, null, null)
        compose.onNodeWithTag("onboarding_rules").performScrollTo().performClick()
        compose.onNodeWithTag("policy_retry").performClick()
        compose.onNodeWithText("Back now.").assertExists()
        compose.runOnIdle { assertEquals(3, reads.requests.size) }
    }

    /** Signed in: You → About and privacy has the documents and the legal notice (a link only, through the choice). */
    @Test fun youShowsTheDocumentRowsAndTheLegalNoticeLink() {
        reads.next = PolicyDocument.Hosted("Rules.", 2, "2026-11-08T12:00:00+00:00", null)
        install(both)
        compose.runOnIdle { runBlocking { sessions.signIn("Alice", "fixture-password", false) } }
        compose.openTab("you").performClick()
        compose.onNodeWithTag("you_privacy_policy").performScrollTo().assertExists()
        compose.onNodeWithTag("you_legal_notice").performScrollTo().performClick()
        compose.onNodeWithTag("you_legal_notice_dialog").assertExists()
        compose.onNodeWithText(text(R.string.a11y_feedback_web, "example.org")).assertExists()
        compose.onNodeWithTag("you_legal_notice_cancel").performClick()
        compose.onNodeWithTag("you_rules").performScrollTo().performClick()
        compose.onNodeWithText("Rules.").assertExists()
        compose.onNodeWithTag("policy_caption").assertTextContains("Version 2", substring = true)
    }

    private class FakeReads : PolicyReads {
        @Volatile var next: PolicyDocument = PolicyDocument.NotPublished
        @Volatile var failNext: ApiFailure? = null
        val requests = mutableListOf<PolicyDocumentName>()
        override suspend fun policyDocument(name: PolicyDocumentName, expected: SessionState): PolicyDocument {
            requests += name
            failNext?.let { failNext = null; throw it }
            return next
        }
    }
}
