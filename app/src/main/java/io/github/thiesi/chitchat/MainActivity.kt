package io.github.thiesi.chitchat

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thiesi.chitchat.app.ChitChatApp
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.account.AccountViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory((application as ChitChatApplication).settingsRepository),
            )
            val auth: AuthViewModel = viewModel(factory = AuthViewModel.factory((application as ChitChatApplication).sessions))
            val account: AccountViewModel = viewModel(factory = AccountViewModel.factory((application as ChitChatApplication).accounts))
            val rooms: RoomsViewModel = viewModel(factory = RoomsViewModel.factory((application as ChitChatApplication).rooms))
            ChitChatApp(model, auth, account, rooms, onThemeChanged = { dark ->
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            })
        }
    }
}
