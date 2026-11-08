package io.github.thiesi.chitchat.core.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** Shared window contract: initial post-super setup, then the resolved palette callback. */
internal fun ComponentActivity.configureChatWindow(dark: Boolean? = null) {
    if (dark == null) {
        enableEdgeToEdge()
    } else {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        )
    }
}
