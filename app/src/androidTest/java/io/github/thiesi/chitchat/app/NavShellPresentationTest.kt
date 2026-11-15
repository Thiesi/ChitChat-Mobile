package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import org.junit.Assert.assertNotNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Rect
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
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
                capture("nav-$label-$name") // Before the assertions, so a failure still leaves the evidence.
                for (tab in ShellTab.entries) assertTab("$label/$name/${tab.name}", tab)
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
        val facts = "size=${layout.size}, constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, " +
            "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}, " +
            "maxLines=${layout.layoutInput.maxLines}, softWrap=${layout.layoutInput.softWrap}, overflow=${layout.layoutInput.overflow}, " +
            "item=${item.boundsInRoot}, text=${text.boundsInRoot}"
        // TextLayoutResult.hasVisualOverflow reported width overflow for a one-line 41px "Rooms" in a 74px constraint
        // (local diagnostics, PR251), so the check is made against what is actually drawn instead.
        labelShortfall(layout, text.boundsInRoot, item.boundsInRoot)?.let { throw AssertionError("$where: $it ($facts)") }
    }

    /** The predicate must fail on clipped, ellipsized and vertically cut text, or it proves nothing. */
    @Test fun theLabelPredicateRejectsClippedEllipsizedAndCutText() {
        FontScaleTestActivity.requestedFontScale = 1f
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { ChitChatTheme(dark = false) { Column {
                Box(Modifier.width(24.dp).testTag("box_clip")) {
                    Text("Notifications", Modifier.testTag("neg_clip"), softWrap = false, maxLines = 1, overflow = TextOverflow.Clip) }
                Box(Modifier.width(24.dp).testTag("box_ellipsis")) {
                    Text("Notifications", Modifier.testTag("neg_ellipsis"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Box(Modifier.width(200.dp).height(10.dp).testTag("box_cut")) {
                    Text("Notifications\nSecond line", Modifier.testTag("neg_cut")) }
            } } } }
            compose.waitForIdle()
            for (case in listOf("clip", "ellipsis", "cut")) {
                val node = compose.onNodeWithTag("neg_$case", useUnmergedTree = true).fetchSemanticsNode()
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                assertNotNull("$case: the predicate reports a shortfall",
                    labelShortfall(layouts.single(), node.boundsInRoot, compose.onNodeWithTag("box_$case").fetchSemanticsNode().boundsInRoot))
            }
        }
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

/**
 * Why a label is not fully shown, or null: an ellipsized line, a line whose glyphs are wider than the drawn label,
 * a last line whose bottom is below the drawn label (a wrapped line cut off), or a label outside its item.
 */
internal fun labelShortfall(layout: TextLayoutResult, label: Rect, item: Rect): String? {
    for (line in 0 until layout.lineCount) {
        if (layout.isLineEllipsized(line)) return "line $line ellipsized"
        val width = layout.multiParagraph.getLineWidth(line) // Unclamped glyph width; line right/left clamp to the layout.
        if (width > label.width + 0.5f) return "line $line is ${width}px wide, drawn ${label.width}px"
    }
    val bottom = layout.getLineBottom(layout.lineCount - 1)
    if (bottom > label.height + 0.5f) return "text is ${bottom}px tall, drawn ${label.height}px"
    if (label.left < item.left - 0.5f || label.right > item.right + 0.5f || label.top < item.top - 0.5f ||
        label.bottom > item.bottom + 0.5f) return "label $label outside its item $item"
    return null
}
