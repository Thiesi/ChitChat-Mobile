package io.github.thiesi.chitchat.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.server.AddressError
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.AuthScreen
import io.github.thiesi.chitchat.core.network.FailureKind
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChitChatApp(model: SettingsViewModel, auth: AuthViewModel, onThemeChanged: (Boolean) -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val session by auth.state.collectAsStateWithLifecycle()
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
        val entry by nav.currentBackStackEntryAsState()
        val atHome = entry?.destination?.route.let { it == null || it == "home" }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (entry?.destination?.route == "settings") R.string.settings else R.string.app_name)) },
                    navigationIcon = {
                        if (!atHome) TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) }
                    },
                    actions = {
                        if (atHome && state.hasLoadedSettings) TextButton(onClick = { nav.navigate("settings") { launchSingleTop = true } }) {
                            Text(stringResource(R.string.settings))
                        }
                    },
                )
            },
        ) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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
                        composable("home") {
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
                    }
                }
            }
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
