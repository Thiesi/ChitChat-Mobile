package io.github.thiesi.chitchat.core.realtime

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRealtimeRuntimeTest {
    @Test fun foregroundRegistrationIsSingleOwnedAndStopsWithoutStartingAnyHttp() {
        val foreground = mutableListOf<Boolean>()
        val availability = mutableListOf<Boolean>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle get() = registry
            }
            val runtime = AndroidRealtimeRuntime(instrumentation.targetContext, owner.lifecycle,
                foreground::add, availability::add)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals(listOf(true), foreground)
            assertTrue(availability.isNotEmpty()) // Real Android network observation; not server reachability.
            owner.registry.currentState = Lifecycle.State.RESUMED
            owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals(listOf(true), foreground)
            owner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(listOf(true, false), foreground)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals(listOf(true, false, true), foreground)
            runtime.close()
            assertEquals(listOf(true, false, true, false), foreground)
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals(listOf(true, false, true, false), foreground)
        }
    }
}
