package io.github.thiesi.chitchat.app

import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun invalidAddressDoesNotChangeStoredServer() = runTest(dispatcher) {
        val store = MemorySettingsStore()
        val model = SettingsViewModel(store)
        advanceUntilIdle()
        model.saveServer("http://example.org")
        advanceUntilIdle()
        assertNotNull(model.state.value.addressError)
        assertEquals(null, store.settings.value.server)
    }
    @Test fun savedSettingsSurviveViewModelRecreationAndCanBeForgotten() = runTest(dispatcher) {
        val store = MemorySettingsStore()
        val first = SettingsViewModel(store)
        advanceUntilIdle()
        first.saveServer("chat.example.org")
        advanceUntilIdle()
        first.setTheme(ThemePreference.DARK)
        advanceUntilIdle()
        val restored = SettingsViewModel(store)
        advanceUntilIdle()
        assertEquals("https://chat.example.org/", restored.state.value.settings.server?.value)
        assertEquals(ThemePreference.DARK, restored.state.value.settings.theme)
        restored.forgetServer()
        advanceUntilIdle()
        assertEquals(null, restored.state.value.settings.server)
    }
    @Test fun storageFailureDoesNotPretendSettingsWereSaved() = runTest(dispatcher) {
        val store = MemorySettingsStore().apply { failWrites = true }
        val model = SettingsViewModel(store)
        advanceUntilIdle()
        model.saveServer("chat.example.org")
        advanceUntilIdle()
        assertTrue(model.state.value.storageError)
        assertFalse(model.state.value.saving)
        assertEquals(null, model.state.value.settings.server)
        store.failWrites = false
        model.saveServer("chat.example.org")
        advanceUntilIdle()
        assertFalse(model.state.value.storageError)
        assertEquals("https://chat.example.org/", model.state.value.settings.server?.value)
    }

    @Test fun retryKeepsAlreadyVisibleContentMounted() = runTest(dispatcher) {
        val model = SettingsViewModel(MemorySettingsStore())
        advanceUntilIdle()
        assertFalse(model.state.value.loading)
        model.reload()
        assertFalse(model.state.value.loading)
        advanceUntilIdle()
        assertFalse(model.state.value.storageError)
    }

    private class MemorySettingsStore : SettingsStore {
        override val settings = MutableStateFlow(AppSettings())
        var failWrites = false
        override suspend fun saveServer(server: ServerAddress?) {
            if (failWrites) throw IOException("Test storage failure")
            settings.value = settings.value.copy(server = server)
        }
        override suspend fun saveTheme(theme: ThemePreference) {
            if (failWrites) throw IOException("Test storage failure")
            settings.value = settings.value.copy(theme = theme)
        }
    }
}
