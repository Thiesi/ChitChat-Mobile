package io.github.thiesi.chitchat.core.mentions

import org.junit.Test
import org.junit.Assert.*

class ComposerCompletionTest {
    private fun edit(text: String, caret: Int = text.length) = ComposerEdit(text, caret)
    private fun inserted(text: String, caret: Int, name: String) =
        insertMention(edit(text, caret), checkNotNull(mentionToken(edit(text, caret))), name)!!

    @Test fun bareAtAndAsciiCasePunctuationAreRecognizedWithoutNormalizingTheDraft() {
        for (prefix in listOf("", "A", "a_.-9", "A".repeat(32))) {
            val value = edit("Hi @$prefix")
            val token = checkNotNull(mentionToken(value))
            assertEquals(prefix, token.prefix); assertEquals(3, token.start); assertEquals(value.start, token.end)
        }
    }
    @Test fun emailAndAttachedUsernamePunctuationDoNotStartMentionRequests() {
        for (body in listOf("a@b", "a.@b", "a_@b", "a-@b", "@@b", "@" + "a".repeat(33), "@café", "@x/"))
            assertNull(body, mentionToken(edit(body)))
    }
    @Test fun punctuationUnicodeAndWhitespaceBeforeAtPreserveTheirExactOffsets() {
        for (before in listOf("(", "😀", " ", "\n", "\t", ":")) {
            val value=edit(before + "@An")
            assertEquals(before.length, mentionToken(value)!!.start)
            assertEquals(before + "@Ann ", insertMention(value, mentionToken(value)!!, "Ann")!!.text)
        }
    }
    @Test fun replacementInTheMiddlePreservesEverySuffixCharacterAndMovesOnlyTheCaret() {
        val body="😀 hi @AnTAIL\nsecond\u0000line"
        val result=inserted(body, "😀 hi @An".length, "Ann")
        assertEquals("😀 hi @Ann TAIL\nsecond\u0000line", result.text)
        assertEquals("😀 hi @Ann ".length, result.start); assertEquals(result.start, result.end)
    }
    @Test fun aNonCollapsedSelectionInvalidCaretOrActiveCompositionNeverStartsOrAppliesCompletion() {
        for (value in listOf(ComposerEdit("@An",1,3), ComposerEdit("@An",-1), ComposerEdit("@An",4), ComposerEdit("@An",3,3,true))) {
            assertNull(mentionToken(value)); assertNull(NameCompletionCycle.start(value,listOf("Ann")))
            assertNull(insertMention(value,mentionToken(edit("@An"))!!,"Ann"))
        }
    }
    @Test fun anOldTokenWithChangedPrefixOrOffsetsCannotReplaceCurrentInput() {
        val token=mentionToken(edit("@An"))!!
        assertNull(insertMention(edit("@Bo"),token,"Ann")); assertNull(insertMention(edit("x @An"),token,"Ann"))
    }
    @Test fun unsupportedOrBlankNamesAreRejectedWithoutTruncatingTheMessage() {
        val value=edit("@A")
        for (name in listOf("", " ", "A".repeat(81))) assertNull(insertMention(value,mentionToken(value)!!,name))
    }
    @Test fun reservedKeywordsAreLiteralTextWithoutInventedIdsOrSpecialSendActions() {
        assertEquals("@room ",inserted("@ro",3,"room").text)
        assertEquals("@here ",inserted("@h",2,"here").text)
    }
    @Test fun ircPlainInitialNameUsesAddressingPunctuationAndCyclesInCallerOrder() {
        val first=NameCompletionCycle.start(edit("al"),listOf("Alice","Alfred"))!!
        assertEquals("Alice: ",first.output.text)
        val second=first.next(first.output)!!; assertEquals("Alfred: ",second.output.text)
        assertEquals("Alice: ",second.next(second.output)!!.output.text)
    }
    @Test fun ircAtOrNonInitialNamesUseOneSpaceAndPreserveTheOriginalSuffixThroughoutCycling() {
        for ((body,caret,expected) in listOf(Triple("@alTAIL",3,"@Alice TAIL"),Triple("Hi alTAIL",5,"Hi Alice TAIL"))) {
            val first=NameCompletionCycle.start(edit(body,caret),listOf("Alice","Alfred"))!!
            assertEquals(expected,first.output.text)
            val second=first.next(first.output)!!
            assertEquals(expected.replace("Alice","Alfred"),second.output.text)
        }
    }
    @Test fun recentNameOrderIsCaseInsensitivelyDeduplicatedWithoutSorting() {
        val first=NameCompletionCycle.start(edit("al"),listOf("ALfred","alfred","Alice","alice"))!!
        assertEquals("ALfred: ",first.output.text)
        val next=first.next(first.output)!!; assertEquals("Alice: ",next.output.text)
        assertEquals("ALfred: ",next.next(next.output)!!.output.text)
    }
    @Test fun ircRequiresANameAtARealWhitespaceOrStartBoundary() {
        for (body in listOf("", "@", "(al", "hello/al", "x@al", "-al", "_al", "@_al", "al"))
            assertNull(body,NameCompletionCycle.start(edit(body),listOf("Alice","_alice")))
        for (before in listOf(" ","\n","\t"," "," ","﻿"))
            assertEquals(before + "Alice ",NameCompletionCycle.start(edit(before + "al"),listOf("Alice"))!!.output.text)
    }
    @Test fun noMatchingLocalNameLeavesTabAvailableForNormalFocusTraversal() {
        assertNull(NameCompletionCycle.start(edit("xy"),listOf("Alice")))
        assertNull(NameCompletionCycle.start(edit("al"),emptyList()))
    }
    @Test fun aFinalNewlineAfterTheTokenIsNotTheCaretAtTheEndOfTheToken() {
        for (ending in listOf("\n", "\r\n", "\r", " ", " ")) {
            assertNull(mentionToken(edit("@Al"+ending)))
            assertNull(NameCompletionCycle.start(edit("Al"+ending),listOf("Alice")))
        }
    }
    @Test fun anyTextSelectionCaretOrCompositionChangeInvalidatesAnOldCycle() {
        val cycle=NameCompletionCycle.start(edit("al"),listOf("Alice","Alfred"))!!
        for (value in listOf(edit("Alice: x"),edit("Alice: ",2),ComposerEdit("Alice: ",7,6),ComposerEdit("Alice: ",7,7,true)))
            assertNull(cycle.next(value))
    }
    @Test fun veryLongMessagesRetainTheirEntirePrefixSuffixAndTrailingCharacters() {
        val prefix="😀".repeat(2000)+" "; val suffix="\nTAIL\u0000 ".repeat(50)
        val value=edit(prefix+"@A"+suffix,prefix.length+2)
        val result=insertMention(value,mentionToken(value)!!,"Alice")!!
        assertEquals(prefix+"@Alice "+suffix,result.text)
    }
    @Test fun privateDraftAndTokenSnapshotsHaveNoGeneratedContentRepresentation() {
        val value=edit("private @Alice")
        assertFalse(value.toString().contains("private")); assertFalse(mentionToken(value)!!.toString().contains("Alice"))
        assertFalse(NameCompletionCycle.start(edit("pr"),listOf("privateName"))!!.toString().contains("privateName"))
    }
}
