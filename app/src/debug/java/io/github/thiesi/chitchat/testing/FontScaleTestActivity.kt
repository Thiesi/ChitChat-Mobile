package io.github.thiesi.chitchat.testing

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity

/**
 * Debug-only presentation host: applies a real [Configuration.fontScale] the way a person's system setting does,
 * so dialog windows (created from this context) inherit it too. Instrumented captures only; never in release.
 */
class FontScaleTestActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(Configuration().apply { fontScale = requestedFontScale })
    }

    companion object {
        /** Set before launching; read once as the activity attaches. */
        @Volatile var requestedFontScale = 1f
    }
}
