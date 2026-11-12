package io.github.thiesi.chitchat.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P-216's threshold: stack when the single-row field would be under 9 × the body text size. */
class ComposerStackRuleTest {
    @Test fun thresholdsMatchTheSpecTable() {
        // At density 1, the body text (16sp) is 16px × font scale.
        for ((scale, threshold) in listOf(1f to 144, 1.25f to 180, 1.5f to 216, 2f to 288)) {
            assertFalse("$scale keeps one row at the threshold", composerStacks(threshold, 16f * scale))
            assertTrue("$scale stacks just under it", composerStacks(threshold - 1, 16f * scale))
        }
    }

    @Test fun a360dpPhoneStaysOneRowTo110PercentAndStacksFrom115() {
        val field = 360 - 2 * 16 - 48 - 2 * 48 - 3 * 8 // Padding, Details, Attach, Send and three gaps: 160dp.
        assertFalse(composerStacks(field, 16f * 1.10f))
        assertTrue(composerStacks(field, 16f * 1.15f))
    }
}
