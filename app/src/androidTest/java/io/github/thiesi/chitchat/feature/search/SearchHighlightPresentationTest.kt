package io.github.thiesi.chitchat.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.search.ExcerptMatch
import io.github.thiesi.chitchat.core.search.MessageSearchHit
import io.github.thiesi.chitchat.core.search.SearchParticipant
import io.github.thiesi.chitchat.core.search.SearchRoom
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic search rows with server match ranges (#283) for the presentation lead's review: no network. */
class SearchHighlightPresentationTest {
    @get:Rule val compose = createComposeRule()

    private val rows = listOf(
        MessageSearchHit.Room(1, "Party 🎉 deployment done", SearchParticipant(8, "Fixture user"),
            SearchRoom(4, "operations", "Operations"), "2026-11-14T10:00:00Z", null,
            listOf(ExcerptMatch(0, 5), ExcerptMatch(9, 10))),
        MessageSearchHit.Room(2, "… the second deployment of the evening went out after the review …",
            SearchParticipant(9, "Another fixture"), SearchRoom(4, "operations", "Operations"), "2026-11-14T10:05:00Z",
            "2026-11-14T10:06:00Z", listOf(ExcerptMatch(13, 10))),
        MessageSearchHit.Direct(3, "Plain fixture text whose ranges were dropped", SearchParticipant(7, "Viewer"),
            SearchParticipant(12, "Peer fixture"), true, "2026-11-14T10:10:00Z", null))

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        var scale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                ChitChatTheme(dark = dark) { Surface { Column(Modifier.width(360.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in rows) Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { SearchResultText(row, "10:00") }
                    }
                } } }
            }
        }
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            scale = fontScale; compose.waitForIdle()
            assertEquals(rows.size, compose.onAllNodesWithTag("message_search_excerpt").fetchSemanticsNodes().size)
            capture("search-$label-$name-results")
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-search-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
