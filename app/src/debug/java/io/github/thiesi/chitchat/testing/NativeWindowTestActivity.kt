package io.github.thiesi.chitchat.testing

import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.thiesi.chitchat.core.ui.configureChatWindow

/** Debug-only host; the window setup has the same owner and post-super order as MainActivity. */
class NativeWindowTestActivity : ComponentActivity() {
    internal var createContent: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureChatWindow()
        try { createContent?.invoke() } finally { createContent = null }
    }
}
