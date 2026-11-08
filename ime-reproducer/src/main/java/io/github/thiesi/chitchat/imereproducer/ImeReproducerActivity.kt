package io.github.thiesi.chitchat.imereproducer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Standalone empty editor. No CCM Application, navigation, network or account state. */
class ImeReproducerActivity : ComponentActivity() {
    @Volatile var imeBottom = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { imeBottom = bottom }
            MaterialTheme {
                var value by remember { mutableStateOf("") }
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                    OutlinedTextField(value, { value = it }, label = { Text("Reproducer input") },
                        modifier = Modifier.fillMaxWidth().testTag("reproducer_input"))
                }
            }
        }
    }
}
