package io.github.thiesi.chitchat.testing

import android.app.Activity

/**
 * Debug-only stand-in for another app opening a chitchat://connect link (a messenger or browser): its own task affinity,
 * so a VIEW it starts without NEW_TASK begins inside its task, as a real caller's would. Instrumented tests only.
 */
class ForeignTaskCallerActivity : Activity()
