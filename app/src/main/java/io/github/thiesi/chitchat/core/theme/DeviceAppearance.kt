package io.github.thiesi.chitchat.core.theme

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.thiesi.chitchat.core.settings.ThemePreference

internal fun effectiveDark(
    choice: ThemePreference,
    deviceDark: Boolean,
    settingsKnown: Boolean=true,
    windowDark: Boolean=deviceDark,
): Boolean = if (!settingsKnown) windowDark else when(choice) {
    ThemePreference.SYSTEM -> deviceDark
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
}

/** App resources may contain yesterday's per-app override; System means the actual device. */
@Composable
internal fun deviceIsDark(): Boolean {
    val context=LocalContext.current.applicationContext
    fun read() = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    var dark by remember(context) { mutableStateOf(read()) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { dark=read() }
    DisposableEffect(context) {
        val receiver=object: BroadcastReceiver() {
            override fun onReceive(context: Context?,intent: Intent?) { dark=read() }
        }
        val filter=IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
        if (Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver,filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return dark
}

/** Only MainActivity.onStop calls this; unknown state syncs once at the next genuine stop. */
internal class BackgroundNightModeSync {
    private var lastMirrored: ThemePreference?=null
    fun onStopped(changingConfigurations: Boolean,choice: ThemePreference?,apply: (Int) -> Unit) {
        if (changingConfigurations || choice==null || choice==lastMirrored) return
        val mode=when(choice) {
            ThemePreference.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemePreference.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemePreference.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        apply(mode)
        lastMirrored=choice
    }
}
