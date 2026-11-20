package io.github.thiesi.chitchat.core.ui

import android.accessibilityservice.AccessibilityServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Received files F6: which accessibility state keeps the viewer's chrome shown (TL ruling of 2026-11-20). */
class AssistiveInputTest {
    @Test fun theCapabilitySetIsExactlyTouchExplorationGesturesAndKeyFiltering() {
        assertEquals(AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION or
            AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES or
            AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS, AssistiveInput.INPUT_CAPABILITIES)
    }

    @Test fun touchExplorationOnIsAssistive() = assertTrue(AssistiveInput.takesInput(true, emptyList()))

    @Test fun aServiceThatRequestsTouchExplorationIsAssistive() =
        assertTrue(AssistiveInput.takesInput(false, listOf(AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION)))

    @Test fun aServiceThatPerformsGesturesIsAssistive() =
        assertTrue(AssistiveInput.takesInput(false, listOf(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)))

    @Test fun aServiceThatFiltersKeyEventsIsAssistive() =
        assertTrue(AssistiveInput.takesInput(false, listOf(AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS)))

    @Test fun aServiceThatOnlyReadsWindowsIsNot() {
        // A password manager's autofill service: it reads windows but never takes over input.
        assertFalse(AssistiveInput.takesInput(false, listOf(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)))
        assertFalse(AssistiveInput.takesInput(false, emptyList()))
    }

    @Test fun oneInputServiceAmongOthersIsAssistive() = assertTrue(AssistiveInput.takesInput(false,
        listOf(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT, AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)))

    @Test fun anyFailureOrNoManagerReadsAsAssistive() {
        assertTrue(AssistiveInput.decide { throw SecurityException("refused") })
        assertTrue(AssistiveInput.decide { null })
        assertFalse(AssistiveInput.decide { false to listOf(0) })
    }
}
