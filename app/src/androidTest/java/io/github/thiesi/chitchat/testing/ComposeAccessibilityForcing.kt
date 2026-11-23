package io.github.thiesi.chitchat.testing

import android.view.View
import android.view.ViewGroup

/**
 * Test-only (#323b, TL-approved): turns on Compose's own accessibility-event gate under instrumentation. Tied to
 * **Compose UI 1.11.4**: `AndroidComposeViewAccessibilityDelegateCompat` sends semantics events only when its internal
 * `accessibilityForceEnabledForTesting` flag is set, or when the AccessibilityManager is enabled AND lists an enabled
 * service. On CI the manager is enabled but lists none, even with UiAutomation connected (runs 45200317050 and
 * 45210942147: `managerEnabled=true, enabledServices=0`), so Compose stayed silent. The public `enableAccessibilityChecks`
 * path never sets the flag (checked in the 1.11.4 ui-test jars), so this sets it by reflection on each
 * `AndroidComposeView`'s `composeAccessibilityDelegate` via `setAccessibilityForceEnabledForTesting$ui`.
 *
 * It fails loudly, never silently skips: no Compose view under the root, or a missing field or setter (a Compose upgrade
 * renamed them), throws [IllegalStateException]. Main thread only. The caller resets it in a finally.
 */
object ComposeAccessibilityForcing {
    const val COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView"
    private const val FIELD = "composeAccessibilityDelegate"
    private const val SETTER = "setAccessibilityForceEnabledForTesting\$ui"

    /** Sets the flag on every Compose view under [root]; returns them (for the reset). Throws if there are none. */
    fun forceAll(root: View, enabled: Boolean): List<View> {
        val views = mutableListOf<View>()
        fun walk(view: View) {
            if (view.javaClass.name == COMPOSE_VIEW) views += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        check(views.isNotEmpty()) { "No $COMPOSE_VIEW under ${root.javaClass.name}: can't enable Compose accessibility events" }
        views.forEach { set(it, enabled) }
        return views
    }

    /** Sets the flag on one Compose view; throws if [view] isn't one, or if the 1.11.4 field or setter is missing. */
    fun set(view: View, enabled: Boolean) {
        check(view.javaClass.name == COMPOSE_VIEW) { "${view.javaClass.name} is not $COMPOSE_VIEW" }
        setOnOwner(view, enabled)
    }

    /** The reflection step on an owner of the delegate field (separate so its guard is testable on a fake owner). */
    fun setOnOwner(owner: Any, enabled: Boolean) {
        val field = generateSequence<Class<*>>(owner.javaClass) { it.superclass }
            .firstNotNullOfOrNull { type -> runCatching { type.getDeclaredField(FIELD) }.getOrNull() }
            ?: throw IllegalStateException("Compose UI 1.11.4 field $FIELD missing on ${owner.javaClass.name}: " +
                "the accessibility test flag moved; update ComposeAccessibilityForcing")
        field.isAccessible = true
        val delegate = field.get(owner) ?: throw IllegalStateException("$FIELD is null on ${owner.javaClass.name}")
        val setter = runCatching { delegate.javaClass.getMethod(SETTER, Boolean::class.javaPrimitiveType) }.getOrNull()
            ?: throw IllegalStateException("Compose UI 1.11.4 setter $SETTER missing on ${delegate.javaClass.name}: " +
                "the accessibility test flag moved; update ComposeAccessibilityForcing")
        setter.invoke(delegate, enabled)
    }
}
