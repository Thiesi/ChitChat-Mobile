package io.github.thiesi.chitchat

import android.os.Bundle
import android.os.Build
import android.app.UiModeManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import io.github.thiesi.chitchat.core.theme.BackgroundNightModeSync
import io.github.thiesi.chitchat.app.ChitChatApp
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.account.AccountViewModel
import io.github.thiesi.chitchat.feature.account.TypingPreferenceViewModel
import io.github.thiesi.chitchat.feature.account.IgnorePersonViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.search.MessageSearchViewModel
import io.github.thiesi.chitchat.feature.profile.ProfileViewModel
import io.github.thiesi.chitchat.feature.completion.CompletionViewModel

class MainActivity : ComponentActivity() {
    private lateinit var settingsModel: SettingsViewModel
    private val nightModeSync=BackgroundNightModeSync()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsModel=ViewModelProvider(this,SettingsViewModel.factory(
            (application as ChitChatApplication).settingsRepository))[SettingsViewModel::class.java]
        setContent {
            val model=settingsModel
            val auth: AuthViewModel = viewModel(factory = AuthViewModel.factory((application as ChitChatApplication).sessions))
            val account: AccountViewModel = viewModel(factory = AccountViewModel.factory((application as ChitChatApplication).accounts))
            val typing: TypingPreferenceViewModel = viewModel(factory = TypingPreferenceViewModel.factory(
                (application as ChitChatApplication).typingPreferences))
            val ignores: IgnorePersonViewModel = viewModel(factory = IgnorePersonViewModel.factory(
                (application as ChitChatApplication).ignores))
            val rooms: RoomsViewModel = viewModel(factory = RoomsViewModel.factory((application as ChitChatApplication).rooms,
                (application as ChitChatApplication).realtime))
            val conversations: ConversationViewModel = viewModel(factory = ConversationViewModel.factory((application as ChitChatApplication).conversations,
                (application as ChitChatApplication).realtime, (application as ChitChatApplication).typing,
                (application as ChitChatApplication).messageMutations, (application as ChitChatApplication).messageReactions,
                (application as ChitChatApplication).attachmentMemory, (application as ChitChatApplication).attachmentUploads))
            val directInbox: DirectInboxViewModel = viewModel(factory = DirectInboxViewModel.factory(
                (application as ChitChatApplication).directMessages, (application as ChitChatApplication).realtime))
            val directThread: DirectThreadViewModel = viewModel(factory = DirectThreadViewModel.factory(
                (application as ChitChatApplication).directMessages, (application as ChitChatApplication).realtime,
                (application as ChitChatApplication).typing, (application as ChitChatApplication).messageMutations,
                (application as ChitChatApplication).messageReactions, (application as ChitChatApplication).attachmentMemory,
                (application as ChitChatApplication).attachmentUploads))
            val targets: MessageTargetViewModel = viewModel(factory = MessageTargetViewModel.factory(
                (application as ChitChatApplication).messageTargets))
            val notifications: NotificationCenterViewModel = viewModel(factory = NotificationCenterViewModel.factory(
                (application as ChitChatApplication).notifications))
            val search: MessageSearchViewModel = viewModel(factory = MessageSearchViewModel.factory(
                (application as ChitChatApplication).messageSearch))
            val profiles: ProfileViewModel = viewModel(factory = ProfileViewModel.factory(
                (application as ChitChatApplication).profiles))
            val completion: CompletionViewModel = viewModel(factory = CompletionViewModel.factory(
                (application as ChitChatApplication).roomMentions))
            val pushSettings: io.github.thiesi.chitchat.feature.push.NotificationSettingsViewModel = viewModel(
                factory = io.github.thiesi.chitchat.feature.push.NotificationSettingsViewModel.factory((application as ChitChatApplication).push))
            val pushPhone: io.github.thiesi.chitchat.feature.push.PushPhoneViewModel = viewModel(
                factory = io.github.thiesi.chitchat.feature.push.PushPhoneViewModel.factory((application as ChitChatApplication).pushRegistrar,
                    (application as ChitChatApplication).pushDistributors) { direct ->
                    io.github.thiesi.chitchat.core.push.PushChannels.create(applicationContext, direct)
                })
            ChitChatApp(model, auth, account, rooms, conversations, directInbox = directInbox, directThread = directThread, messageTargets = targets,
                realtime = (application as ChitChatApplication).realtime, typingPreferences = typing, ignores = ignores,
                notifications = notifications, search = search, profiles = profiles, completion = completion, pushSettings = pushSettings, pushPhone = pushPhone,
                onThemeChanged = { dark ->
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            })
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT>=31 && ::settingsModel.isInitialized) {
            val manager=getSystemService(UiModeManager::class.java) ?: return
            val state=settingsModel.state.value
            nightModeSync.onStopped(isChangingConfigurations,state.settings.theme.takeIf { state.hasLoadedSettings },
                manager::setApplicationNightMode)
        }
    }
}
