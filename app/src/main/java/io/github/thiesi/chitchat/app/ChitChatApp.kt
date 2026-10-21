package io.github.thiesi.chitchat.app

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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
import io.github.thiesi.chitchat.feature.notifications.noticeIdentity
import io.github.thiesi.chitchat.core.targets.TargetSnapshot
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.core.notifications.NotificationDestination
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import io.github.thiesi.chitchat.feature.realtime.RealtimeStatus
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
    onThemeChanged: (Boolean) -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val session = auth.state.collectAsStateWithLifecycle().value
    val displayedSession = session
    val targetState = messageTargets?.state?.collectAsStateWithLifecycle()?.value
    val targetObscured = targetState?.request?.matches(session) == true
    val roomDrafts by conversations.drafts.collectAsStateWithLifecycle()
    val roomDraftCount = roomDrafts.values.count { it.matches(displayedSession) }
    val connection = realtime?.state?.collectAsStateWithLifecycle()?.value
    RealtimeConsumers(realtime, session, rooms, conversations, directInbox, directThread)
    LaunchedEffect(state.hasLoadedSettings, state.settings.server) {
        if (state.hasLoadedSettings) auth.selectServer(state.settings.server)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { auth.foreground() }
    val dark = when (state.settings.theme) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }
    LaunchedEffect(dark) { onThemeChanged(dark) }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFA7D1C0), surface = Color(0xFF191C1A), background = Color(0xFF191C1A),
    ) else lightColorScheme(
        primary = Color(0xFF32675B), surface = Color(0xFFFFF8EF), background = Color(0xFFFFF8EF),
    )
    MaterialTheme(colorScheme = colors) {
        val nav = rememberNavController()
        val entry = nav.currentBackStackEntryAsState().value
        val entryStarted = entry?.lifecycle?.currentStateAsState()?.value?.isAtLeast(Lifecycle.State.STARTED) == true
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
        val atHome = entry?.destination?.route.let { it == null || it == "home" }
        LaunchedEffect(entry, session.phase, session.server, session.generation,
            session.user?.id, session.user?.sessionVersion) {
            if (nav.currentBackStackEntry !== entry) return@LaunchedEffect
            if (entry?.destination?.route in setOf(NOTIFICATIONS_ROUTE, NOTIFICATION_ROOM_ROUTE, NOTIFICATION_ACCOUNT_ROUTE) &&
                (!noticeScope || notifications == null))
                nav.popBackStack("home", false)
            val accountRoute = entry?.destination?.route in setOf("account", NOTIFICATION_ACCOUNT_ROUTE)
            if (accountRoute && session.phase != SessionPhase.AUTHENTICATED) {
                nav.popBackStack("home", false)
                account.cancel()
                typingPreferences?.cancel()
            } else if (entry?.destination?.route != null && !accountRoute) {
                account.cancel(); typingPreferences?.cancel()
            }
            if (entry?.destination?.route == "rooms" && session.phase != SessionPhase.AUTHENTICATED) {
                nav.popBackStack("home", false)
                rooms.cancel()
            } else if (entry?.destination?.route != null && entry?.destination?.route != "rooms") rooms.cancel()
            val conversation = entry?.destination?.route?.startsWith("conversation/") == true || entry?.destination?.route == NOTIFICATION_ROOM_ROUTE
            if (entry?.destination?.route != null && (!conversation || session.phase != SessionPhase.AUTHENTICATED))
                ignores?.cancel()
            if (conversation && session.phase != SessionPhase.AUTHENTICATED) {
                nav.popBackStack("home", false)
                conversations.cancel()
            } else if (entry?.destination?.route != null && !conversation) conversations.cancel()
            val direct = entry?.destination?.route?.startsWith("direct") == true
            val args = entry?.arguments
            val sameScope = args != null && matchesDirectScope(session, args.getLong("generation", -1),
                args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server"))
            val peer = args?.getLong("peerId", -1) ?: -1
            val validPeer = entry?.destination?.route?.startsWith("direct/") != true || peer > 0 && peer != session.user?.id
            if (direct && (!sameScope || !validPeer || directInbox == null || directThread == null)) {
                nav.popBackStack("home", false)
                directInbox?.background(); directThread?.background()
            } else if (entry?.destination?.route != null && !direct) {
                directInbox?.background(); directThread?.background()
            }
        }
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        // A toolbar can consume the entire keyboard-visible landscape viewport. Keep editing
        // controls usable; system Back dismisses the IME and restores the ordinary navigation bar.
        val conversationRoute = entry?.destination?.route?.let { it.startsWith("direct") || it.startsWith("conversation/") || it == NOTIFICATION_ROOM_ROUTE } == true
        val compactConversation = conversationRoute && maxHeight < 400.dp
        val compactKeyboard = imeVisible && maxHeight < 240.dp
        Scaffold(
            topBar = {
                if (!compactKeyboard) TopAppBar(
                    title = { Text(stringResource(if (entry?.destination?.route == NOTIFICATIONS_ROUTE) R.string.notifications_title
                        else if (entry?.destination?.route?.startsWith("direct") == true) R.string.dm_title
                        else if (entry?.destination?.route?.startsWith("conversation/") == true || entry?.destination?.route == NOTIFICATION_ROOM_ROUTE)
                        R.string.rooms_conversation else when (entry?.destination?.route) {
                        "settings" -> R.string.settings
                        "account", NOTIFICATION_ACCOUNT_ROUTE -> R.string.account
                        "rooms" -> R.string.rooms_title
                        else -> R.string.app_name
                    })) },
                    navigationIcon = {
                        if (!atHome) TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) }
                    },
                    actions = {
                        if (atHome && session.phase == SessionPhase.AUTHENTICATED) TextButton(onClick = { nav.navigate("account") {
                            launchSingleTop = true
                        } }) { Text(stringResource(R.string.account)) }
                        if (atHome && state.hasLoadedSettings) TextButton(onClick = { nav.navigate("settings") { launchSingleTop = true } }) {
                            Text(stringResource(R.string.settings))
                        }
                    },
                )
            },
        ) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!compactConversation && session.phase == SessionPhase.AUTHENTICATED && realtime != null && connection?.generation == session.generation) {
                    RealtimeStatus(connection, realtime::retry, realtime::dismissBroadcast,
                        roomId = entry?.arguments?.getLong("roomId")?.takeIf { it > 0 })
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
                        composable("home") { homeEntry ->
                            // A successful save/forget replaces the page, not just its body:
                            // never retain the editor's scrolled offset on the confirmation.
                            key(state.settings.server?.value, session.generation) {
                                ScreenContent {
                                    if (state.settings.server == null) {
                                        if (session.error?.kind == FailureKind.STORAGE || session.storageWarning) AuthScreen(session, auth)
                                        else ServerEditor(state, model, onSaved = {})
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                            Text(stringResource(R.string.saved_server), style = MaterialTheme.typography.headlineLarge)
                                            Text(state.settings.server!!.value, style = MaterialTheme.typography.titleMedium)
                                            AuthScreen(session, auth)
                                            if (session.phase == SessionPhase.AUTHENTICATED) Button(onClick = {
                                                nav.navigate("rooms") { launchSingleTop = true }
                                            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rooms_title)) }
                                            if (session.phase == SessionPhase.AUTHENTICATED && directInbox != null && directThread != null)
                                                Button(onClick = { openDirect(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dm_title)) }
                                            if (session.noticeIdentity() != null && notifications != null) {
                                                val countText = notificationState.unreadCount?.let { count -> pluralStringResource(
                                                    if (notificationState.countStale) R.plurals.notifications_last_count else R.plurals.notifications_count, count, count)
                                                } ?: stringResource(R.string.notifications_count_unknown)
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Button(onClick = {
                                                        if (nav.currentBackStackEntry === homeEntry && displayedSession.noticeIdentity() == auth.state.value.noticeIdentity())
                                                            notificationRoute(displayedSession)?.let { nav.navigate(it) { launchSingleTop = true } }
                                                    }, modifier = Modifier.fillMaxWidth().testTag("notifications_open").semantics { stateDescription = countText }) {
                                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Text(stringResource(R.string.notifications_title))
                                                            notificationState.unreadCount?.takeIf { it > 0 }?.let { count -> Badge { Text(count.toString()) } }
                                                        }
                                                    }
                                                    Text(countText, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                            Button(onClick = { nav.navigate("server") }, enabled = session.canChangeServer) {
                                                Text(stringResource(R.string.change_server))
                                            }
                                            if (!session.canChangeServer) Text(stringResource(R.string.sign_out_to_change_server))
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
                        composable("settings") {
                            ScreenContent { SettingsScreen(state, model, session.canChangeServer) }
                        }
                        composable("account") {
                            if (session.phase == SessionPhase.AUTHENTICATED) ScreenContent {
                                AccountScreen(account, session, auth::refresh)
                                typingPreferences?.let { TypingPreferenceCard(it, session, auth::refresh) }
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
                                Box(Modifier.weight(1f)) { RoomsScreen(rooms, auth::refresh, openRoom) }
                            }
                        }
                        composable(NOTIFICATIONS_ROUTE, arguments = notificationArguments()) { destination ->
                            val args = destination.arguments
                            if (notifications != null && args != null && matchesNotificationScope(session,
                                args.getLong("generation", -1), args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server"))) {
                                key(session.noticeIdentity()) {
                                    NotificationCenterScreen(notifications, displayedSession, auth::refresh,
                                        onOpen = if (messageTargets == null) null else { item, link ->
                                            val current = auth.state.value
                                            if (nav.currentBackStackEntry === destination && notifications.belongsTo(displayedSession) &&
                                                displayedSession.noticeIdentity() == current.noticeIdentity() &&
                                                notifications.state.value.notifications.any { it === item }) {
                                                when (link) {
                                                    is NotificationDestination.Room -> {
                                                        val target = link.messageId?.let { MessageTarget.RoomMessage(link.roomId, it) }
                                                            ?: link.pingId?.let { MessageTarget.RoomPing(link.roomId, it) }
                                                        if (target != null) messageTargets.load(displayedSession, target, destination.id)
                                                        else notificationRoomRoute(displayedSession, link.roomId)?.let { route ->
                                                            focus.clearFocus(); conversations.open(link.roomId, interacted = true, expected = displayedSession)
                                                            nav.navigate(route) { launchSingleTop = true }
                                                        }
                                                    }
                                                    is NotificationDestination.Direct -> if (link.messageId != null)
                                                        messageTargets.load(displayedSession, MessageTarget.DirectMessage(link.peerId, link.messageId), destination.id)
                                                        else openDirect(link.peerId)
                                                    NotificationDestination.Account -> notificationAccountRoute(displayedSession)?.let { route ->
                                                        focus.clearFocus(); nav.navigate(route) { launchSingleTop = true }
                                                    }
                                                    NotificationDestination.Registration -> Unit // Explicitly unavailable, never an auth mutation.
                                                }
                                            }
                                        }) {
                                        nav.currentBackStackEntry === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                                    }
                                }
                            }
                        }
                        composable("conversation/{roomId}", arguments = listOf(navArgument("roomId") { type = NavType.LongType })) { destination ->
                            val id = destination.arguments?.getLong("roomId") ?: 0L
                            if (session.phase == SessionPhase.AUTHENTICATED && id > 0)
                                ConversationScreen(conversations, id, auth::refresh, ignores, messageTargets, targetObscured) {
                                    nav.currentBackStackEntry === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                        }
                        composable(NOTIFICATION_ROOM_ROUTE, arguments = notificationArguments() +
                            navArgument("roomId") { type = NavType.LongType; defaultValue = -1L }) { destination ->
                            val args = destination.arguments
                            val id = args?.getLong("roomId", -1) ?: -1
                            if (id > 0 && args != null && matchesNotificationScope(session, args.getLong("generation", -1),
                                args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server")))
                                ConversationScreen(conversations, id, auth::refresh, ignores, messageTargets, targetObscured) {
                                    nav.currentBackStackEntry === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                        }
                        composable(DIRECT_INBOX_ROUTE, arguments = directArguments()) { destination ->
                            val args = destination.arguments
                            if (directInbox != null && directThread != null && args != null && matchesDirectScope(session,
                                args.getLong("generation", -1), args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server")))
                                DirectInboxScreen(directInbox, directThread, auth::refresh) { openDirect(it) }
                        }
                        composable(DIRECT_THREAD_ROUTE, arguments = directArguments(thread = true)) { destination ->
                            val args = destination.arguments
                            val peer = args?.getLong("peerId", -1) ?: -1
                            if (directThread != null && args != null && peer > 0 && peer != session.user?.id && matchesDirectScope(session,
                                args.getLong("generation", -1), args.getLong("viewer", -1), args.getInt("version", -1), args.getString("server")))
                                DirectThreadScreen(directThread, peer, auth::refresh, messageTargets, targetObscured) {
                                    nav.currentBackStackEntry === destination && destination.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                        }
                    }
                }
            }
        }
        }
        RoomDraftsDialog(conversations, session, openRoom)
        if (messageTargets != null && targetState != null) {
            val route = entry?.destination?.route
            val args = entry?.arguments
            val roomId = args?.getLong("roomId")?.takeIf { (route == "conversation/{roomId}" || route == NOTIFICATION_ROOM_ROUTE && noticeScope) && it > 0 }
            val peerId = args?.getLong("peerId")?.takeIf { route == DIRECT_THREAD_ROUTE && it > 0 &&
                matchesDirectScope(session, args.getLong("generation", -1), args.getLong("viewer", -1),
                    args.getInt("version", -1), args.getString("server")) }
            ReplyTargetHost(messageTargets, targetState, session, conversations, directThread, entryStarted, roomId, peerId,
                onAccept = { snapshot ->
                    // Effects may outlive their rendered entry. Check the REAL current back-stack entry too.
                    val actual = nav.currentBackStackEntry
                    val actualArgs = actual?.arguments
                    val source = targetState.sourceEntryId
                    if (!snapshot.matches(auth.state.value) || messageTargets.state.value.request !== snapshot.request ||
                        actual?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true) false
                    else if (source != null) {
                        if (actual !== entry || actual.id != source || actual.destination.route != NOTIFICATIONS_ROUTE ||
                            actualArgs == null || !matchesNotificationScope(auth.state.value, actualArgs.getLong("generation", -1),
                                actualArgs.getLong("viewer", -1), actualArgs.getInt("version", -1), actualArgs.getString("server"))) false
                        else when (snapshot) {
                            is TargetSnapshot.RoomMessage -> notificationRoomRoute(snapshot.request.original, snapshot.context.room.id)?.let { next ->
                                if (conversations.prepareTarget(snapshot)) { focus.clearFocus(); nav.navigate(next); true } else false
                            } == true
                            is TargetSnapshot.RoomPing -> notificationRoomRoute(snapshot.request.original, snapshot.context.room.id)?.let { next ->
                                if (conversations.prepareTarget(snapshot)) { focus.clearFocus(); nav.navigate(next); true } else false
                            } == true
                            is TargetSnapshot.DirectMessage -> directRoute(snapshot.request.original, snapshot.context.peer.id)?.let { next ->
                                if (directThread?.prepareTarget(snapshot) == true) { focus.clearFocus(); nav.navigate(next); true } else false
                            } == true
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
                    conversations.targetDenied(request, failure)
                    directThread?.targetDenied(request, failure)
                }, refreshSession = auth::refresh, entryId = entry?.id, actualEntryId = { nav.currentBackStackEntry?.id })
        }
    }
}

@Composable
private fun ScreenContent(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) { content() }
    }
}

@Composable
private fun ServerEditor(state: SettingsUiState, model: SettingsViewModel, onSaved: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(state.settings.server?.value.orEmpty()) }
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
        )
        Button(
            onClick = submitAddress,
            enabled = !state.saving && address.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (state.saving) R.string.saving else R.string.save_server)) }
        Text(stringResource(R.string.preview_limit), style = MaterialTheme.typography.bodySmall)
        PrivacyCard()
    }
}

@Composable
private fun PrivacyCard() {
    Card(Modifier.fillMaxWidth()) {
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
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.push_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.push_body))
        if (state.settings.server != null) {
            Text(stringResource(R.string.saved_server), style = MaterialTheme.typography.titleLarge)
            Text(state.settings.server.value)
            OutlinedButton(onClick = { confirmForget = true }, enabled = !state.saving && canChangeServer) {
                Text(stringResource(R.string.forget_server))
            }
            if (!canChangeServer) Text(stringResource(R.string.sign_out_to_change_server))
        }
    }
    if (confirmForget && canChangeServer) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text(stringResource(R.string.forget_title)) },
        text = { Text(stringResource(R.string.forget_body)) },
        confirmButton = {
            TextButton(onClick = { confirmForget = false; model.forgetServer() }) { Text(stringResource(R.string.forget_server)) }
        },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
