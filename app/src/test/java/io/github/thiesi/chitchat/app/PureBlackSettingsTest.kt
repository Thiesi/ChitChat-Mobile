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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PureBlackSettingsTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun start() { Dispatchers.setMain(dispatcher) }
    @After fun finish() { Dispatchers.resetMain() }
    private class Store: SettingsStore {
        override val settings=MutableStateFlow(AppSettings())
        var fail=false
        override suspend fun saveServer(server: ServerAddress?) { settings.value=settings.value.copy(server=server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value=settings.value.copy(theme=theme) }
        override suspend fun savePureBlack(enabled: Boolean) {
            if(fail) throw IOException("fixture write failure")
            settings.value=settings.value.copy(pureBlack=enabled)
        }
    }
    @Test fun pureBlackIsIndependentOfTheExistingThemeChoiceAndSurvivesVmRecreation()=runTest(dispatcher) {
        val store=Store();val first=SettingsViewModel(store);advanceUntilIdle()
        first.setPureBlack(true);advanceUntilIdle()
        first.setTheme(ThemePreference.LIGHT);advanceUntilIdle()
        val restored=SettingsViewModel(store);advanceUntilIdle()
        assertTrue(restored.state.value.settings.pureBlack)
        assertEquals(ThemePreference.LIGHT,restored.state.value.settings.theme)
        restored.setTheme(ThemePreference.SYSTEM);advanceUntilIdle()
        assertTrue(restored.state.value.settings.pureBlack)
    }
    @Test fun aFailedPureBlackWriteKeepsTheLastConfirmedChoiceAndReleasesBusy()=runTest(dispatcher) {
        val store=Store();val model=SettingsViewModel(store);advanceUntilIdle()
        store.fail=true;model.setPureBlack(true);advanceUntilIdle()
        assertFalse(model.state.value.settings.pureBlack)
        assertTrue(model.state.value.storageError);assertFalse(model.state.value.saving)
        store.fail=false;model.setPureBlack(true);advanceUntilIdle()
        assertTrue(model.state.value.settings.pureBlack);assertFalse(model.state.value.storageError)
    }
}
