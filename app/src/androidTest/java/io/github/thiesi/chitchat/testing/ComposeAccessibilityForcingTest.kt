package io.github.thiesi.chitchat.testing

import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** TL: the Compose 1.11.4 accessibility-flag helper fails loudly, never silently skips. */
class ComposeAccessibilityForcingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A fake owner without the delegate field: the guard throws, naming the field and the Compose version. */
    @Test fun aMissingFieldFailsLoudly() {
        val failure = assertThrows(IllegalStateException::class.java) { ComposeAccessibilityForcing.setOnOwner(Any(), true) }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("composeAccessibilityDelegate") &&
            failure.message.orEmpty().contains("1.11.4"))
    }

    /** A view that isn't an AndroidComposeView: the type check throws. */
    @Test fun aNonComposeViewIsRefused() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertThrows(IllegalStateException::class.java) { ComposeAccessibilityForcing.set(View(context), true) }
        }
    }

    /** A tree without any Compose view: nothing to enable is a failure, not a silent no-op. */
    @Test fun aTreeWithoutComposeFailsLoudly() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertThrows(IllegalStateException::class.java) { ComposeAccessibilityForcing.forceAll(View(context), true) }
        }
    }
}
