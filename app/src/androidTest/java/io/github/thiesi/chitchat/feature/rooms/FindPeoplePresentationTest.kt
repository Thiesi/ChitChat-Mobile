package io.github.thiesi.chitchat.feature.rooms

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.rooms.CreateRoom
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.RoomOperations
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Find your people (O2b) at a real Configuration font scale, light and dark: Start chatting stays on screen without
 * scrolling (pinned below the list), every Join has a 48dp touch target, and captures of the top, a confirmed row, a
 * definite failure and an uncertain one with Check room.
 */
class FindPeoplePresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            val sessions = TestSessions()
            runBlocking { sessions.signIn("Alice", "fixture-password", false) }
            val api = Rooms(sessions.state)
            val model = RoomsViewModel(api)
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                    activity.setContent { ChitChatTheme(dark = dark) { Surface { Box(Modifier.fillMaxSize().testTag("find_people_host")) {
                        FindPeopleScreen(model, "Alice", "chat.example.test", onStartChatting = {})
                    } } } }
                }
                compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy }
                compose.onNodeWithTag("find_people_start").assertIsDisplayed() // Pinned: reachable without scrolling.
                capture("find-people-$label-$name-top")
                val small = api.rooms.map { it.id }.mapNotNull { id ->
                    compose.onNodeWithTag("find_people_join_$id").performScrollTo().assertIsDisplayed()
                    val node = compose.onNodeWithTag("find_people_join_$id").fetchSemanticsNode()
                    val min = with(node.layoutInfo.density) { 48.dp.toPx() } - 0.5f
                    if (node.touchBoundsInRoot.height >= min && node.touchBoundsInRoot.width >= min) null else "join_$id ${node.touchBoundsInRoot}"
                }
                assertTrue("A-floor: every Join has a 48dp target ($label, $name): $small", small.isEmpty())
                compose.onNodeWithTag("find_people_join_1").performScrollTo().performClick()
                compose.waitUntil(5_000) { model.state.value.rooms.first { it.id == 1L }.isMember && !model.state.value.busy }
                compose.onNodeWithTag("find_people_joined_1").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("find_people_start").assertIsDisplayed()
                capture("find-people-$label-$name-joined")
                // A definite refusal, inline on its row (presentation lead J request).
                api.failNext = ApiFailure(FailureKind.HTTP, 403, "minimum_age_not_met")
                compose.onNodeWithTag("find_people_join_2").performScrollTo().performClick()
                compose.waitUntil(5_000) { !model.state.value.busy && model.state.value.joinRefusal != null }
                compose.onNodeWithTag("find_people_failure_2").performScrollTo().assertIsDisplayed()
                capture("find-people-$label-$name-failed")
                // An ambiguous one: "We couldn't confirm whether you joined." with Check room.
                api.failNext = ApiFailure(FailureKind.TIMEOUT)
                compose.onNodeWithTag("find_people_join_3").performScrollTo().performClick()
                compose.waitUntil(5_000) { !model.state.value.busy && model.state.value.mutationUncertain }
                compose.onNodeWithTag("find_people_check_3").performScrollTo().assertIsDisplayed()
                capture("find-people-$label-$name-uncertain")
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-find-people-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag("find_people_host").captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private class Rooms(override val session: StateFlow<SessionState>) : RoomOperations {
        val rooms = mutableListOf(
            room(1, "Lounge", "Say hello and tell us where you're from"),
            room(2, "The very long room name of the community garden and allotment group", "Seeds, swaps and the watering rota"),
            room(3, "Board games", ""),
            room(4, "Help desk", "Questions about this server go here"))
        override suspend fun list(): List<RoomInfo> = rooms.toList()
        override suspend fun detail(roomId: Long) = rooms.first { it.id == roomId }
        @Volatile var failNext: ApiFailure? = null
        override suspend fun join(roomId: Long): RoomInfo {
            failNext?.let { failNext = null; throw it }
            val i = rooms.indexOfFirst { it.id == roomId }
            rooms[i] = room(roomId, rooms[i].name, rooms[i].infoLine, "member"); return rooms[i]
        }
        override suspend fun leave(roomId: Long) = error("Unexpected leave")
        override suspend fun create(input: CreateRoom): RoomInfo = error("Unexpected create")
        companion object {
            fun room(id: Long, name: String, info: String, role: String? = null) =
                RoomInfo(id, "room$id", name, info, "public", 0, 0, 7, role, false, "none")
        }
    }
}
