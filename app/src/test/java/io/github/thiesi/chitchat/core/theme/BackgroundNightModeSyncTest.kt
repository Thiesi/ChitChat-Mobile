package io.github.thiesi.chitchat.core.theme

import android.app.UiModeManager
import io.github.thiesi.chitchat.core.settings.ThemePreference
import org.junit.Assert.*
import org.junit.Test

class BackgroundNightModeSyncTest {
    @Test fun startupKeepsThePlatformWindowOnlyUntilTheSavedPreferenceIsKnown() {
        for(windowDark in listOf(false,true)) {
            assertEquals(windowDark,effectiveDark(ThemePreference.SYSTEM,!windowDark,false,windowDark))
            assertEquals(!windowDark,effectiveDark(ThemePreference.SYSTEM,!windowDark,true,windowDark))
        }
        assertTrue(effectiveDark(ThemePreference.DARK,false,true,false))
        assertFalse(effectiveDark(ThemePreference.LIGHT,true,true,true))
    }
    @Test fun systemUsesActualDeviceStateWhileExplicitChoicesRemainExplicit() {
        for(device in listOf(false,true)) {
            assertEquals(device,effectiveDark(ThemePreference.SYSTEM,device))
            assertFalse(effectiveDark(ThemePreference.LIGHT,device))
            assertTrue(effectiveDark(ThemePreference.DARK,device))
        }
    }
    @Test fun configurationStopAndUnloadedSettingsNeverWriteAPlatformOverride() {
        val sync=BackgroundNightModeSync();val writes=mutableListOf<Int>()
        sync.onStopped(true,ThemePreference.DARK,writes::add)
        sync.onStopped(false,null,writes::add)
        assertTrue(writes.isEmpty())
        sync.onStopped(false,ThemePreference.DARK,writes::add)
        assertEquals(listOf(UiModeManager.MODE_NIGHT_YES),writes)
    }
    @Test fun unknownMirrorSyncsOnceAndLaterPreferenceChangesHaveTheCorrectMode() {
        val sync=BackgroundNightModeSync();val writes=mutableListOf<Int>()
        sync.onStopped(false,ThemePreference.SYSTEM,writes::add)
        sync.onStopped(false,ThemePreference.SYSTEM,writes::add)
        sync.onStopped(false,ThemePreference.DARK,writes::add)
        sync.onStopped(false,ThemePreference.LIGHT,writes::add)
        sync.onStopped(false,ThemePreference.SYSTEM,writes::add)
        assertEquals(listOf(UiModeManager.MODE_NIGHT_AUTO,UiModeManager.MODE_NIGHT_YES,
            UiModeManager.MODE_NIGHT_NO,UiModeManager.MODE_NIGHT_AUTO),writes)
    }
    @Test fun unconfirmedSetterIsNeverRecordedAsSuccessfullyMirrored() {
        val sync=BackgroundNightModeSync();var calls=0
        try { sync.onStopped(false,ThemePreference.DARK) { calls++;throw IllegalStateException("fixture") };fail() }
        catch(_: IllegalStateException) { }
        sync.onStopped(false,ThemePreference.DARK) { calls++ }
        assertEquals(2,calls)
    }
}
