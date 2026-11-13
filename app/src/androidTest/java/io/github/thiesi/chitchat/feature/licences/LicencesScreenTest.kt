package io.github.thiesi.chitchat.feature.licences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
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
import io.github.thiesi.chitchat.core.licences.OssLicences
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The licences list and one entry's terms, at the default and 2.0 font sizes. Captures go to the presentation lead. */
class LicencesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val licences = OssLicences.load(context.assets)
    private val fcm = licences.indexOfFirst { "org.unifiedpush.android:embedded-fcm-distributor" in it.covers }

    private var opened = -1
    private var detail by mutableStateOf<Int?>(null)

    private fun show(dark: Boolean, fontScale: Float) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            ChitChatTheme(dark = dark) { Surface {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                    detail?.let { LicenceScreen(it) } ?: LicencesScreen { opened = it; detail = it }
                }
            } }
        }
    }

    @Test fun everyEntryIsAFullWidth48dpRowAndOpensItsTerms() {
        show(dark = false, fontScale = 1f)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("licence:0").fetchSemanticsNodes().isNotEmpty() }
        licences.indices.forEach { compose.onNodeWithTag("licence:$it").assertHeightIsAtLeast(48.dp) }
        compose.onNodeWithTag("licence:$fcm").performScrollTo().performClick()
        assertEquals(fcm, opened)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("GNU LESSER GENERAL PUBLIC LICENSE", substring = true)
            .fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(licences[fcm].copyright!!).assertExists()
        compose.onNodeWithTag("licence_source").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(licences[fcm].source).assertExists()
    }

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        var scale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                ChitChatTheme(dark = dark) { Surface {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                        detail?.let { LicenceScreen(it) } ?: LicencesScreen { detail = it }
                    }
                } }
            }
        }
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            scale = fontScale; detail = null
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("licence:0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.oss_licences_intro)).assertExists()
            capture("licences-$label-$name-list")
            detail = fcm
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("licence_source").fetchSemanticsNodes().isNotEmpty() }
            capture("licences-$label-$name-detail")
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-licences-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
