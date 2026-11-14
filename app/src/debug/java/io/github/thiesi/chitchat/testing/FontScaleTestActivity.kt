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
        // Copy the real configuration and change only the font scale: a bare Configuration() also carries default
        // fields that changed the density (48dp measured as 36dp on CI).
        applyOverrideConfiguration(Configuration(newBase.resources.configuration).apply { fontScale = requestedFontScale })
    }

    companion object {
        /** Set before launching; read once as the activity attaches. */
        @Volatile var requestedFontScale = 1f
    }
}
