package io.github.thiesi.chitchat.core.formatting

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Outputs from the hash-verified pinned browser parser, not a second Kotlin implementation. */
@RunWith(Parameterized::class)
class MessageFormattingBrowserTest(private val label: String, private val fixture: JsonObject) {
    @Test fun conformsToPinnedBrowserGrammar() {
        val source = fixture.getValue("source").jsonPrimitive.content
        val inline = MessageFormatting.parseInline(source)
        val message = MessageFormatting.parseMessage(source)
        assertFalse(label, inline.literalFallback); assertFalse(label, message.literalFallback)
        assertEquals(label, fixture.getValue("inline"), jsonInline(inline.nodes))
        assertEquals(label, fixture.getValue("blocks"), jsonBlocks(message.blocks))
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun fixtures(): List<Array<Any>> {
            val resource = checkNotNull(MessageFormattingBrowserTest::class.java.getResourceAsStream("/message-format/browser-cases.json"))
            return resource.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }.map {
                val fixture = it.jsonObject
                arrayOf(fixture.getValue("name").jsonPrimitive.content, fixture)
            }
        }
    }
}

class MessageFormattingTest {
    @Test fun credentialsAndControlsDoNotBecomeLinkTargets() {
        for (source in listOf("https://name:credential-canary@example.test/path_*literal*", "https://name@example.test",
            "https://example.test/\u0000x", "https://example.test/\u0085x")) {
            assertNull(MessageFormatting.safeLinkTarget(source))
            assertEquals(source, onlyText(MessageFormatting.parseInline(source)))
        }
    }
    @Test fun linkTargetValidationDoesNotAcceptOtherSchemesOrImplicitOrigins() {
        for (source in listOf("javascript:alert(1)","data:text/html,hi","ftp://example.test","//example.test",
            "http:example.test"," https://example.test","https://example.test/\tpath","https://")) {
            assertNull(MessageFormatting.safeLinkTarget(source))
        }
        assertEquals("https://example.test/", MessageFormatting.safeLinkTarget("HTTPS://EXAMPLE.TEST"))
    }
    @Test fun supportedPrivateAndPlainHttpLinksDoNotAcquireSessionAuthority() {
        assertEquals("http://localhost:8080/path", MessageFormatting.safeLinkTarget("http://localhost:8080/path"))
        assertEquals("https://192.168.1.2/", MessageFormatting.safeLinkTarget("https://192.168.1.2"))
    }
    @Test fun excessiveInputIsPreservedWholeAsLiteral() {
        val source = "> *" + "🎉".repeat(9_000) + "*"
        val inline = MessageFormatting.parseInline(source)
        val message = MessageFormatting.parseMessage(source)
        assertTrue(inline.literalFallback); assertTrue(message.literalFallback)
        assertEquals(source, onlyText(inline)); assertEquals(source, onlyBlockText(message))
        assertNull(MessageFormatting.safeLinkTarget("https://example.test/" + "a".repeat(20_000)))
    }
    @Test fun mixedStylesRecurseWithinTheBrowserDelimiterRules() {
        val source = "*bold _and italic_*"
        val inline = MessageFormatting.parseInline(source)
        assertFalse(inline.literalFallback)
        val strong = inline.nodes.single() as MessageInline.Styled
        assertEquals(MessageInline.Emphasis.STRONG, strong.emphasis)
        assertEquals("bold ", (strong.children.first() as MessageInline.Text).text)
        val emphasis = strong.children.last() as MessageInline.Styled
        assertEquals(MessageInline.Emphasis.EMPHASIS, emphasis.emphasis)
        assertEquals("and italic", (emphasis.children.single() as MessageInline.Text).text)
        assertEquals(jsonInline(inline.nodes), jsonInline((MessageFormatting.parseMessage(source).blocks.single() as MessageBlock.Paragraph).children))
    }
    @Test fun repeatedUnmatchedOpenersHaveABoundedWorkFallback() {
        val source = "*a ".repeat(2_000)
        val inline = MessageFormatting.parseInline(source)
        assertTrue(inline.literalFallback); assertEquals(source, onlyText(inline))
        val message = MessageFormatting.parseMessage(source)
        assertTrue(message.literalFallback); assertEquals(source, onlyBlockText(message))
    }
    @Test fun excessiveNodeCountAlsoPreservesTheOriginalBody() {
        val source = "*a*".repeat(3_000)
        val inline = MessageFormatting.parseInline(source)
        assertTrue(inline.literalFallback); assertEquals(source, onlyText(inline))
        val message = MessageFormatting.parseMessage(source)
        assertTrue(message.literalFallback); assertEquals(source, onlyBlockText(message))
    }
    @Test fun ordinaryMaximumUnicodeBodiesAreNotTruncatedOrRejected() {
        val source = "🎉".repeat(4_000)
        val parsed = MessageFormatting.parseInline(source)
        assertFalse(parsed.literalFallback); assertEquals(source, onlyText(parsed))
        assertEquals(source, onlyBlockText(MessageFormatting.parseMessage(source)))
    }
    @Test fun stateAndBudgetsNeverLeakBetweenMessages() {
        MessageFormatting.parseMessage("*a ".repeat(2_000))
        MessageFormatting.parseMessage("private-prior-canary")
        val next = MessageFormatting.parseMessage("")
        assertFalse(next.literalFallback)
        assertTrue((next.blocks.single() as MessageBlock.Paragraph).children.isEmpty())
        assertTrue(MessageFormatting.parseInline("").nodes.isEmpty())
    }
    @Test fun presentationObjectsNeverGenerateContentOrLinkStringsForLogs() {
        val canary = "private-format-canary"
        val parsed = MessageFormatting.parseMessage("*$canary* _${canary}_ `$canary` https://example.test/$canary\n> $canary\n```\n$canary\n```")
        assertFalse(parsed.toString().contains(canary))
        fun checkNodes(nodes: List<MessageInline>) { nodes.forEach {
            assertFalse(it.toString().contains(canary))
            if (it is MessageInline.Styled) checkNodes(it.children)
        } }
        parsed.blocks.forEach { block ->
            assertFalse(block.toString().contains(canary))
            when (block) { is MessageBlock.Paragraph -> checkNodes(block.children)
                is MessageBlock.Quote -> checkNodes(block.children)
                is MessageBlock.CodeBlock -> assertEquals(canary, block.text) }
        }
        assertFalse(MessageFormatting.parseInline(canary).toString().contains(canary))
    }
    private fun onlyText(value: FormattedInline) = (value.nodes.single() as MessageInline.Text).text
    private fun onlyBlockText(value: FormattedMessage) = ((value.blocks.single() as MessageBlock.Paragraph).children.single() as MessageInline.Text).text
}

private fun jsonInline(nodes: List<MessageInline>): JsonArray = buildJsonArray { nodes.forEach { node -> add(buildJsonObject {
    when (node) {
        is MessageInline.Text -> { put("type","text"); put("text",node.text) }
        is MessageInline.Code -> { put("type","code"); put("text",node.text) }
        is MessageInline.Link -> { put("type","link"); put("href",node.target); put("text",node.text) }
        is MessageInline.Styled -> { put("type",if(node.emphasis==MessageInline.Emphasis.STRONG) "strong" else "em"); put("children",jsonInline(node.children)) }
    }
}) } }
private fun jsonBlocks(blocks: List<MessageBlock>): JsonArray = buildJsonArray { blocks.forEach { block -> add(buildJsonObject {
    when (block) {
        is MessageBlock.Paragraph -> { put("type","paragraph"); put("children",jsonInline(block.children)) }
        is MessageBlock.Quote -> { put("type","quote"); put("children",jsonInline(block.children)) }
        is MessageBlock.CodeBlock -> { put("type","codeblock"); put("text",block.text) }
    }
}) } }
