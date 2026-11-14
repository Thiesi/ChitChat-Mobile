package io.github.thiesi.chitchat.feature.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.thiesi.chitchat.core.search.ExcerptMatch
import org.junit.Assert.*
import org.junit.Test

class SearchExcerptTest {
    private val wash = Color(0xFFFFF0D2)

    @Test fun eachMatchGetsTheWashAndSemiBoldAndTheTextIsUnchanged() {
        val text = "… Party 🎉 deployment done …"
        val styled = excerptWithMatches(text, listOf(ExcerptMatch(2, 5), ExcerptMatch(11, 10)), wash)
        assertEquals("The server's text, ellipses included, as given", text, styled.text)
        assertEquals(listOf(2 to 7, 11 to 21), styled.spanStyles.map { it.start to it.end })
        for (span in styled.spanStyles) {
            assertEquals(wash, span.item.background)
            assertEquals(FontWeight.SemiBold, span.item.fontWeight)
            assertNotEquals("Never an underline: that means link", TextDecoration.Underline, span.item.textDecoration)
        }
    }

    @Test fun noMatchesIsPlainText() {
        assertTrue(excerptWithMatches("Plain fixture", emptyList(), wash).spanStyles.isEmpty())
    }
}
