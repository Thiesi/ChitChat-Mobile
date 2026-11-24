package io.github.thiesi.chitchat

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.content.Intent
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
import io.github.thiesi.chitchat.core.push.PushTap
import io.github.thiesi.chitchat.core.theme.BackgroundNightModeSync
import io.github.thiesi.chitchat.feature.push.PushTapViewModel
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
    private lateinit var pushTaps: PushTapViewModel
    private val nightModeSync=BackgroundNightModeSync()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsModel=ViewModelProvider(this,SettingsViewModel.factory(
            (application as ChitChatApplication).settingsRepository,
            (application as ChitChatApplication).settingsRepository))[SettingsViewModel::class.java]
        pushTaps=ViewModelProvider(this)[PushTapViewModel::class.java]
        // Only a fresh launch carries a tap. A restored activity's intent is old and never replays.
        if (savedInstanceState==null) pushTaps.taps.offer(PushTap.from(intent))
        setContent {
            val model=settingsModel
            val auth: AuthViewModel = viewModel(factory = AuthViewModel.factory((application as ChitChatApplication).sessions))
            val account: AccountViewModel = viewModel(factory = AccountViewModel.factory((application as ChitChatApplication).accounts))
            val reports: io.github.thiesi.chitchat.feature.reports.ReportViewModel = viewModel(
                factory = io.github.thiesi.chitchat.feature.reports.ReportViewModel.factory((application as ChitChatApplication).reports))
            val recoveryKeys: io.github.thiesi.chitchat.feature.account.RecoveryKeyViewModel = viewModel(
                factory = io.github.thiesi.chitchat.feature.account.RecoveryKeyViewModel.factory((application as ChitChatApplication).accounts))
            val receivedImages: io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel = viewModel(
                factory = io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel.factory(application,
                    (application as ChitChatApplication).sessions.state, (application as ChitChatApplication).attachmentDownloads,
                    (application as ChitChatApplication).attachmentMemory))
            // F5: Save, Share and Open, app-scoped like the images; the host below needs this Activity.
            val fileTransfers: io.github.thiesi.chitchat.core.media.FileTransfersViewModel = viewModel(
                factory = io.github.thiesi.chitchat.core.media.FileTransfersViewModel.factory(application,
                    (application as ChitChatApplication).sessions.state, (application as ChitChatApplication).attachmentDownloads,
                    (application as ChitChatApplication).shareCopies))
            val settingsRepository = (application as ChitChatApplication).settingsRepository
            // Activity-scoped, so the open first-use note and the picker's pending Save survive a recreation (#318 review).
            val transfersUi: io.github.thiesi.chitchat.core.ui.FileTransfersUi = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    io.github.thiesi.chitchat.core.ui.FileTransfersUi(fileTransfers.transfers, settingsRepository.shareNoteSeen,
                        settingsRepository::markShareNoteSeen) as T
            })
            val accountDeletion: io.github.thiesi.chitchat.feature.account.AccountDeletionViewModel = viewModel(
                factory = io.github.thiesi.chitchat.feature.account.AccountDeletionViewModel.factory((application as ChitChatApplication).accounts))
            val typing: TypingPreferenceViewModel = viewModel(factory = TypingPreferenceViewModel.factory(
                (application as ChitChatApplication).typingPreferences))
            val ignores: IgnorePersonViewModel = viewModel(factory = IgnorePersonViewModel.factory(
                (application as ChitChatApplication).ignores))
            val rooms: RoomsViewModel = viewModel(factory = RoomsViewModel.factory((application as ChitChatApplication).rooms,
                (application as ChitChatApplication).realtime, (application as ChitChatApplication).roomOwnership,
                (application as ChitChatApplication).accounts))
            val conversations: ConversationViewModel = viewModel(factory = ConversationViewModel.factory((application as ChitChatApplication).conversations,
                (application as ChitChatApplication).realtime, (application as ChitChatApplication).typing,
                (application as ChitChatApplication).messageMutations, (application as ChitChatApplication).messageReactions,
                (application as ChitChatApplication).attachmentMemory, (application as ChitChatApplication).attachmentUploads,
                revalidate = { (application as ChitChatApplication).sessions.refresh() }))
            val directInbox: DirectInboxViewModel = viewModel(factory = DirectInboxViewModel.factory(
                (application as ChitChatApplication).directMessages, (application as ChitChatApplication).realtime))
            val directThread: DirectThreadViewModel = viewModel(factory = DirectThreadViewModel.factory(
                (application as ChitChatApplication).directMessages, (application as ChitChatApplication).realtime,
                (application as ChitChatApplication).typing, (application as ChitChatApplication).messageMutations,
                (application as ChitChatApplication).messageReactions, (application as ChitChatApplication).attachmentMemory,
                (application as ChitChatApplication).attachmentUploads,
                revalidate = { (application as ChitChatApplication).sessions.refresh() }))
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
            val guidelines: io.github.thiesi.chitchat.feature.policies.GuidelinesViewModel = viewModel(
                factory = io.github.thiesi.chitchat.feature.policies.GuidelinesViewModel.factory(
                    (application as ChitChatApplication).rulesOperations, (application as ChitChatApplication).settingsRepository))
            val rulesUpdate: io.github.thiesi.chitchat.feature.policies.RulesUpdateViewModel = viewModel(
                factory = io.github.thiesi.chitchat.feature.policies.RulesUpdateViewModel.factory(
                    (application as ChitChatApplication).rulesOperations, (application as ChitChatApplication).policyReads))
            val policies: io.github.thiesi.chitchat.feature.policies.PolicyDocumentViewModel = viewModel(
                factory = io.github.thiesi.chitchat.feature.policies.PolicyDocumentViewModel.factory((application as ChitChatApplication).policyReads))
            io.github.thiesi.chitchat.core.ui.FileTransfersHost(transfersUi) {
            io.github.thiesi.chitchat.feature.reports.ReportsHost(reports) {
            ChitChatApp(model, auth, account, rooms, conversations, directInbox = directInbox, directThread = directThread, messageTargets = targets,
                realtime = (application as ChitChatApplication).realtime, typingPreferences = typing, ignores = ignores,
                notifications = notifications, search = search, profiles = profiles, completion = completion, pushSettings = pushSettings, pushPhone = pushPhone,
                pushTaps = pushTaps.taps, policies = policies, guidelines = guidelines, recoveryKeys = recoveryKeys, receivedImages = receivedImages, rulesUpdate = rulesUpdate,
                accountDeletion = accountDeletion,
                // Server prefill P1: the process's pending link (from LinkEntryActivity) and its UI state.
                serverLinks = (application as ChitChatApplication).serverLinks,
                linkUi = viewModel<io.github.thiesi.chitchat.app.ServerLinkViewModel>(),
                onThemeChanged = { dark ->
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            })
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushTaps.taps.offer(PushTap.from(intent))
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
