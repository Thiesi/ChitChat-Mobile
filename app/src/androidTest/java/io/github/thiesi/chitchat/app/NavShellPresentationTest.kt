package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Navigation shell bottom bar at a real Configuration font scale (default and 2.0) on the AVD's actual window,
 * light and dark: every tab label is fully shown (no clipping or ellipsis) inside its item, and every item is a
 * target of at least 48dp. The Activity tab carries a count badge. No network.
 */
class NavShellPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                    assertEquals("The platform density is untouched",
                        InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.densityDpi,
                        activity.resources.configuration.densityDpi)
                    activity.setContent { ChitChatTheme(dark = dark) { Box(Modifier.fillMaxWidth().testTag("nav_bar_host")) {
                        ShellBar(ShellTab.entries, ShellTab.ROOMS, unread = 2, countStale = false, countText = "2 unread notifications") {}
                    } } }
                }
                compose.waitForIdle()
                for (tab in ShellTab.entries) assertTab("$label/$name/${tab.name}", tab)
                capture("nav-$label-$name")
            }
        }
    }

    private fun assertTab(where: String, tab: ShellTab) {
        val item = compose.onNodeWithTag(tab.tag).fetchSemanticsNode()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("$where: item at least 48dp tall", item.boundsInRoot.height / density >= 48f - 0.5f)
        assertTrue("$where: item at least 48dp wide", item.boundsInRoot.width / density >= 48f - 0.5f)
        val text = labelNode(tab) ?: throw AssertionError("$where: no label text")
        val layouts = mutableListOf<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertFalse("$where: label not clipped", layout.hasVisualOverflow)
        assertFalse("$where: label not ellipsized", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        val bounds = text.boundsInRoot
        assertTrue("$where: label inside its item", bounds.left >= item.boundsInRoot.left - 0.5f &&
            bounds.right <= item.boundsInRoot.right + 0.5f && bounds.bottom <= item.boundsInRoot.bottom + 0.5f)
    }

    /** The item's own label text node (the badge's count is a separate text), from the unmerged tree. */
    private fun labelNode(tab: ShellTab): SemanticsNode? {
        val wanted = InstrumentationRegistry.getInstrumentation().targetContext.getString(tab.label)
        fun walk(node: SemanticsNode): SemanticsNode? {
            val texts = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
            if (texts.any { it.text == wanted } && node.config.contains(SemanticsActions.GetTextLayoutResult)) return node
            return node.children.firstNotNullOfOrNull(::walk)
        }
        return walk(compose.onNodeWithTag(tab.tag, useUnmergedTree = true).fetchSemanticsNode())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-nav-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag("nav_bar_host").captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
