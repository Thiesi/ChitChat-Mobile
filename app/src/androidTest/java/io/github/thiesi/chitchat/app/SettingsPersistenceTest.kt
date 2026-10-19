package io.github.thiesi.chitchat.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.SettingsRepository
import io.github.thiesi.chitchat.core.settings.ThemePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPersistenceTest {
    @Test fun deviceSettingsPersistAcrossRepositoryInstances() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsRepository(context)
        val previous = store.settings.first()
        try {
            val address = (ServerAddress.parse("chat.example.org") as AddressResult.Valid).address
            store.saveServer(address)
            store.saveTheme(ThemePreference.DARK)
            val restored = SettingsRepository(context).settings.first()
            assertEquals(address, restored.server)
            assertEquals(ThemePreference.DARK, restored.theme)
        } finally {
            store.saveServer(previous.server)
            store.saveTheme(previous.theme)
        }
    }
}
