package io.github.thiesi.chitchat.feature.push

import androidx.lifecycle.ViewModel
import io.github.thiesi.chitchat.core.push.PendingPushTap

/** Keeps a tap that hasn't been routed yet across configuration changes, and nowhere else. */
class PushTapViewModel : ViewModel() {
    val taps = PendingPushTap()
}
