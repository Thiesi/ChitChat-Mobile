package io.github.thiesi.chitchat.core.ui

import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Test

class ReadVisibilityTest {
    @Test fun pausedUnfocusedAndObscuredWindowsNeverProvideReadProof() {
        for (state in listOf(Lifecycle.State.CREATED, Lifecycle.State.STARTED, Lifecycle.State.DESTROYED))
            assertFalse(canAcknowledgeVisibleRead(state, true))
        assertFalse(canAcknowledgeVisibleRead(Lifecycle.State.RESUMED, false))
        assertFalse(canAcknowledgeVisibleRead(Lifecycle.State.RESUMED, true, false))
        assertTrue(canAcknowledgeVisibleRead(Lifecycle.State.RESUMED, true))
    }
    @Test fun actualRowBottomCanBeVisibleWithoutFittingAnEntireTallMessage() {
        assertTrue(isReadEdgeVisible(-200, 250, 0, 50))
        assertTrue(isReadEdgeVisible(0, 30, 0, 50))
        assertFalse(isReadEdgeVisible(-200, 251, 0, 50))
        assertFalse(isReadEdgeVisible(-200, 200, 0, 50))
        assertFalse(isReadEdgeVisible(50, 1, 0, 50))
        assertFalse(isReadEdgeVisible(0, 0, 0, 50))
        assertFalse(isReadEdgeVisible(-200, 250, 50, 50))
    }
}
