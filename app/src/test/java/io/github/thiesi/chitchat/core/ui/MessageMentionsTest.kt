package io.github.thiesi.chitchat.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.thiesi.chitchat.core.conversation.MessageMention
import io.github.thiesi.chitchat.core.direct.DirectMention
import io.github.thiesi.chitchat.core.formatting.MessageBlock
import io.github.thiesi.chitchat.core.formatting.MessageFormatting
import org.junit.Assert.*
import org.junit.Test

class MessageMentionsTest {
    private val personal = SpanStyle(color = Color.Black, background = Color.Yellow)
    private val broadcast = SpanStyle(color = Color.White, background = Color.Blue)
    private fun room(vararg values: MessageMention) = MessageMentions.room(values.toList())
    private fun member(name: String, broadcast: Boolean = false) = MessageMention(7, name, broadcast)
    private fun render(body: String, hints: MessageMentions, prefix: String = ""): AnnotatedString {
        val parsed = MessageFormatting.parseMessage(body)
        val nodes = (parsed.blocks.single() as MessageBlock.Paragraph).children
        return messageInlineText(nodes, Color.Blue, Color.Black, Color.Gray, prefix,
            hints.resolve(parsed), personal, broadcast)
    }
    private fun marks(value: AnnotatedString) = value.getStringAnnotations(MESSAGE_MENTION_ANNOTATION, 0, value.length)

    @Test fun onlyNamesInActualMetadataAreDecorated() {
        val value = render("Hi @Alice, @Mallory", room(member("Alice")))
        val mark = marks(value).single()
        assertEquals("personal", mark.item); assertEquals(3, mark.start); assertEquals(9, mark.end)
        assertEquals("Hi @Alice, @Mallory", value.text)
        assertEquals(personal, value.spanStyles.single().item)
        assertTrue(value.getLinkAnnotations(0, value.length).isEmpty())
    }
    @Test fun missingHintsNeverResolveNamesOrBroadcastsFromSyntax() {
        assertTrue(marks(render("@Alice @room @here", MessageMentions.None)).isEmpty())
        assertTrue(marks(render("@Alice @room @here", room())).isEmpty())
    }
    @Test fun caseMatchingUsesTheSuppliedNameAndAsciiTokenGrammar() {
        val value = render("@ALICE @aLiCe @Älice", room(member("Alice"), member("Älice")))
        assertEquals(listOf("@ALICE", "@aLiCe"), marks(value).map { value.text.substring(it.start, it.end) })
    }
    @Test fun roomBroadcastFlagBelongsToResolvedMembersRatherThanALiteralTokenName() {
        val value = render("@Alice @room @here", room(member("Alice"), member("OtherMember", true)))
        assertEquals(listOf("personal", "broadcast", "broadcast"), marks(value).map { it.item })
        assertEquals(broadcast, value.spanStyles.last().item)
    }
    @Test fun ordinaryRoomHintsDoNotInventBroadcastConsent() {
        val value = render("@Alice @room @here", room(member("Alice")))
        assertEquals(listOf("@Alice"), marks(value).map { value.text.substring(it.start, it.end) })
    }
    @Test fun directHintsHaveNoRoomBroadcastFieldOrBehavior() {
        val hints = MessageMentions.direct(listOf(DirectMention(7, "Alice")))
        val value = render("@Alice @room @here @Other", hints)
        assertEquals(listOf("personal"), marks(value).map { it.item })
        assertEquals("@Alice", value.text.substring(marks(value).single().start, marks(value).single().end))
    }
    @Test fun emphasisBoundariesAreMatchedAfterParsingRatherThanAgainstRawMarkup() {
        val value = render("_Hi @Alice_", room(member("Alice")))
        assertEquals("Hi @Alice", value.text)
        assertEquals(3, marks(value).single().start); assertEquals(9, marks(value).single().end)
        assertTrue(value.spanStyles.any { it.start == 0 && it.end == 9 && it.item.fontStyle == FontStyle.Italic })
    }
    @Test fun literalEmotePrefixCannotGainBodyMentionDecoration() {
        val prefix = "* @Alice "
        val value = render("*@Alice*", room(member("Alice")), prefix)
        assertEquals(prefix + "@Alice", value.text)
        assertEquals(prefix.length, marks(value).single().start)
        assertTrue(value.spanStyles.any { it.start == prefix.length && it.item.fontWeight == FontWeight.Bold })
    }
    @Test fun codeAndUrlsNeverAcquireMentionAnnotations() {
        val source = "@Alice `code @Alice` https://example.test/@Alice"
        val value = render(source, room(member("Alice")))
        assertEquals(1, marks(value).size)
        assertEquals(0, marks(value).single().start)
        val onlyCode = MessageFormatting.parseMessage("```\n@Alice\n```")
        assertSame(MentionHighlights.None, room(member("Alice")).resolve(onlyCode))
        val onlyLink = MessageFormatting.parseMessage("https://example.test/@Alice")
        assertSame(MentionHighlights.None, room(member("Alice")).resolve(onlyLink))
    }
    @Test fun quotedTextCanUseTheSameSnapshotWithoutTouchingCodeBlocks() {
        val parsed = MessageFormatting.parseMessage("> @Alice\n```\n@Alice\n```")
        val hints = room(member("Alice")).resolve(parsed)
        assertEquals(MentionHighlights.Kind.PERSONAL, hints.kind("Alice"))
        val quoted = (parsed.blocks.first() as MessageBlock.Quote).children
        val value = messageInlineText(quoted, Color.Blue, Color.Black, Color.Gray, highlights = hints)
        assertEquals(1, marks(value).size)
    }
    @Test fun editedInNamesAbsentFromTheSnapshotAndRemovedNamesStayPlain() {
        val snapshot = room(member("Alice"))
        assertTrue(marks(render("Edit now mentions @Mallory", snapshot)).isEmpty())
        assertTrue(marks(render("All mentions removed", snapshot)).isEmpty())
        assertNull(snapshot.resolve(MessageFormatting.parseMessage("@Mallory")).kind("Alice"))
    }
    /** Cases executed against the pinned browser renderer (blob64a91b4), not inferred trimming rules. */
    @Test fun exactTokensAndMidwordMentionsFollowThePinnedBrowserContract() {
        val snapshot = room(member("Alice"))
        assertTrue(marks(render("Thanks @Alice.", snapshot)).isEmpty())
        assertTrue(marks(render("@Alice-", snapshot)).isEmpty())
        val distinct = render("@Alice @Alice-", snapshot)
        assertEquals(listOf("@Alice"), marks(distinct).map { distinct.text.substring(it.start, it.end) })
        val fullName = render("@Alice-", room(member("Alice-")))
        assertEquals("@Alice-", fullName.text.substring(marks(fullName).single().start, marks(fullName).single().end))
        val adjacent = render("foo@Alice", snapshot)
        assertEquals(3, marks(adjacent).single().start); assertEquals(9, marks(adjacent).single().end)
    }
    @Test fun anExistingBroadcastRecipientCanAlsoHavePersonalDecorationWithoutNewResolution() {
        val snapshot = room(member("Bob", true))
        val original = render("@Bob @room", snapshot)
        assertEquals(listOf("personal", "broadcast"), marks(original).map { it.item })
        assertEquals(listOf("personal"), marks(render("@Bob", snapshot)).map { it.item })
        assertTrue(marks(render("@Mallory", snapshot)).isEmpty())
    }
    @Test fun complexityAndLengthFallbackStayWholeAndUndecorated() {
        val source = "@Alice " + "*a ".repeat(1_000)
        val parsed = MessageFormatting.parseMessage(source)
        assertTrue(parsed.literalFallback)
        assertSame(MentionHighlights.None, room(member("Alice", true)).resolve(parsed))
        assertEquals(source, render(source, room(member("Alice"))).text)
        assertTrue(marks(render(source, room(member("Alice")))).isEmpty())
        val long = "@Alice " + "x".repeat(20_000)
        assertTrue(marks(render(long, room(member("Alice")))).isEmpty())
    }
    @Test fun utf16OffsetsDoNotSplitUnicodeOrRetainUnmentionedMetadata() {
        val value = render("🎉 @Alice, @a_b @ab", room(member("Alice"), member("a_b"), member("ab"), member("NeverShown")))
        assertEquals(listOf("@Alice", "@a_b"), marks(value).map { value.text.substring(it.start, it.end) })
        assertEquals(3, marks(value).first().start)
        assertNull(room(member("NeverShown")).resolve(MessageFormatting.parseMessage("ordinary")).kind("NeverShown"))
    }
    @Test fun contentBearingPresentationObjectsHaveSafeDebugStrings() {
        val name = "privateMentionCanary"
        val input = room(member(name, true))
        val resolved = input.resolve(MessageFormatting.parseMessage("@$name @room"))
        assertFalse(input.toString().contains(name)); assertFalse(resolved.toString().contains(name))
    }
}
