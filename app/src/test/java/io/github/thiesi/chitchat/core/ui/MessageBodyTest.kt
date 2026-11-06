package io.github.thiesi.chitchat.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.thiesi.chitchat.core.formatting.MessageFormatting
import org.junit.Assert.*
import org.junit.Test

class MessageBodyTest {
    private val link = Color.Blue
    private val code = Color.Black
    private val background = Color.Yellow
    private fun render(text: String, prefix: String = "") = messageInlineText(
        MessageFormatting.parseInline(text).nodes, link, code, background, prefix)

    @Test fun nestedEmphasisPreservesExactCharacterRanges() {
        val value = render("A *bold _italic_* end")
        assertEquals("A bold italic end", value.text)
        assertTrue(value.spanStyles.any { it.start == 2 && it.end == 13 && it.item.fontWeight == FontWeight.Bold })
        assertTrue(value.spanStyles.any { it.start == 7 && it.end == 13 && it.item.fontStyle == FontStyle.Italic })
    }
    @Test fun emoteUsernamePrefixIsLiteralAndHasNoInheritedBodyStyle() {
        val prefix = "* _*author*_ "
        val value = render("*waves*", prefix)
        assertEquals(prefix + "waves", value.text)
        assertEquals(prefix.length, value.spanStyles.single().start)
        assertEquals(value.length, value.spanStyles.single().end)
    }
    @Test fun codeCharactersRemainLiteralWithReadableCodeStyles() {
        val value = render("run `*a* _b_ https://example.test/` now")
        assertEquals("run *a* _b_ https://example.test/ now", value.text)
        val style = value.spanStyles.single().item
        assertEquals(FontFamily.Monospace, style.fontFamily)
        assertEquals(code, style.color); assertEquals(background, style.background)
        assertTrue(messageLinks("`https://example.test/`").isEmpty())
    }
    @Test fun linksAreStyledButActivationBelongsToTheContainingActionsTarget() {
        val value = render("See https://example.test/path.")
        assertEquals("See https://example.test/path.", value.text)
        assertEquals(link, value.spanStyles.single().item.color)
        assertEquals(TextDecoration.Underline, value.spanStyles.single().item.textDecoration)
        assertTrue(value.getLinkAnnotations(0, value.length).isEmpty())
        assertEquals(listOf("https://example.test/path"), messageLinks(value.text).map { it.target })
    }
    @Test fun linkChoicesRespectBlockAndNestedInlineGrammar() {
        val source = "*https://example.test/one*\n> https://example.test/two\n```\nhttps://example.test/secret-code\n```"
        assertEquals(listOf("https://example.test/one", "https://example.test/two"), messageLinks(source).map { it.target })
    }
    @Test fun emotesUseInlineGrammarRatherThanInterpretingBlockFences() {
        val source = "```\nhttps://example.test/inline\n```"
        assertTrue(messageLinks(source).isEmpty())
        assertEquals(listOf("https://example.test/inline"), messageLinks(source, inlineOnly = true).map { it.target })
    }
    @Test fun duplicateNormalizedDestinationsProduceOneExplicitChoice() {
        val links = messageLinks("HTTPS://EXAMPLE.TEST https://example.test/ https://example.test/other")
        assertEquals(listOf("https://example.test/", "https://example.test/other"), links.map { it.target })
        assertEquals("HTTPS://EXAMPLE.TEST", links.first().text)
    }
    @Test fun excessivelyExpandedLinkAddressesStayLiteralInsteadOfOfferingAnUnusableChoice() {
        val source = "https://example.test/" + "雪".repeat(2_000)
        assertNull(MessageFormatting.safeLinkTarget(source))
        assertEquals(source, render(source).text)
        assertTrue(messageLinks(source).isEmpty())
    }
    @Test fun rejectedLinksHtmlAndMentionsDoNotGainInteractiveOrMentionAnnotations() {
        val source = "<b>@some_user</b> https://name:private@example.test/path_*text* javascript:alert(1)"
        val value = render(source)
        assertEquals(source, value.text)
        assertTrue(value.spanStyles.isEmpty()); assertTrue(value.getLinkAnnotations(0, value.length).isEmpty())
        assertTrue(messageLinks(source).isEmpty())
    }
    @Test fun complexityFallbackPreservesWholeSourceAndOffersNoPartialLinks() {
        val source = "https://example.test/ " + "*a ".repeat(2_000)
        assertTrue(MessageFormatting.parseInline(source).literalFallback)
        assertEquals(source, render(source).text)
        assertTrue(messageLinks(source).isEmpty())
    }
    @Test fun unicodeBodiesKeepEveryCodePointAndUseUtf16StyleOffsets() {
        val prefix = "🎉".repeat(3_990)
        val value = render(prefix + "*end*")
        assertEquals(prefix + "end", value.text)
        assertEquals(prefix.length, value.spanStyles.single().start)
        assertEquals(prefix.length + 3, value.spanStyles.single().end)
    }
}
