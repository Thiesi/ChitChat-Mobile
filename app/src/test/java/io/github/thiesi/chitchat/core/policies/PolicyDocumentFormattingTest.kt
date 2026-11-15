package io.github.thiesi.chitchat.core.policies

import io.github.thiesi.chitchat.core.policies.DocumentBlock.*
import io.github.thiesi.chitchat.core.policies.DocumentInline.*
import org.junit.Assert.*
import org.junit.Test

/** P-176's display subset, its link classification and its bounds (play-readiness.md §1). */
class PolicyDocumentFormattingTest {
    private fun parse(text: String) = PolicyDocumentFormatting.parse(text)
    private fun only(text: String) = parse(text).blocks.single()
    private fun plainText(blocks: List<DocumentBlock>): String = blocks.joinToString("\n") { block ->
        when (block) {
            is Heading -> inlineText(block.content); is Paragraph -> inlineText(block.content)
            is ListItem -> inlineText(block.content); is Plain -> block.text
        }
    }
    private fun inlineText(nodes: List<DocumentInline>): String = nodes.joinToString("") { node ->
        when (node) {
            is Text -> node.text; is Code -> node.text
            is Strong -> inlineText(node.content); is Emphasis -> inlineText(node.content); is Link -> inlineText(node.content)
        }
    }
    private fun links(nodes: List<DocumentInline>): List<Link> = nodes.flatMap { node ->
        when (node) {
            is Link -> listOf(node); is Strong -> links(node.content); is Emphasis -> links(node.content); else -> emptyList()
        }
    }

    @Test fun headingsStartAtLevelTwoBecauseTheScreenOwnsTheTitle() {
        val blocks = parse("# Rules\n\n## Be kind\n\n### Details ###\n\nSetext\n=====\n\nOther\n-----").blocks
        assertEquals(listOf(2, 2, 3, 2, 2), blocks.map { (it as Heading).level })
        assertEquals(listOf("Rules", "Be kind", "Details", "Setext", "Other"), blocks.map { inlineText((it as Heading).content) })
        assertTrue(only("#hashtag is text") is Paragraph)
    }

    @Test fun paragraphsJoinSoftBreaksAndKeepHardOnes() {
        assertEquals("one two", inlineText((only("one\ntwo") as Paragraph).content))
        assertEquals("one\ntwo", inlineText((only("one  \ntwo") as Paragraph).content))
    }

    @Test fun listItemsKeepOrderNumbersAndCappedNesting() {
        val blocks = parse("- first\n  continued\n- second\n    - nested\n1. one\n2) two").blocks.map { it as ListItem }
        assertEquals(listOf("first continued", "second", "nested", "one", "two"), blocks.map { inlineText(it.content) })
        assertEquals(listOf(false, false, false, true, true), blocks.map { it.ordered })
        assertEquals(listOf(null, null, null, 1, 2), blocks.map { it.number })
        assertEquals(2, blocks[2].indent)
    }

    @Test fun quotesFlattenCodeIsPlainAndBreaksAreDropped() {
        val blocks = parse("> quoted **bold**\n\n---\n\n```kotlin\nval a = \"<b>\"\n```\n\n    indented code").blocks
        assertEquals("quoted bold", inlineText((blocks[0] as Paragraph).content))
        assertEquals("val a = \"<b>\"", (blocks[1] as Plain).text)
        assertEquals("indented code", inlineText((blocks[2] as Paragraph).content))
        assertEquals(3, blocks.size)
    }

    @Test fun inlineEmphasisStrongCodeAndEscapes() {
        val content = (only("a *em* and **strong** and `co*de` and \\*literal\\* and snake_case_word") as Paragraph).content
        assertTrue(content.any { it is Emphasis && inlineText(it.content) == "em" })
        assertTrue(content.any { it is Strong && inlineText(it.content) == "strong" })
        assertTrue(content.any { it is Code && it.text == "co*de" })
        assertEquals("a em and strong and co*de and *literal* and snake_case_word", inlineText(content))
    }

    @Test fun onlyHttpHttpsAndMailtoBecomeLinksOthersKeepTheirText() {
        val content = (only("[Site](https://chat.example/rules) [Old](http://chat.example/) [Mail](mailto:admin@chat.example) " +
            "[Bad](javascript:alert(1)) [File](file:///etc/passwd) [Cred](https://user:pw@chat.example/)") as Paragraph).content
        val found = links(content)
        assertEquals(listOf(LinkKind.HTTPS, LinkKind.HTTP, LinkKind.MAILTO), found.map { it.kind })
        assertEquals(listOf("https://chat.example/rules", "http://chat.example/", "mailto:admin@chat.example"), found.map { it.target })
        assertEquals("Site Old Mail Bad File Cred", inlineText(content))
        assertFalse(inlineText(content).contains("javascript"))
    }

    @Test fun autolinksAndBareUrlsAreClassifiedTooWithoutTrailingPunctuation() {
        val content = (only("See <https://chat.example/a> or https://chat.example/b. Mail <mailto:x@chat.example>.") as Paragraph).content
        assertEquals(listOf("https://chat.example/a", "https://chat.example/b", "mailto:x@chat.example"), links(content).map { it.target })
    }

    @Test fun imagesAreDroppedWithTheirAltTextAndNeverBecomeLinks() {
        val content = (only("Before ![a secret alt](https://tracker.example/pixel.png) after") as Paragraph).content
        assertEquals("Before  after", inlineText(content))
        assertTrue(links(content).isEmpty())
    }

    @Test fun rawHtmlAndScriptStayLiteralText() {
        val html = "<script>alert('x')</script> <b>bold</b> <img src=x onerror=alert(1)>"
        val content = (only(html) as Paragraph).content
        assertEquals(html, inlineText(content))
        assertTrue(links(content).isEmpty())
    }

    @Test fun anOversizedDocumentFallsBackToLiteralParagraphs() {
        val huge = "**bold** paragraph\n\n".repeat(PolicyDocumentFormatting.MAX_INPUT_CHARS / 10)
        val result = parse(huge)
        assertTrue(result.literalFallback)
        assertTrue(result.blocks.all { it is Paragraph && it.content.single() is Text })
        assertEquals("**bold** paragraph", inlineText((result.blocks.first() as Paragraph).content))
    }

    @Test fun deepQuoteNestingFallsBackToLiteralText() {
        val result = parse(">".repeat(50) + " too deep")
        assertTrue(result.literalFallback)
        assertTrue(plainText(result.blocks).contains("too deep"))
    }

    @Test fun deepInlineNestingFallsBackToLiteralText() {
        val nested = "[".repeat(40) + "x" + "](https://chat.example/)".repeat(40)
        val emphasis = "*a ".repeat(5_000) + "z" + " a*".repeat(5_000)
        for (input in listOf(nested, emphasis)) {
            val result = parse(input)
            assertTrue(result.literalFallback || plainText(result.blocks).isNotEmpty())
            assertTrue("Never a blank document", plainText(result.blocks).isNotBlank())
        }
    }

    @Test fun pathologicalDelimitersStayWithinTheWorkBudget() {
        val started = System.nanoTime()
        val result = parse("*".repeat(60_000) + "\n\n" + "[".repeat(60_000) + "\n\n" + "`".repeat(60_000))
        assertTrue("Bounded work", (System.nanoTime() - started) / 1_000_000 < 5_000)
        assertTrue(plainText(result.blocks).isNotBlank())
    }

    @Test fun anUnclosedFenceKeepsTheRestAsPlainText() {
        val blocks = parse("Intro\n\n```\nnot closed\n# not a heading").blocks
        assertEquals("not closed\n# not a heading", (blocks[1] as Plain).text)
    }
}
