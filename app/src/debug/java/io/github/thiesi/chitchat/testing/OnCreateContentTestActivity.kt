package io.github.thiesi.chitchat.testing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/**
 * Debug-only host that sets its content in onCreate, as MainActivity does, so a recreation restores the composition
 * through the platform's own saved-state path (not a test-side setContent after the fact). Instrumented tests only.
 */
class OnCreateContentTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = content ?: return
        setContent { body() }
    }

    companion object {
        /** Set before launching; read in every onCreate, including the one after recreation. */
        @Volatile var content: (@Composable () -> Unit)? = null
    }
}
