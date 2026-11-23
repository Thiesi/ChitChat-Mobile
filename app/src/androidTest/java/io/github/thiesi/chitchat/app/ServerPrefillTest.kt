package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.PendingServerLink
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.server.ServerLink
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.performNativeTap
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Server prefill P1 (PL rulings A to G, TL notes): a chitchat://connect link only prefills "Where do you chat?". The
 * person always taps Continue; nothing connects (the session generation stays), the saved server stays until Continue,
 * typed text is never replaced without a tap, nothing switches while signed in, an invalid value is never shown, and
 * every shown or filled host is the canonical ASCII one. Captures `prefill-*` for the PL's J (taken before asserts).
 */
class ServerPrefillTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    @Volatile private var imeBottom = 0

    private class Env(val sessions: TestSessions, val store: MutableStateFlow<AppSettings>, val links: PendingServerLink,
        val ui: ServerLinkViewModel)

    private fun link(value: String) = ServerLink.from("chitchat://connect?server=$value")!!
    private fun address(text: String) = (ServerAddress.parse(text) as AddressResult.Valid).address

    private fun run(saved: String?, dark: Boolean = false, fontScale: Float = 1f, signedIn: Boolean = false, body: (Env) -> Unit) {
        FontScaleTestActivity.requestedFontScale = fontScale
        val sessions = TestSessions()
        val store = MutableStateFlow(AppSettings(server = saved?.let(::address), theme = if (dark) ThemePreference.DARK else ThemePreference.LIGHT))
        val settingsStore = object : SettingsStore {
            override val settings = store
            override suspend fun saveServer(server: ServerAddress?) { store.value = store.value.copy(server = server) }
            override suspend fun savePureBlack(enabled: Boolean) = Unit
            override suspend fun saveTheme(theme: ThemePreference) { store.value = store.value.copy(theme = theme) }
        }
        val links = PendingServerLink()
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            lateinit var ui: ServerLinkViewModel
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                ui = ViewModelProvider(activity)[ServerLinkViewModel::class.java]
                val settings = ViewModelProvider(activity, SettingsViewModel.factory(settingsStore))[SettingsViewModel::class.java]
                val auth = ViewModelProvider(activity, AuthViewModel.factory(sessions))[AuthViewModel::class.java]
                activity.setContent {
                    val bottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
                    androidx.compose.runtime.SideEffect { imeBottom = bottom }
                    Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, serverLinks = links, linkUi = ui) }
                }
            }
            if (saved != null) compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
            if (signedIn) compose.runOnIdle { runBlocking { sessions.signIn("Viewer", "fixture-password") } }
            compose.waitForIdle()
            body(Env(sessions, store, links, ui))
        }
    }

    private fun field() = compose.onNodeWithTag("onboarding_server_input").fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    /** The activity's own root (a dialog or a text-handle popup is another root). */
    private fun appRoot() = compose.onNode(isRoot() and androidx.compose.ui.test.hasAnyDescendant(hasTestTag("onboarding_server_input") or
        hasTestTag("onboarding_server_card") or hasTestTag("sign_in_username") or hasTestTag("onboarding_welcome_start")))

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(context.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-prefill-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = (if (dialog) compose.onNode(isDialog()) else appRoot()).captureToImage().asAndroidBitmap()
        try { java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun scales(run: (String, Float) -> Unit) { run("default", 1f); run("font200", 2f) }

    // A and B: no server; the link waits on Welcome (unchanged) and fills step 2 once, with the host-free line.
    @Test fun stepTwoFilledLight() = scales { name, factor -> filled("prefill-step2-light-$name", dark = false, factor) }
    @Test fun stepTwoFilledDark() = scales { name, factor -> filled("prefill-step2-dark-$name", dark = true, factor) }

    private fun filled(name: String, dark: Boolean, factor: Float) = run(null, dark, factor) { env ->
        val generation = env.sessions.state.value.generation
        compose.runOnIdle { env.links.offer(link("chat.example.org")) }
        compose.waitForIdle()
        val welcomeStill = shown("onboarding_welcome_start") && !shown("prefill_filled")
        compose.onNodeWithTag("onboarding_welcome_start").performNativeTap()
        compose.waitUntil(5_000) { shown("onboarding_server_input") }
        capture(name)
        assertTrue("Welcome is unchanged until Get started", welcomeStill)
        assertEquals("chat.example.org", field())
        assertTrue(shown("prefill_filled"))
        assertEquals("No request on arrival", generation, env.sessions.state.value.generation)
        assertEquals("Nothing saved", null, env.store.value.server)
    }

    /** Once per arrival: cleared stays cleared, even back and forth. And an IDN host is filled as ASCII (TL, PL). */
    @Test fun filledOncePerArrivalAndIdnShowsAscii() = run(null) { env ->
        compose.runOnIdle { env.links.offer(link("b%C3%BCcher.example")) }
        compose.onNodeWithTag("onboarding_welcome_start").performNativeTap()
        compose.waitUntil(5_000) { shown("onboarding_server_input") }
        assertEquals("xn--bcher-kva.example", field())
        compose.tapNativeInput("onboarding_server_input", { imeBottom }).performTextReplacement("")
        assertFalse("The line goes on edit", shown("prefill_filled"))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { shown("onboarding_welcome_start") }
        compose.onNodeWithTag("onboarding_welcome_start").performNativeTap()
        compose.waitUntil(5_000) { shown("onboarding_server_input") }
        assertEquals("Not refilled", "", field())
    }

    // C: typed text stays; an offer with Use this address and Dismiss; the same address does nothing.
    @Test fun typedTextGetsAnOffer() = scales { name, factor ->
        run(null, fontScale = factor) { env ->
            compose.onNodeWithTag("onboarding_welcome_start").performNativeTap()
            compose.waitUntil(5_000) { shown("onboarding_server_input") }
            compose.tapNativeInput("onboarding_server_input", { imeBottom }).performTextReplacement("typed.example.org")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.runOnIdle { env.links.offer(link("TYPED.example.org")) }
            compose.waitForIdle()
            val sameDoesNothing = !shown("prefill_offer")
            compose.runOnIdle { env.links.offer(link("other.example.org")) }
            compose.waitUntil(5_000) { shown("prefill_offer") }
            capture("prefill-offer-light-$name")
            assertTrue("The same normalized address does nothing", sameDoesNothing)
            assertEquals("Typed text unchanged until the tap", "typed.example.org", field())
            compose.onNodeWithTag("prefill_offer_use").performNativeTap()
            compose.waitForIdle()
            assertEquals("other.example.org", field())
            assertTrue(shown("prefill_filled"))
            assertFalse(shown("prefill_offer"))
        }
    }

    // D: Server found. The same host does nothing; another opens "Use a different server" filled; the saved one stays.
    @Test fun serverFoundOpensStepTwoFilled() = run("saved.example.org") { env ->
        compose.waitUntil(5_000) { shown("onboarding_server_card") }
        val generation = env.sessions.state.value.generation
        compose.runOnIdle { env.links.offer(link("saved.example.org")) }
        compose.waitForIdle()
        val sameStays = shown("onboarding_server_card") && !shown("onboarding_server_input")
        compose.runOnIdle { env.links.offer(link("other.example.org")) }
        compose.waitUntil(5_000) { shown("onboarding_server_input") }
        capture("prefill-server-found-light-default")
        assertTrue("The same host does nothing", sameStays)
        assertEquals("other.example.org", field())
        assertTrue(shown("prefill_filled"))
        assertEquals("The saved server stays until Continue", address("saved.example.org"), env.store.value.server)
        assertEquals("No request", generation, env.sessions.state.value.generation)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { shown("onboarding_server_card") }
    }

    // E: a form open. No navigation; the offer on top; only its tap leaves the form.
    @Test fun anOpenFormGetsTheOffer() = scales { name, factor ->
        run("saved.example.org", fontScale = factor) { env ->
            compose.waitUntil(5_000) { shown("onboarding_server_card") }
            compose.onNodeWithTag("onboarding_sign_in").performNativeTap()
            compose.waitUntil(5_000) { shown("sign_in_username") }
            compose.tapNativeInput("sign_in_username", { imeBottom }).performTextReplacement("Alice")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.runOnIdle { env.links.offer(link("other.example.org")) }
            compose.waitUntil(5_000) { shown("prefill_offer") }
            capture("prefill-form-light-$name")
            assertTrue("Still on the form", shown("sign_in_username"))
            assertEquals("Typed text unchanged", "Alice", compose.onNodeWithTag("sign_in_username").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.EditableText)?.text)
            compose.onNodeWithTag("prefill_offer_use").performNativeTap()
            compose.waitUntil(5_000) { shown("onboarding_server_input") }
            assertEquals("other.example.org", field())
            assertEquals("The saved server stays", address("saved.example.org"), env.store.value.server)
        }
    }

    // F: signed in. A dialog for another host (never stacked, no request); the same host does nothing; it takes window focus.
    @Test fun signedInLight() = scales { name, factor -> signedIn("prefill-signed-in-light-$name", dark = false, factor) }
    @Test fun signedInDark() = scales { name, factor -> signedIn("prefill-signed-in-dark-$name", dark = true, factor) }

    private fun signedIn(name: String, dark: Boolean, factor: Float) = run("saved.example.org", dark, factor, signedIn = true) { env ->
        compose.waitUntil(5_000) { env.sessions.state.value.phase == SessionPhase.AUTHENTICATED }
        val generation = env.sessions.state.value.generation
        compose.runOnIdle { env.links.offer(link("saved.example.org")) }
        compose.waitForIdle()
        val sameNothing = !shown("prefill_signed_in")
        compose.runOnIdle { env.links.offer(link("other.example.org")) }
        compose.waitUntil(5_000) { shown("prefill_signed_in") }
        compose.runOnIdle { env.links.offer(link("third.example.org")) }
        compose.waitForIdle()
        capture(name, dialog = true)
        assertTrue("The same host does nothing", sameNothing)
        assertEquals("Never stacked", 1, compose.onAllNodesWithTag("prefill_signed_in").fetchSemanticsNodes().size)
        var focused = true
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            focused = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single().hasWindowFocus()
        }
        assertFalse("The dialog's window holds focus, which suppresses read and typing proof", focused)
        assertEquals("No request, no switch", generation, env.sessions.state.value.generation)
        assertEquals(address("saved.example.org"), env.store.value.server)
        compose.onNodeWithTag("prefill_signed_in_ok").performNativeTap()
        compose.waitUntil(5_000) { !shown("prefill_signed_in") }
        assertEquals("Signed in still", SessionPhase.AUTHENTICATED, env.sessions.state.value.phase)
    }

    // G: an invalid value from a link with step 2's field empty: the line, the field empty, the value never shown.
    @Test fun anInvalidLinkShowsTheLineNeverTheValue() = run(null) { env ->
        compose.runOnIdle { env.links.offer(link("not%20a%20host")) }
        compose.onNodeWithTag("onboarding_welcome_start").performNativeTap()
        compose.waitUntil(5_000) { shown("prefill_invalid") }
        capture("prefill-invalid-light-default")
        assertEquals("The field stays empty", "", field())
        assertTrue("The value is never shown", compose.onAllNodes(androidx.compose.ui.test.hasText("not a host", substring = true))
            .fetchSemanticsNodes().isEmpty())
        assertEquals(null, env.store.value.server)
    }
}
