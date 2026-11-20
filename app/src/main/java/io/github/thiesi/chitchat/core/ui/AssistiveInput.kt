package io.github.thiesi.chitchat.core.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Whether assistive input is on (received files F6, TL ruling of 2026-11-20): touch exploration, or any enabled
 * accessibility service that takes over input. Read from AccessibilityManager by capability, never by package name, so
 * a service that only reads windows (a password manager's autofill) doesn't count. Any failure reads as assistive, so a
 * screen that hides controls keeps them shown when in doubt.
 */
internal object AssistiveInput {
    /** The capabilities that mean a service takes over input: touch exploration, gestures, key-event filtering. */
    const val INPUT_CAPABILITIES = AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION or
        AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES or
        AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS

    fun takesInput(touchExploration: Boolean, capabilities: List<Int>): Boolean =
        touchExploration || capabilities.any { it and INPUT_CAPABILITIES != 0 }

    /** [read] gives touch exploration and the enabled services' capabilities; a failure or no manager is assistive. */
    fun decide(read: () -> Pair<Boolean, List<Int>>?): Boolean = try {
        val (touch, capabilities) = read() ?: return true
        takesInput(touch, capabilities)
    } catch (_: Exception) { true }

    fun read(manager: AccessibilityManager?): Boolean = decide {
        manager?.let { m ->
            m.isTouchExplorationEnabled to m.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).map { it.capabilities }
        }
    }
}

/** Tests can't turn TalkBack on: they provide the answer here (null reads the system). */
internal val LocalAssistiveInput = compositionLocalOf<Boolean?> { null }

/** The current answer, updated when accessibility state, touch exploration or (API 33+) the enabled services change. */
@Composable
internal fun rememberAssistiveInput(): Boolean {
    val override = LocalAssistiveInput.current
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var assistive by remember(manager) { mutableStateOf(AssistiveInput.read(manager)) }
    DisposableEffect(manager) {
        val update = { assistive = AssistiveInput.read(manager) }
        val state = AccessibilityManager.AccessibilityStateChangeListener { update() }
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { update() }
        val services = if (Build.VERSION.SDK_INT >= 33) AccessibilityManager.AccessibilityServicesStateChangeListener { update() } else null
        if (manager != null) {
            manager.addAccessibilityStateChangeListener(state)
            manager.addTouchExplorationStateChangeListener(touch)
            if (services != null && Build.VERSION.SDK_INT >= 33) manager.addAccessibilityServicesStateChangeListener(context.mainExecutor, services)
        }
        update()
        onDispose {
            if (manager != null) {
                manager.removeAccessibilityStateChangeListener(state)
                manager.removeTouchExplorationStateChangeListener(touch)
                if (services != null && Build.VERSION.SDK_INT >= 33) manager.removeAccessibilityServicesStateChangeListener(services)
            }
        }
    }
    return override ?: assistive
}
