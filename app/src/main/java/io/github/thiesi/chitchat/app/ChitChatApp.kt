package io.github.thiesi.chitchat.app

import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton

import android.content.res.Configuration
import io.github.thiesi.chitchat.core.theme.lanternSurfaceBorder

import androidx.activity.compose.LocalActivity
import io.github.thiesi.chitchat.core.theme.deviceIsDark
import io.github.thiesi.chitchat.core.theme.effectiveDark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.navigation
import androidx.activity.compose.BackHandler
import io.github.thiesi.chitchat.feature.profile.ProfileViewModel
import io.github.thiesi.chitchat.feature.completion.CompletionViewModel
import io.github.thiesi.chitchat.feature.profile.ProfileDialog
import androidx.navigation.NavType
import androidx.navigation.navArgument
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.server.AddressError
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.AuthScreen
import io.github.thiesi.chitchat.feature.account.AccountScreen
import io.github.thiesi.chitchat.feature.account.AccountViewModel
import io.github.thiesi.chitchat.feature.account.TypingPreferenceViewModel
import io.github.thiesi.chitchat.feature.account.IgnorePersonViewModel
import io.github.thiesi.chitchat.feature.account.TypingPreferenceCard
import io.github.thiesi.chitchat.feature.rooms.RoomsScreen
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.conversation.RoomDraftsDialog
import io.github.thiesi.chitchat.feature.conversation.roomDraftAccount
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.direct.DirectInboxScreen
import io.github.thiesi.chitchat.feature.direct.DirectThreadScreen
import io.github.thiesi.chitchat.feature.direct.directIdentity
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.feature.targets.ReplyTargetHost
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterScreen
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterState
import io.github.thiesi.chitchat.feature.notifications.NotificationLifecycle
import io.github.thiesi.chitchat.feature.search.MessageSearchViewModel
import io.github.thiesi.chitchat.feature.search.MessageSearchScreen
import io.github.thiesi.chitchat.feature.notifications.noticeIdentity
import io.github.thiesi.chitchat.core.targets.TargetSnapshot
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.core.notifications.NotificationDestination
import io.github.thiesi.chitchat.core.notifications.notificationDestination
import io.github.thiesi.chitchat.core.push.pushTapSettled
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.currentStateAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChitChatApp(model: SettingsViewModel, auth: AuthViewModel, account: AccountViewModel, rooms: RoomsViewModel,
    conversations: ConversationViewModel, realtime: RealtimeController? = null,
    directInbox: DirectInboxViewModel? = null, directThread: DirectThreadViewModel? = null,
    typingPreferences: TypingPreferenceViewModel? = null, ignores: IgnorePersonViewModel? = null,
    messageTargets: MessageTargetViewModel? = null,
    notifications: NotificationCenterViewModel? = null,
    search: MessageSearchViewModel? = null,
    profiles: ProfileViewModel? = null,
    completion: CompletionViewModel? = null,
    pushSettings: io.github.thiesi.chitchat.feature.push.NotificationSettingsViewModel? = null,
    pushPhone: io.github.thiesi.chitchat.feature.push.PushPhoneViewModel? = null,
    pushTaps: io.github.thiesi.chitchat.core.push.PendingPushTap? = null,
    onThemeChanged: (Boolean) -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val session = auth.state.collectAsStateWithLifecycle().value
    val displayedSession = session
    val targetState = messageTargets?.state?.collectAsStateWithLifecycle()?.value
    val searchState = search?.state?.collectAsStateWithLifecycle()?.value
    val profileState = profiles?.state?.collectAsStateWithLifecycle()?.value
    val completionState = completion?.state?.collectAsStateWithLifecycle()?.value
    val targetObscured = targetState?.request?.matches(session) == true || profileState?.selection?.matches(session) == true
    LaunchedEffect(searchState?.targetBinding, targetState?.request) {
        val binding = searchState?.targetBinding
        if (binding != null && messageTargets?.state?.value?.request !== binding.request) search.cancelTarget(binding)
    }
    val roomDrafts by conversations.drafts.collectAsStateWithLifecycle()
    val roomDraftCount = roomDrafts.values.count { it.matches(displayedSession) }
    val connection = realtime?.state?.collectAsStateWithLifecycle()?.value
    RealtimeConsumers(realtime, session, rooms, conversations, directInbox, directThread)
    LaunchedEffect(state.hasLoadedSettings, state.settings.server) {
        if (state.hasLoadedSettings) auth.selectServer(state.settings.server)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { auth.foreground() }
    val windowDark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val dark = effectiveDark(state.settings.theme, deviceIsDark(), state.hasLoadedSettings, windowDark)
    LaunchedEffect(dark) { onThemeChanged(dark) }
    val screenStatus = remember { io.github.thiesi.chitchat.feature.status.ScreenStatusHolder() }
    ChitChatTheme(dark = dark, pureBlack = state.settings.pureBlack) { androidx.compose.runtime.CompositionLocalProvider(
        io.github.thiesi.chitchat.feature.status.LocalScreenStatus provides screenStatus) {
        val nav = rememberNavController()
        val activity = LocalActivity.current
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            // A paused room operation can outlive its pane while search is visible. Genuine app
            // background still retires it; configuration recreation retains the original operation.
            val route = nav.currentBackStackEntry?.destination?.route
            if (activity?.isChangingConfigurations != true &&
                route?.startsWith("conversation/") != true && route != NOTIFICATION_ROOM_ROUTE)
                conversations.background()
            // A paused thread upload also survives the inbox/other routes. Genuine background
            // must still cancel it there; an invalid scoped thread has no screen lifecycle owner.
            if (activity?.isChangingConfigurations != true) {
                val actual = nav.currentBackStackEntry
                val args = actual?.arguments
                val current = directThread?.session?.value
                val peer = args?.getLong("peerId", -1) ?: -1
                val thread = route == DIRECT_THREAD_ROUTE && args != null && current != null &&
                    peer > 0 && peer != current.user?.id && matchesDirectScope(current, args.getLong("generation", -1),
                        args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server"))
                if (!thread) directThread?.background()
            }
        }
        val entry = nav.currentBackStackEntryAsState().value
        val entryStarted = entry?.lifecycle?.currentStateAsState()?.value?.isAtLeast(Lifecycle.State.STARTED) == true
        LifecycleEventEffect(Lifecycle.Event.ON_START) { profiles?.foreground(); completion?.foreground() }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            if (activity?.isChangingConfigurations != true) { profiles?.background(); completion?.background(); pushSettings?.retire(); pushPhone?.retire() }
        }
        // Push facts live only while Notifications is the current entry; leaving it (including
        // sign-out, which navigates away) clears them. Configuration change keeps the entry.
        val pushRoute = entry?.destination?.route
        LaunchedEffect(pushSettings, pushPhone, pushRoute) { if (pushRoute != null && pushRoute != "notification_settings") { pushSettings?.retire(); pushPhone?.retire() } }
        LaunchedEffect(completion, entry, entryStarted, completionState?.entryId) {
            val actual=entry
            if (actual!=null && entryStarted && nav.currentBackStackEntry===actual) completion?.navigate(actual.id)
        }
        LaunchedEffect(entry, entryStarted, profileState?.selection) {
            // NavHost must first restore an actual STARTED entry; transient null is not navigation.
            val choice = profileState?.selection
            if (choice != null && entryStarted && entry.id != choice.sourceEntryId)
                profiles.close(choice)
        }
        fun profileOpener(source: NavBackStackEntry, roomId: Long? = null): ((Long) -> Boolean)? = profiles?.let { coordinator ->
            { userId ->
                if (nav.currentBackStackEntry === source && source.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    coordinator.state.value.selection == null && messageTargets?.state?.value?.request == null &&
                    !conversations.state.value.draftsOpen)
                    coordinator.open(displayedSession, userId, roomId, source.id) != null
                else false
            }
        }
        val notificationState = notifications?.state?.collectAsStateWithLifecycle()?.value
            ?.takeIf { notifications.belongsTo(displayedSession) } ?: NotificationCenterState()
        val noticeArgs = entry?.arguments
        val noticeScope = noticeArgs != null && matchesNotificationScope(session, noticeArgs.getLong("generation", -1),
            noticeArgs.getLong("viewer", -1), noticeArgs.getInt("version", -1), noticeArgs.getString("server"))
        val inNotifications = entry?.destination?.route == NOTIFICATIONS_ROUTE && noticeScope
        notifications?.let { NotificationLifecycle(it, displayedSession, inNotifications, entry) }
        val focus = LocalFocusManager.current
        val openDirect: (Long?) -> Unit = { peer ->
            val actual = auth.state.value
            if (actual.directIdentity() == displayedSession.directIdentity()) directRoute(actual, peer)?.let { route ->
                focus.clearFocus()
                nav.navigate(route) { launchSingleTop = true }
            }
        }
        val openRoom: (Long) -> Unit = { id ->
            val actual = auth.state.value
            if (displayedSession.roomDraftAccount() != null && actual.roomDraftAccount() == displayedSession.roomDraftAccount() && id > 0) {
                focus.clearFocus()
                conversations.open(id, interacted = true, expected = displayedSession)
                nav.navigate("conversation/$id") { launchSingleTop = true }
            }
        }
        // Navigation shell (navigation-shell.md). Departure rules stay in the root effect below: a tab switch
        // changes the current entry exactly like leaving a route did, so the same cancellations apply.
        // Only public NavController API: a tab root is a tab route with no visible entry below it (previousBackStackEntry
        // skips graph entries; a pushed screen always has its tab root below it). The current tab is kept in saved
        // state, set by every tab change and confirmed whenever a tab root is current.
        var currentTab by rememberSaveable { mutableStateOf(ShellTab.ROOMS.name) }
        // getBackStackEntry throws IllegalArgumentException when the graph isn't on the back stack (onboarding,
        // after leaving): that means "not in the shell". Nothing else is swallowed.
        fun inShell() = try { nav.getBackStackEntry(SHELL_GRAPH); true } catch (_: IllegalArgumentException) { false }
        fun liveTab(): ShellTab? = if (inShell()) ShellTab.valueOf(currentTab) else null
        val entryTab = tabOf(entry?.destination?.route)
        val tabRoot = entryTab != null && nav.previousBackStackEntry == null
        val tab = if (entry != null && inShell()) (if (tabRoot) entryTab else ShellTab.valueOf(currentTab)) else null
        SideEffect { if (tabRoot && entryTab != null && entryTab.name != currentTab) currentTab = entryTab.name }
        // "TAB=scope key" per saved tab stack (a read-only, bundle-safe list).
        var tabScopes by rememberSaveable { mutableStateOf(listOf<String>()) }
        // Reselect at a tab root scrolls that root to the top (navigation-shell.md); one count per tab.
        var reselects by rememberSaveable { mutableStateOf(listOf(0, 0, 0, 0)) }
        fun savedScope(of: ShellTab) = tabScopes.firstOrNull { it.startsWith("${of.name}=") }?.substringAfter('=')
        fun withScope(of: ShellTab, key: String?) = tabScopes.filterNot { it.startsWith("${of.name}=") } +
            listOfNotNull(key?.let { "${of.name}=$it" })
        fun clearTabs() { ShellTab.entries.forEach { nav.clearBackStack(it.pattern) }; tabScopes = emptyList() }
        fun leaveShell() { clearTabs(); currentTab = ShellTab.ROOMS.name; nav.navigate(HOME_ROUTE) { popUpTo(SHELL_GRAPH) { inclusive = true } } }
        fun roomsRoot() {
            nav.clearBackStack(ROOMS_ROUTE); tabScopes = withScope(ShellTab.ROOMS, null)
            currentTab = ShellTab.ROOMS.name
            nav.navigate(ROOMS_ROUTE) { popUpTo(SHELL_GRAPH); launchSingleTop = true }
        }
        /** Replaces "pop to home": outside the shell, onboarding; signed out, leave it; a stale scope, the Rooms root. */
        fun resetToRoot() {
            val current = liveTab()
            if (current == null) nav.popBackStack(HOME_ROUTE, false)
            else if (auth.state.value.phase != SessionPhase.AUTHENTICATED) leaveShell()
            else { clearTabs(); if (current == ShellTab.ROOMS) nav.popBackStack(ROOMS_ROUTE, false) else roomsRoot() }
        }
        fun selectTab(target: ShellTab, restore: Boolean = true) {
            val actual = auth.state.value
            val current = liveTab() ?: return
            if (actual.phase != SessionPhase.AUTHENTICATED) return
            focus.clearFocus()
            if (current == target) {
                if (nav.currentBackStackEntry?.destination?.route == target.pattern && nav.previousBackStackEntry == null)
                    reselects = reselects.toMutableList().also { it[target.ordinal]++ }
                else nav.popBackStack(target.pattern, false)
                return
            }
            val route = target.route(actual) ?: return
            val key = scopeKey(actual)
            val keep = restore && savedScope(target) == key
            if (!keep) nav.clearBackStack(target.pattern)
            tabScopes = withScope(current, key); tabScopes = withScope(target, null)
            currentTab = target.name
            nav.navigate(route) { popUpTo(SHELL_GRAPH) { saveState = true }; launchSingleTop = true; restoreState = keep }
        }
        /**
         * One router for a notice link, from a center row or a tapped push. The [source] entry must be the
         * current Activity entry: targets load against its ID and the guarded flow re-checks access.
         * Nothing is marked read. Unsupported destinations stay on Activity.
         */
        fun openNoticeLink(source: NavBackStackEntry, link: NotificationDestination) {
            val targets = messageTargets ?: return
            when (link) {
                is NotificationDestination.Room -> {
                    val target = link.messageId?.let { MessageTarget.RoomMessage(link.roomId, it) }
                        ?: link.pingId?.let { MessageTarget.RoomPing(link.roomId, it) }
                    if (target != null) targets.load(displayedSession, target, source.id)
                    else notificationRoomRoute(displayedSession, link.roomId)?.let { route ->
                        focus.clearFocus(); conversations.open(link.roomId, interacted = true, expected = displayedSession)
                        nav.navigate(route) { launchSingleTop = true }
                    }
                }
                is NotificationDestination.Direct -> if (link.messageId != null)
                    targets.load(displayedSession, MessageTarget.DirectMessage(link.peerId, link.messageId), source.id)
                    else openDirect(link.peerId)
                NotificationDestination.DirectInbox -> openDirect(null)
                NotificationDestination.Account -> notificationAccountRoute(displayedSession)?.let { route ->
                    focus.clearFocus(); nav.navigate(route) { launchSingleTop = true }
                }
                NotificationDestination.Registration -> Unit // Explicitly unavailable, never an auth mutation.
            }
        }
        // A tapped push lands on Activity for the signed-in account it was posted for; that entry then
        // routes its link (below). Sign-in first, another account or server, or no server drops it, and
        // it is never replayed after a later sign-in.
        val pendingTap = pushTaps?.state?.collectAsStateWithLifecycle()?.value
        LaunchedEffect(pendingTap, session, state.hasLoadedSettings, state.settings.server, entry, entryStarted, inNotifications) {
            val tap = pendingTap ?: return@LaunchedEffect
            if (tap.matches(session)) {
                if (!inNotifications && entryStarted && nav.currentBackStackEntry === entry) selectTab(ShellTab.ACTIVITY, restore = false)
            } else if (pushTapSettled(session, state.hasLoadedSettings, state.settings.server != null)) pushTaps.take(tap)
        }
        val atHome = entry?.destination?.route.let { it == null || it == HOME_ROUTE }
        LaunchedEffect(entry, session.phase, session.server, session.generation,
            session.user?.id, session.user?.sessionVersion) {
            if (nav.currentBackStackEntry !== entry) return@LaunchedEffect
            // Two graphs: signed in, the shell (Rooms root first); otherwise onboarding, with every cleanup below.
            if (session.phase == SessionPhase.AUTHENTICATED && entry?.destination?.route == HOME_ROUTE) {
                currentTab = ShellTab.ROOMS.name
                nav.navigate(SHELL_GRAPH) { popUpTo(HOME_ROUTE) { inclusive = true } }; return@LaunchedEffect
            }
            val searchArgs = entry?.arguments
            val atSearch = entry?.destination?.route == SEARCH_ROUTE
            if (atSearch && (search == null || searchArgs == null || !matchesSearchScope(session,
                searchArgs.getLong("generation", -1), searchArgs.getLong("viewer", -1),
                searchArgs.getInt("version", -1), searchArgs.getString("server"))))
                resetToRoot()
            else if (entry?.destination?.route != null && !atSearch) search?.state?.value?.entry?.let(search::close)
            if (entry?.destination?.route in setOf(NOTIFICATIONS_ROUTE, NOTIFICATION_ROOM_ROUTE, NOTIFICATION_ACCOUNT_ROUTE) &&
                (!noticeScope || notifications == null))
                resetToRoot()
            val accountRoute = entry?.destination?.route in setOf("account", NOTIFICATION_ACCOUNT_ROUTE)
            if (accountRoute && session.phase != SessionPhase.AUTHENTICATED) {
                resetToRoot()
                account.cancel()
                typingPreferences?.cancel()
            } else if (entry?.destination?.route != null && !accountRoute) {
                account.cancel(); typingPreferences?.cancel()
            }
            if (entry?.destination?.route == "rooms" && session.phase != SessionPhase.AUTHENTICATED) {
                resetToRoot()
                rooms.cancel()
            } else if (entry?.destination?.route != null && entry?.destination?.route != "rooms") rooms.cancel()
            val conversation = entry?.destination?.route?.startsWith("conversation/") == true || entry?.destination?.route == NOTIFICATION_ROOM_ROUTE
            if (entry?.destination?.route != null && (!conversation || session.phase != SessionPhase.AUTHENTICATED))
                ignores?.cancel()
            if (conversation && session.phase != SessionPhase.AUTHENTICATED) {
                resetToRoot()
                conversations.cancel()
            } else if (entry?.destination?.route != null && !conversation) conversations.leavePane()
            val direct = entry?.destination?.route?.startsWith("direct") == true
            val args = entry?.arguments
            val sameScope = args != null && matchesDirectScope(session, args.getLong("generation", -1),
                args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server"))
            val peer = args?.getLong("peerId", -1) ?: -1
            val validPeer = entry?.destination?.route?.startsWith("direct/") != true || peer > 0 && peer != session.user?.id
            if (direct && (!sameScope || !validPeer || directInbox == null || directThread == null)) {
                resetToRoot()
                directInbox?.background(); directThread?.leavePane()
            } else if (entry?.destination?.route != null && !direct) {
                directInbox?.background(); directThread?.leavePane()
            }
            if (session.phase != SessionPhase.AUTHENTICATED && inShell()) leaveShell()
        }
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        // A toolbar can consume the entire keyboard-visible landscape viewport. Keep editing
        // controls usable; system Back dismisses the IME and restores the ordinary navigation bar.
        val conversationRoute = entry?.destination?.route?.let { it.startsWith("direct") || it.startsWith("conversation/") ||
            it == NOTIFICATION_ROOM_ROUTE || it == SEARCH_ROUTE } == true
        val compactConversation = conversationRoute && maxHeight < 400.dp
        val compactKeyboard = imeVisible && maxHeight < 240.dp
        if (session.phase == SessionPhase.AUTHENTICATED && session.updateRequired)
            ServerUpdateScreen(session, state.settings.server?.value, onCheckAgain = auth::refresh, onSignOut = auth::signOut)
        else Scaffold(
            topBar = {
                if (!compactKeyboard) TopAppBar(
                    title = { BarTitle(large = tabRoot, title = if (entry?.destination?.route == NOTIFICATIONS_ROUTE) R.string.notifications_title
                        else if (entry?.destination?.route == SEARCH_ROUTE) R.string.message_search_title
                        else if (entry?.destination?.route?.startsWith("direct") == true) R.string.dm_title
                        else if (entry?.destination?.route?.startsWith("conversation/") == true || entry?.destination?.route == NOTIFICATION_ROOM_ROUTE)
                        R.string.rooms_conversation else when (entry?.destination?.route) {
                        "settings" -> R.string.settings
                        "account", NOTIFICATION_ACCOUNT_ROUTE -> R.string.account
                        "notification_settings" -> R.string.notification_settings_title
                        LICENCES_ROUTE, LICENCE_ROUTE -> R.string.oss_licences_title
                        ROOMS_ROUTE -> R.string.rooms_title
                        YOU_ROUTE -> R.string.nav_you
                        else -> R.string.app_name
                    }) },
                    navigationIcon = {
                        // P-76: pushed destinations get an icon Back; tab roots have none (system Back still works there).
                        if (!atHome && !tabRoot) NavBackButton { nav.popBackStack() }
                    },
                    actions = {
                        if (tabRoot && (tab == ShellTab.ROOMS || tab == ShellTab.DIRECT) && search != null &&
                            searchRoute(displayedSession) != null) TextButton(onClick = {
                            val source = entry
                            if (source != null && nav.currentBackStackEntry === source && matchesSearchScope(auth.state.value,
                                displayedSession.generation, displayedSession.user?.id ?: -1,
                                displayedSession.user?.sessionVersion ?: -1, displayedSession.server?.value))
                                searchRoute(displayedSession)?.let { route -> focus.clearFocus(); nav.navigate(route) { launchSingleTop = true } }
                        }, modifier = Modifier.testTag("message_search_open")) { Text(stringResource(R.string.message_search_title)) }
                        if (atHome && state.hasLoadedSettings) TextButton(onClick = { nav.navigate("settings") { launchSingleTop = true } }) {
                            Text(stringResource(R.string.settings))
                        }
                    },
                )
            },
            bottomBar = {
                // Only on a tab root, and out of layout and semantics while the IME shows (navigation-shell.md).
                if (session.phase == SessionPhase.AUTHENTICATED && tab != null && tabRoot && !imeVisible) {
                    val tabs = ShellTab.entries.filter { when (it) {
                        ShellTab.DIRECT -> directInbox != null && directThread != null && directRoute(displayedSession) != null
                        ShellTab.ACTIVITY -> notifications != null && displayedSession.noticeIdentity() != null
                        else -> true
                    } }
                    val countText = if (session.noticeIdentity() == null || notifications == null) null
                        else notificationState.unreadCount?.let { count -> pluralStringResource(
                            if (notificationState.countStale) R.plurals.notifications_last_count else R.plurals.notifications_count, count, count)
                        } ?: stringResource(R.string.notifications_count_unknown)
                    ShellBar(tabs, tab, notificationState.unreadCount, notificationState.countStale, countText, ::selectTab)
                }
            },
        ) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!compactConversation && session.phase == SessionPhase.AUTHENTICATED && realtime != null && connection?.generation == session.generation) {
                    io.github.thiesi.chitchat.feature.status.StatusSlot(session, connection, realtime::retry, auth::refresh, auth::refresh,
                        syncing = screenStatus.current.syncing || session.busy, stale = screenStatus.current.stale)
                    io.github.thiesi.chitchat.feature.realtime.AnnouncementCard(connection, realtime::dismissBroadcast,
                        roomId = entry?.arguments?.getLong("roomId")?.takeIf { it > 0 })
                } else if (!compactConversation && session.phase == SessionPhase.AUTHENTICATED) {
                    // No current connection snapshot: the session rows still show, so Refresh session stays reachable (PR223 review).
                    io.github.thiesi.chitchat.feature.status.StatusSlot(session, null, {}, auth::refresh, auth::refresh,
                        syncing = screenStatus.current.syncing || session.busy, stale = screenStatus.current.stale, connection = false)
                }
                if (state.loading) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.loading_settings), Modifier.padding(top = 16.dp))
                } else {
                    if (state.storageError) {
                        Text(
                            stringResource(R.string.settings_error),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = model::reload) { Text(stringResource(R.string.retry)) }
                    }
                    if (state.settings.serverError != null) Text(stringResource(R.string.saved_server_invalid),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                    if (state.hasLoadedSettings) NavHost(navController = nav, startDestination = "home", modifier = Modifier.weight(1f).fillMaxWidth()) {
                        composable(HOME_ROUTE) {
                            // A successful save/forget replaces the page, not just its body:
                            // never retain the editor's scrolled offset on the confirmation.
                            key(state.settings.server?.value, session.generation) {
                                ScreenContent {
                                    if (state.settings.server == null) {
                                        if (session.error?.kind == FailureKind.STORAGE || session.storageWarning) AuthScreen(session, auth)
                                        else ServerEditor(state, model, onSaved = {})
                                    } else if (session.phase == SessionPhase.AUTHENTICATED) {
                                        // Entering the shell; the signed-in hub is the You tab.
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                            Text(stringResource(R.string.saved_server), style = MaterialTheme.typography.headlineLarge)
                                            Text(state.settings.server!!.value, style = MaterialTheme.typography.titleMedium)
                                            if (session.updateRequired && session.phase in setOf(SessionPhase.ANONYMOUS, SessionPhase.GUEST))
                                                ServerUpdateNotice(session, state.settings.server?.value) { nav.navigate(SERVER_NEW_ROUTE) }
                                            else {
                                                AuthScreen(session, auth)
                                                LanternOutlinedButton(onClick = { nav.navigate("server") }, enabled = session.canChangeServer) {
                                                    Text(stringResource(R.string.change_server))
                                                }
                                                if (!session.canChangeServer) Text(stringResource(R.string.sign_out_to_change_server))
                                            }
                                            PrivacyCard()
                                        }
                                    }
                                }
                            }
                        }
                        composable("server") {
                            ScreenContent {
                                if (session.canChangeServer) ServerEditor(state, model, onSaved = { nav.popBackStack() })
                                else Text(stringResource(R.string.sign_out_to_change_server))
                            }
                        }
                        composable(SERVER_NEW_ROUTE) { // "Use a different server": step 2 with the field empty.
                            ScreenContent {
                                if (session.canChangeServer) ServerEditor(state, model, onSaved = { nav.popBackStack() }, startEmpty = true)
                                else Text(stringResource(R.string.sign_out_to_change_server))
                            }
                        }
                        composable("settings") {
                            ScreenContent { SettingsScreen(state, model, session.canChangeServer) }
                        }
                        navigation(route = SHELL_GRAPH, startDestination = ROOMS_ROUTE) {
                        composable(YOU_ROUTE) {
                            if (session.phase == SessionPhase.AUTHENTICATED) TabRoot(reselects[ShellTab.YOU.ordinal]) { ScreenContent {
                                YouScreen(session, auth, state.settings.server?.value, pushSettings != null,
                                    onNotifications = { nav.navigate("notification_settings") { launchSingleTop = true } },
                                    onAppearance = { nav.navigate("settings") { launchSingleTop = true } },
                                    onAccount = { nav.navigate("account") { launchSingleTop = true } },
                                    onChangeServer = { nav.navigate("server") },
                                    onLicences = { nav.navigate(LICENCES_ROUTE) { launchSingleTop = true } })
                            } }
                        }
                        composable("account") {
                            if (session.phase == SessionPhase.AUTHENTICATED) ScreenContent {
                                AccountScreen(account, session, auth::refresh)
                                typingPreferences?.let { TypingPreferenceCard(it, session, auth::refresh) }
                            }
                        }
                        composable(LICENCES_ROUTE) {
                            ScreenContent { io.github.thiesi.chitchat.feature.licences.LicencesScreen { index ->
                                nav.navigate("licences/$index") { launchSingleTop = true }
                            } }
                        }
                        composable(LICENCE_ROUTE, arguments = listOf(navArgument("index") { type = NavType.IntType })) { destination ->
                            ScreenContent { io.github.thiesi.chitchat.feature.licences.LicenceScreen(destination.arguments?.getInt("index") ?: -1) }
                        }
                        composable("notification_settings") {
                            if (session.phase == SessionPhase.AUTHENTICATED && pushSettings != null) ScreenContent {
                                io.github.thiesi.chitchat.feature.push.NotificationSettingsScreen(pushSettings, session, pushPhone)
                            }
                        }
                        composable(NOTIFICATION_ACCOUNT_ROUTE, arguments = notificationArguments()) { destination ->
                            val args = destination.arguments
                            if (args != null && matchesNotificationScope(session, args.getLong("generation", -1),
                                args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server"))) ScreenContent {
                                AccountScreen(account, session, auth::refresh)
                                typingPreferences?.let { TypingPreferenceCard(it, session, auth::refresh) }
                            }
                        }
                        composable("rooms") {
                            if (session.phase == SessionPhase.AUTHENTICATED) Column(Modifier.fillMaxSize()) {
                                if (roomDraftCount > 0) TextButton(onClick = { conversations.showDrafts(displayedSession) },
                                    modifier = Modifier.fillMaxWidth().testTag("room_directory_drafts")) {
                                    Text(stringResource(R.string.room_drafts_count, roomDraftCount))
                                }
                                Box(Modifier.weight(1f)) { TabRoot(reselects[ShellTab.ROOMS.ordinal]) { RoomsScreen(rooms, auth::refresh, openRoom) } }
                            }
                        }
                        composable(NOTIFICATIONS_ROUTE, arguments = notificationArguments()) { destination ->
                            val args = destination.arguments
                            if (notifications != null && args != null && matchesNotificationScope(session,
                                args.getLong("generation", -1), args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server"))) {
                                key(session.noticeIdentity()) {
                                    val started = destination.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
                                    LaunchedEffect(pendingTap, started) {
                                        val tap = pendingTap ?: return@LaunchedEffect
                                        val current = auth.state.value
                                        if (started && nav.currentBackStackEntry === destination && tap.matches(current) &&
                                            notifications.belongsTo(displayedSession) && displayedSession.noticeIdentity() == current.noticeIdentity() &&
                                            pushTaps.take(tap)) {
                                            val server = displayedSession.server ?: return@LaunchedEffect
                                            notificationDestination(server, tap.link)?.let { openNoticeLink(destination, it) }
                                        }
                                    }
                                    TabRoot(reselects[ShellTab.ACTIVITY.ordinal]) { NotificationCenterScreen(notifications, displayedSession, auth::refresh,
                                        onOpen = if (messageTargets == null) null else { item, link ->
                                            val current = auth.state.value
                                            if (nav.currentBackStackEntry === destination && notifications.belongsTo(displayedSession) &&
                                                displayedSession.noticeIdentity() == current.noticeIdentity() &&
                                                notifications.state.value.notifications.any { it === item }) openNoticeLink(destination, link)
                                        }) {
                                        nav.currentBackStackEntry === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                                    } }
                                }
                            }
                        }
                        composable(SEARCH_ROUTE, arguments = notificationArguments()) { destination ->
                            val args = destination.arguments
                            if (search != null && args != null && matchesSearchScope(displayedSession,
                                args.getLong("generation", -1), args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server"))) {
                                MessageSearchScreen(search, displayedSession, destination.id, auth::refresh,
                                    onOpen = if (messageTargets == null) null else { source, results, hit ->
                                        val actual = nav.currentBackStackEntry
                                        if (actual === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                            source.navigationEntryId == destination.id && source.matches(auth.state.value) &&
                                            matchesSearchScope(auth.state.value, args.getLong("generation", -1),
                                                args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server"))) {
                                            val choice = search.select(source, results, hit)
                                            if (choice != null) {
                                                val request = messageTargets.load(source.original, choice.target, source.navigationEntryId)
                                                if (request == null) search.cancelSelection(choice)
                                                else if (search.bindTarget(choice, request, messageTargets::cancel) == null) {
                                                    messageTargets.cancel(request); search.cancelSelection(choice)
                                                }
                                            }
                                        }
                                    }) {
                                    nav.currentBackStackEntry === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                                }
                            }
                        }
                        composable("conversation/{roomId}", arguments = listOf(navArgument("roomId") { type = NavType.LongType })) { destination ->
                            val id = destination.arguments?.getLong("roomId") ?: 0L
                            if (session.phase == SessionPhase.AUTHENTICATED && id > 0)
                                ConversationScreen(conversations, id, auth::refresh, ignores, messageTargets, targetObscured,
                                    onProfile = profileOpener(destination, id), completion = completion, completionEntryId = destination.id,
                                    onStopped = { if (nav.currentBackStackEntry === destination) conversations.background()
                                        else conversations.leavePane() }) {
                                    nav.currentBackStackEntry === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                        }
                        composable(NOTIFICATION_ROOM_ROUTE, arguments = notificationArguments() +
                            navArgument("roomId") { type = NavType.LongType; defaultValue = -1L }) { destination ->
                            val args = destination.arguments
                            val id = args?.getLong("roomId", -1) ?: -1
                            if (id > 0 && args != null && matchesNotificationScope(session, args.getLong("generation", -1),
                                args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server")))
                                ConversationScreen(conversations, id, auth::refresh, ignores, messageTargets, targetObscured,
                                    onProfile = profileOpener(destination, id), completion = completion, completionEntryId = destination.id,
                                    onStopped = { if (nav.currentBackStackEntry === destination) conversations.background()
                                        else conversations.leavePane() }) {
                                    nav.currentBackStackEntry === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                        }
                        composable(DIRECT_INBOX_ROUTE, arguments = directArguments()) { destination ->
                            val args = destination.arguments
                            if (directInbox != null && directThread != null && args != null && matchesDirectScope(session,
                                args.getLong("generation", -1), args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server")))
                                TabRoot(reselects[ShellTab.DIRECT.ordinal]) { DirectInboxScreen(directInbox, directThread, auth::refresh, isCurrentEntry = {
                                    nav.currentBackStackEntry === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                                }) { openDirect(it) } }
                        }
                        composable(DIRECT_THREAD_ROUTE, arguments = directArguments(thread = true)) { destination ->
                            val args = destination.arguments
                            val peer = args?.getLong("peerId", -1) ?: -1
                            if (directThread != null && args != null && peer > 0 && peer != session.user?.id && matchesDirectScope(session,
                                args.getLong("generation", -1), args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server")))
                                DirectThreadScreen(directThread, peer, auth::refresh, messageTargets, targetObscured,
                                    onProfile = profileOpener(destination), completion = completion, completionEntryId = destination.id,
                                    onStopped = { if (nav.currentBackStackEntry === destination) directThread.background()
                                        else directThread.leavePane() }) {
                                    nav.currentBackStackEntry === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                        }
                        }
                    }
                }
            }
        }
        }
        // Back at a Direct, Activity or You root goes to the Rooms root; at the Rooms root the system leaves the app.
        BackHandler(enabled = session.phase == SessionPhase.AUTHENTICATED && !session.updateRequired && tabRoot && tab != null &&
            tab != ShellTab.ROOMS) { roomsRoot() }
        RoomDraftsDialog(conversations, session, openRoom)
        val profileChoice = profileState?.selection
        if (profiles != null && profileChoice != null && profileChoice.matches(displayedSession) &&
            !displayedSession.storageWarning && entryStarted && entry?.id == profileChoice.sourceEntryId) {
            val origin = entry
            fun current() = nav.currentBackStackEntry === origin &&
                origin.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                profileChoice.matches(auth.state.value) && profiles.state.value.selection === profileChoice
            ProfileDialog(profileState, !displayedSession.busy && !displayedSession.refreshRequired &&
                !displayedSession.storageWarning,
                onRefresh = { if (current()) profiles.refresh(profileChoice) },
                onClose = { if (current()) profiles.close(profileChoice) })
        }
        if (messageTargets != null && targetState != null) {
            val route = entry?.destination?.route
            val args = entry?.arguments
            val roomId = args?.getLong("roomId")?.takeIf { (route == "conversation/{roomId}" || route == NOTIFICATION_ROOM_ROUTE && noticeScope) && it > 0 }
            val peerId = args?.getLong("peerId")?.takeIf { route == DIRECT_THREAD_ROUTE && it > 0 &&
                matchesDirectScope(session, args.getLong("generation", -1), args.getLong("viewer", -1),
                    args.getInt("version", -1), args.getString("server")) }
            val prepareDestination: (TargetSnapshot) -> Boolean = { snapshot -> when (snapshot) {
                is TargetSnapshot.RoomMessage -> notificationRoomRoute(snapshot.request.original, snapshot.context.room.id)?.let { next ->
                    if (conversations.prepareTarget(snapshot)) { focus.clearFocus(); nav.navigate(next); true } else false
                } == true
                is TargetSnapshot.RoomPing -> notificationRoomRoute(snapshot.request.original, snapshot.context.room.id)?.let { next ->
                    if (conversations.prepareTarget(snapshot)) { focus.clearFocus(); nav.navigate(next); true } else false
                } == true
                is TargetSnapshot.DirectMessage -> directRoute(snapshot.request.original, snapshot.context.peer.id)?.let { next ->
                    if (directThread?.prepareTarget(snapshot) == true) { focus.clearFocus(); nav.navigate(next); true } else false
                } == true
            } }
            ReplyTargetHost(messageTargets, targetState, session, conversations, directThread, entryStarted, roomId, peerId,
                onAccept = { snapshot ->
                    // Effects may outlive their rendered entry. Check the REAL current back-stack entry too.
                    val actual = nav.currentBackStackEntry
                    val actualArgs = actual?.arguments
                    val source = targetState.sourceEntryId
                    if (!snapshot.matches(auth.state.value) || messageTargets.state.value.request !== snapshot.request ||
                        actual?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true) false
                    else if (source != null) {
                        if (actual !== entry || actual.id != source || actualArgs == null) false
                        else when (actual.destination.route) {
                            NOTIFICATIONS_ROUTE -> matchesNotificationScope(auth.state.value, actualArgs.getLong("generation", -1),
                                actualArgs.getLong("viewer", -1), actualArgs.getInt("version", -1), actualArgs.getString("server")) &&
                                prepareDestination(snapshot)
                            SEARCH_ROUTE -> {
                                val binding = search?.state?.value?.targetBinding
                                if (search == null || binding == null || binding.selection.results.attempt.entry.navigationEntryId != source ||
                                    !matchesSearchScope(auth.state.value, actualArgs.getLong("generation", -1),
                                        actualArgs.getLong("viewer", -1), actualArgs.getInt("version", -1), actualArgs.getString("server"))) false
                                else search.adoptTarget(binding, snapshot.request) { prepareDestination(snapshot) }
                            }
                            else -> false
                        }
                    } else when (snapshot) {
                        is TargetSnapshot.RoomMessage -> (actual.destination.route == "conversation/{roomId}" ||
                            actual.destination.route == NOTIFICATION_ROOM_ROUTE && actualArgs != null &&
                                matchesNotificationScope(auth.state.value, actualArgs.getLong("generation", -1), actualArgs.getLong("viewer", -1),
                                    actualArgs.getInt("version", -1), actualArgs.getString("server"))) &&
                            actualArgs?.getLong("roomId") == snapshot.context.room.id && conversations.adoptTarget(snapshot)
                        is TargetSnapshot.DirectMessage -> actual.destination.route == DIRECT_THREAD_ROUTE && actualArgs != null &&
                            actualArgs.getLong("peerId") == snapshot.context.peer.id && matchesDirectScope(auth.state.value,
                                actualArgs.getLong("generation", -1), actualArgs.getLong("viewer", -1),
                                actualArgs.getInt("version", -1), actualArgs.getString("server")) && directThread?.adoptTarget(snapshot) == true
                        else -> false
                    }
                }, onDenied = { request, failure ->
                    val binding = search?.state?.value?.targetBinding
                    if (binding?.request === request) search.targetDenied(binding, request, failure)
                    conversations.targetDenied(request, failure)
                    directThread?.targetDenied(request, failure)
                }, refreshSession = auth::refresh, entryId = entry?.id, actualEntryId = { nav.currentBackStackEntry?.id })
        }
    } }
}

private const val LICENCES_ROUTE = "licences"
private const val SERVER_NEW_ROUTE = "server_new"
private const val LICENCE_ROUTE = "licences/{index}"

/** A tab root: its screen sees the tab's reselect count (scroll to top on reselection). */
@Composable
private fun TabRoot(reselect: Int, content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(io.github.thiesi.chitchat.core.ui.LocalTabReselect provides reselect, content = content)

@Composable
private fun ScreenContent(content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    io.github.thiesi.chitchat.core.ui.OnTabReselect { scroll.animateScrollTo(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) { content() }
    }
}

@Composable
private fun ServerEditor(state: SettingsUiState, model: SettingsViewModel, onSaved: () -> Unit, startEmpty: Boolean = false) {
    var address by rememberSaveable { mutableStateOf(if (startEmpty) "" else state.settings.server?.value.orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submitAddress: () -> Unit = {
        submitted = true
        if (model.saveServer(address) is AddressResult.Valid) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    LaunchedEffect(state.settings.server, state.saving, submitted) {
        val expected = (ServerAddress.parse(address) as? AddressResult.Valid)?.address
        if (submitted && expected != null && !state.saving && !state.storageError && state.settings.server == expected) {
            submitted = false
            onSaved()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; submitted = false; model.clearAddressError() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.server_address)) },
            supportingText = {
                Text(stringResource(when (state.addressError) {
                    AddressError.INVALID -> R.string.invalid_server
                    AddressError.HTTPS -> R.string.https_required
                    AddressError.ORIGIN_ONLY -> R.string.server_origin_only
                    null -> R.string.server_hint
                }))
            },
            isError = state.addressError != null,
            singleLine = true,
            enabled = !state.saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitAddress() }),
         shape = MaterialTheme.shapes.medium)
        PrimaryButton(
            onClick = submitAddress,
            enabled = !state.saving && address.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (state.saving) R.string.saving else R.string.save_server)) }
        Text(stringResource(R.string.preview_limit), style = MaterialTheme.typography.bodySmall)
        PrivacyCard()
    }
}

@Composable
internal fun PrivacyCard() {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, border = lanternSurfaceBorder()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.privacy_body))
        }
    }
}

@Composable
private fun SettingsScreen(state: SettingsUiState, model: SettingsViewModel, canChangeServer: Boolean) {
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(canChangeServer) { if (!canChangeServer) confirmForget = false }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge)
        ThemePreference.entries.forEach { choice ->
            val selected = state.settings.theme == choice
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = selected, enabled = !state.saving, role = Role.RadioButton,
                    onClick = { model.setTheme(choice) },
                ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = !state.saving)
                Text(stringResource(when (choice) {
                    ThemePreference.SYSTEM -> R.string.theme_system
                    ThemePreference.LIGHT -> R.string.theme_light
                    ThemePreference.DARK -> R.string.theme_dark
                }), Modifier.padding(start = 16.dp))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theme_pure_black").toggleable(
            value = state.settings.pureBlack, enabled = !state.saving, role = Role.Switch,
            onValueChange = model::setPureBlack,
        ).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.theme_pure_black))
                Text(stringResource(R.string.theme_pure_black_support), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.settings.pureBlack, onCheckedChange = null, enabled = !state.saving)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.push_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.push_body))
        if (state.settings.server != null) {
            Text(stringResource(R.string.saved_server), style = MaterialTheme.typography.titleLarge)
            Text(state.settings.server.value)
            LanternOutlinedButton(onClick = { confirmForget = true }, enabled = !state.saving && canChangeServer) {
                Text(stringResource(R.string.forget_server))
            }
            if (!canChangeServer) Text(stringResource(R.string.sign_out_to_change_server))
        }
    }
    if (confirmForget && canChangeServer) ForgetServerDialog(onForget = { confirmForget = false; model.forgetServer() },
        onCancel = { confirmForget = false })
}

/** Top-bar titles never break mid-word (A-04). The wordmark is a logotype: one line at the
 * display face's 1.0 size, independent of the font scale. Every other title follows the font
 * scale on one ellipsized line and keeps its full text in semantics as a heading.
 */
@Composable
internal fun BarTitle(@androidx.annotation.StringRes title: Int, large: Boolean = false) {
    val text = stringResource(title)
    if (title == R.string.app_name) {
        val size = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toSp() }
        Text(text, Modifier.testTag("bar_wordmark").semantics { heading(); contentDescription = text },
            style = MaterialTheme.typography.titleLarge.copy(fontSize = size, lineHeight = size * 1.27f),
            maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Visible)
    } else Text(text, Modifier.testTag("bar_title").semantics { heading() }, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = if (large) MaterialTheme.typography.headlineSmall else androidx.compose.material3.LocalTextStyle.current)
}
