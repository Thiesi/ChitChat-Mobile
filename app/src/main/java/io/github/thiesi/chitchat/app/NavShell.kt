package io.github.thiesi.chitchat.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.Lantern
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.BuildConfig
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton
import io.github.thiesi.chitchat.core.ui.CountBadge
import io.github.thiesi.chitchat.feature.auth.AuthScreen
import io.github.thiesi.chitchat.feature.auth.AuthViewModel

/*
 * Navigation shell (docs/design/navigation-shell.md, N1). Onboarding is the root graph's `home`;
 * every signed-in destination lives in the nested SHELL_GRAPH. A tab root is the tab's route with no visible
 * entry below it; tab switches pop to the shell graph entry, saving the left tab's stack.
 */
internal const val HOME_ROUTE = "home"
internal const val SHELL_GRAPH = "shell"
internal const val ROOMS_ROUTE = "rooms"
internal const val YOU_ROUTE = "you"

internal enum class ShellTab(val pattern: String, val tag: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    ROOMS(ROOMS_ROUTE, "nav_tab_rooms", R.string.rooms_title, R.drawable.ic_nav_rooms),
    DIRECT(DIRECT_INBOX_ROUTE, "nav_tab_direct", R.string.nav_direct, R.drawable.ic_nav_direct),
    ACTIVITY(NOTIFICATIONS_ROUTE, "nav_tab_activity", R.string.nav_activity, R.drawable.ic_nav_activity),
    YOU(YOU_ROUTE, "nav_tab_you", R.string.nav_you, R.drawable.ic_nav_you);

    /** The tab root built from the current session; scoped tabs carry its owner arguments. */
    fun route(session: SessionState): String? = when (this) {
        ROOMS -> ROOMS_ROUTE
        DIRECT -> directRoute(session)
        ACTIVITY -> notificationRoute(session)
        YOU -> YOU_ROUTE
    }
}

/** The tab whose root has this route pattern, if any. */
internal fun tabOf(route: String?): ShellTab? = ShellTab.entries.firstOrNull { it.pattern == route }

/** A saved tab stack is restored only under the scope it was saved in; anything else starts again at its root. */
internal fun scopeKey(session: SessionState): String =
    listOf(session.server?.value, session.generation, session.user?.id, session.user?.sessionVersion).joinToString("|")

/**
 * The scale every tab label shares (presentation lead J1, PR251): 1 while the widest bold label fits its item on one
 * line; otherwise the largest scale that fits, but never below [floorScale] (18sp, 1.5x); below it labels wrap.
 */
internal fun sharedLabelScale(widestAtFull: Float, available: Float, floorScale: Float): Float =
    if (widestAtFull <= available) 1f else (available / widestAtFull).coerceIn(minOf(floorScale, 1f), 1f)

/** Bottom bar: icon and always-visible label per tab; the Activity badge uses the existing summary count only. */
@Composable
internal fun ShellBar(tabs: List<ShellTab>, current: ShellTab, unread: Int?, countStale: Boolean, countText: String?,
    onSelect: (ShellTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val base = MaterialTheme.typography.labelMedium
    val labels = tabs.map { stringResource(it.label) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // AMOLED P-07: in Pure black the bar meets black content with a visible 1dp `line` edge.
    val edge = if (ChitChatTheme.colors === Lantern.amoledBlack) ChitChatTheme.colors.line else null
    NavigationBar(Modifier.drawWithContent {
        drawContent()
        if (edge != null) drawLine(edge, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx())
    }) {
        BoxWithConstraints(Modifier.weight(1f)) {
            // Items take the full width (no M3 label padding); all labels share one size, measured in bold.
            val available = constraints.maxWidth.toFloat() / tabs.size
            val floor = 1.5f / density.fontScale
            val scale = remember(labels, available, density, base) {
                fun widest(s: Float) = labels.maxOf { measurer.measure(it, base.copy(fontWeight = FontWeight.Bold,
                    fontSize = base.fontSize * s), softWrap = false).size.width }.toFloat()
                var s = sharedLabelScale(widest(1f), available, floor)
                while (s > floor && widest(s) > available) s = maxOf(floor, s - 0.02f) // Rounding: never trust linear width.
                s
            }
            // TalkBack: "Rooms, tab, 1 of 4" (the group and its position).
            Row(Modifier.fillMaxWidth().selectableGroup().semantics { collectionInfo = CollectionInfo(1, tabs.size) }) {
                tabs.forEachIndexed { index, tab ->
                    val selected = tab == current
                    val tint = if (selected) colors.onSurface else colors.onSurfaceVariant
                    Column(Modifier.weight(1f).heightIn(min = 80.dp)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                        .semantics { collectionItemInfo = CollectionItemInfo(0, 1, index, 1) }
                        .testTag(tab.tag).then(if (tab == ShellTab.ACTIVITY && countText != null)
                            Modifier.semantics { stateDescription = countText } else Modifier)
                        .padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                        Box(Modifier.size(64.dp, 32.dp).background(if (selected) colors.primary.copy(alpha = 0.18f)
                            else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                            val icon = @Composable { Icon(painterResource(tab.icon), contentDescription = null, tint = tint) }
                            val count = unread?.takeIf { tab == ShellTab.ACTIVITY && it > 0 }
                            if (count != null) BadgedBox(badge = {
                                if (countStale) CountBadge() else CountBadge { Text(if (count > 99) "99+" else count.toString()) }
                            }) { icon() } else icon()
                        }
                        Text(labels[index], style = base.copy(fontSize = base.fontSize * scale), color = tint,
                            textAlign = TextAlign.Center, fontWeight = if (selected) FontWeight.Bold else null)
                    }
                }
            }
        }
    }
}

/**
 * The You hub: the signed-in parts of the old home, `account` and `settings`. Rows open today's
 * screens unchanged; the session section is AuthScreen's authenticated state as before.
 */
@Composable
internal fun YouScreen(session: SessionState, auth: AuthViewModel, server: String?, pushAvailable: Boolean,
    onNotifications: () -> Unit, onAppearance: () -> Unit, onAccount: () -> Unit, onChangeServer: () -> Unit,
    onLicences: () -> Unit, onPolicy: ((io.github.thiesi.chitchat.core.policies.PolicyDocumentName) -> Unit)? = null) {
    @Composable fun Row(text: Int, tag: String, onClick: () -> Unit) =
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(stringResource(text)) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        session.user?.let { user -> ProfileHeader(user.username, user.roles) }
        Column {
            if (pushAvailable) Row(R.string.notification_settings_open, "notification_settings_open", onNotifications)
            Row(R.string.appearance, "you_appearance", onAppearance)
            Row(R.string.you_privacy_security, "you_account", onAccount)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.saved_server), style = MaterialTheme.typography.titleMedium)
            server?.let { Text(it) }
            LanternOutlinedButton(onClick = onChangeServer, enabled = session.canChangeServer) { Text(stringResource(R.string.change_server)) }
            if (!session.canChangeServer) Text(stringResource(R.string.sign_out_to_change_server))
        }
        Column(Modifier.testTag("you_session")) { AuthScreen(session, auth) }
        Column(Modifier.testTag("you_about"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.you_about), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.you_app_version, BuildConfig.VERSION_NAME))
            Row(R.string.oss_licences_open, "licences_open", onLicences)
            // The server's documents, each only when published (play-readiness.md §1); the legal notice is a link only.
            // Nothing new starts while the update state shows (server-update.md), as on Server found.
            if (onPolicy != null && !session.updateRequired) PolicyRows(session, server, onPolicy, "you")
            LegalNoticeRow(session, server)
            io.github.thiesi.chitchat.core.ui.AccessibilityFeedbackLink(session.public?.accessibilityFeedback, Modifier.fillMaxWidth())
        }
        PrivacyCard()
    }
}

/** "{installation}'s legal notice" with the open-external icon, only when published; it opens through the explicit choice. */
@Composable
private fun LegalNoticeRow(session: SessionState, server: String?) {
    val url = session.public?.policies?.legalNotice?.url ?: return
    val installation = session.installationName ?: breakableHost(server?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty())
    var choosing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val title = stringResource(R.string.policy_legal_notice, installation)
    TextButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("you_legal_notice")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false))
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_open_external), contentDescription = null)
        }
    }
    if (choosing) io.github.thiesi.chitchat.core.ui.ExternalLinkChoice(url, title, "you_legal_notice") { choosing = false }
}

/** The global roles the server reports, highest first; anything else (member, unknown) shows no badge. */
private val GLOBAL_ROLES = listOf("super_admin" to R.string.role_super_admin, "admin" to R.string.role_admin,
    "chat_admin" to R.string.role_chat_admin, "global_moderator" to R.string.role_global_moderator)

/** You's header: initial pebble, username and a display-only badge for the highest reported global role. */
@Composable
private fun ProfileHeader(username: String, roles: List<String>) {
    val colors = ChitChatTheme.colors
    Row(Modifier.testTag("you_header"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(colors.raised, CircleShape), contentAlignment = Alignment.Center) {
            Text(username.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = colors.text)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(username, style = MaterialTheme.typography.titleLarge)
            GLOBAL_ROLES.firstOrNull { it.first in roles }?.let { (_, label) ->
                Text(stringResource(label), Modifier.testTag("you_role").background(colors.steward, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 2.dp), color = colors.onSteward, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** P-76: the top bar's Back on pushed destinations, an icon described "Back". */
@Composable
internal fun NavBackButton(onBack: () -> Unit) = IconButton(onClick = onBack, Modifier.testTag("nav_back")) {
    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
}
