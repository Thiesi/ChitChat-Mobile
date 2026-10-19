package io.github.thiesi.chitchat.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.server.AddressError
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val saving: Boolean = false,
    val storageError: Boolean = false,
    val addressError: AddressError? = null,
    val hasLoadedSettings: Boolean = false,
)

class SettingsViewModel(private val store: SettingsStore) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null

    init { reload() }

    fun reload() {
        observation?.cancel()
        // Only the first load replaces content. Retry must keep form/scroll state.
        mutableState.update { it.copy(storageError = false) }
        observation = viewModelScope.launch {
            try {
                store.settings.collect { settings ->
                    mutableState.update { it.copy(loading = false, settings = settings, storageError = false, hasLoadedSettings = true) }
                }
            } catch (_: IOException) {
                mutableState.update { it.copy(loading = false, storageError = true) }
            }
        }
    }

    /** Valid means input accepted, not a successful persistence acknowledgement. */
    fun saveServer(input: String): AddressResult {
        val result = ServerAddress.parse(input)
        when (result) {
            is AddressResult.Invalid -> mutableState.update { it.copy(addressError = result.reason) }
            is AddressResult.Valid -> write { store.saveServer(result.address) }
        }
        return result
    }

    fun clearAddressError() { mutableState.update { it.copy(addressError = null) } }
    fun forgetServer() = write { store.saveServer(null) }
    fun setTheme(theme: ThemePreference) = write { store.saveTheme(theme) }

    private fun write(action: suspend () -> Unit) {
        if (mutableState.value.saving) return
        mutableState.update { it.copy(saving = true, storageError = false, addressError = null) }
        viewModelScope.launch {
            try { action() } catch (_: IOException) {
                mutableState.update { it.copy(storageError = true) }
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }

    companion object {
        fun factory(store: SettingsStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(store) }
        }
    }
}
