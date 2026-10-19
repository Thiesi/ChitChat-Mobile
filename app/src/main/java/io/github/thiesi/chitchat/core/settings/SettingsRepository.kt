package io.github.thiesi.chitchat.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.thiesi.chitchat.core.server.AddressError
import io.github.thiesi.chitchat.core.server.ServerAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ThemePreference { SYSTEM, LIGHT, DARK }

data class AppSettings(val server: ServerAddress? = null, val theme: ThemePreference = ThemePreference.SYSTEM,
    val serverError: AddressError? = null)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun saveServer(server: ServerAddress?)
    suspend fun saveTheme(theme: ThemePreference)
}

/** Non-secret device preferences only. Sessions and message content never belong in this store. */
class SettingsRepository(context: Context) : SettingsStore {
    private val store = context.applicationContext.settingsStore
    override val settings: Flow<AppSettings> = store.data.map { preferences ->
        decodeSettings(preferences[SERVER], preferences[THEME])
    }
    override suspend fun saveServer(server: ServerAddress?) {
        store.edit { if (server == null) it.remove(SERVER) else it[SERVER] = server.value }
    }
    override suspend fun saveTheme(theme: ThemePreference) { store.edit { it[THEME] = theme.name } }

    private companion object {
        val SERVER = stringPreferencesKey("server")
        val THEME = stringPreferencesKey("theme")
    }
}
