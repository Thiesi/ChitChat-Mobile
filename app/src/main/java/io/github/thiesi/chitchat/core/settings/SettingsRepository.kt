package io.github.thiesi.chitchat.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.thiesi.chitchat.core.server.AddressError
import io.github.thiesi.chitchat.core.server.ServerAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ThemePreference { SYSTEM, LIGHT, DARK }

data class AppSettings(val server: ServerAddress? = null, val theme: ThemePreference = ThemePreference.SYSTEM,
    val serverError: AddressError? = null, val pureBlack: Boolean = false)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun saveServer(server: ServerAddress?)
    suspend fun saveTheme(theme: ThemePreference)
    suspend fun savePureBlack(enabled: Boolean)
}

/** Non-secret device preferences only. Sessions and message content never belong in this store. */
class SettingsRepository(context: Context) : SettingsStore, GuidelinesAcceptanceStore {
    private val store = context.applicationContext.settingsStore
    override val settings: Flow<AppSettings> = store.data.map { preferences ->
        decodeSettings(preferences[SERVER], preferences[THEME], preferences.asMap()[PURE_BLACK])
    }
    override suspend fun saveServer(server: ServerAddress?) {
        store.edit { if (server == null) it.remove(SERVER) else it[SERVER] = server.value }
    }
    override suspend fun saveTheme(theme: ThemePreference) { store.edit { it[THEME] = theme.name } }

    override suspend fun savePureBlack(enabled: Boolean) { store.edit { it[PURE_BLACK] = enabled } }

    override val accepted: Flow<Set<GuidelinesAcceptance>> = store.data.map { preferences ->
        preferences[GUIDELINES].orEmpty().mapNotNull(::decodeAcceptance).toSet()
    }
    override suspend fun record(acceptance: GuidelinesAcceptance) {
        store.edit { it[GUIDELINES] = withAcceptance(it[GUIDELINES].orEmpty(), acceptance) }
    }
    override suspend fun forgetServer(origin: String) {
        store.edit { it[GUIDELINES] = withoutServer(it[GUIDELINES].orEmpty(), origin) }
    }

    private companion object {
        val GUIDELINES = stringSetPreferencesKey("guidelines_accepted")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val SERVER = stringPreferencesKey("server")
        val THEME = stringPreferencesKey("theme")
    }
}
