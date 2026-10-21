package io.github.thiesi.chitchat.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageExcerptTest {
    @Test fun shortTextKeepsWhitespaceAndLiteralMarkup() {
        assertEquals("", messageExcerpt(""))
        assertEquals("First\n<b>Second</b>", messageExcerpt("First\n<b>Second</b>"))
    }
    @Test fun aPreviewNeverCutsAnEmojiSurrogatePairInHalf() {
        val excerpt = messageExcerpt("x" + "😀".repeat(200))
        assertEquals("x" + "😀".repeat(159), excerpt)
        assertEquals(160, excerpt.codePointCount(0, excerpt.length))
    }
}
