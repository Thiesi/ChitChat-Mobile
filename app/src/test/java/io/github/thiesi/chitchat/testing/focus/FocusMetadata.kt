package io.github.thiesi.chitchat.testing.focus

/** Fixed categories only. Never return titles, component names, tokens, user IDs or raw lines. */
internal enum class FocusOwner { TARGET_HOST, SYSTEM_UI, OTHER_COMPONENT, NO_WINDOW }
internal data class FocusMetadata(val window: FocusOwner?, val app: FocusOwner?,
    val windowFields: Int, val appFields: Int)

private val field = Regex("^\\s*(mCurrentFocus|mFocusedApp)=(.*)$")
private val windowComponent = Regex("Window\\{[0-9a-fA-F]+ u[0-9]+ ([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)\\}")
private val appComponent = Regex("ActivityRecord\\{[0-9a-fA-F]+ u[0-9]+ ([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+) t[0-9]+(?: [^{}]*)?\\}")
private const val TARGET = "de.networkxxiii.chitchat.debug/androidx.activity.ComponentActivity"

/** Missing, malformed, truncated or multiple-display observations are unknown, never unlocked/ready.
 * Parse only the component position in the platform grammar, not a substring in a window title.
 */
internal fun focusMetadata(raw: CharSequence, truncated: Boolean = false): FocusMetadata {
    val windows = mutableListOf<String>()
    val apps = mutableListOf<String>()
    raw.lineSequence().forEach { line ->
        field.matchEntire(line)?.let { match ->
            val list = if (match.groupValues[1] == "mCurrentFocus") windows else apps
            if (list.size < 2) list += match.groupValues[2].trim()
        }
    }
    fun classify(values: List<String>, pattern: Regex): FocusOwner? {
        if (truncated || windows.size > 1 || apps.size > 1 || values.size != 1) return null
        if (values.single() == "null") return FocusOwner.NO_WINDOW
        val component = pattern.matchEntire(values.single())?.groupValues?.get(1) ?: return null
        return when {
            component == TARGET -> FocusOwner.TARGET_HOST
            component.substringBefore('/') == "com.android.systemui" -> FocusOwner.SYSTEM_UI
            else -> FocusOwner.OTHER_COMPONENT
        }
    }
    return FocusMetadata(classify(windows, windowComponent), classify(apps, appComponent), windows.size, apps.size)
}
