package io.github.thiesi.chitchat.core.typing

import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

class TypingState(val target: TypingTarget? = null, val people: List<TypingPerson> = emptyList(),
    val account: TypingAccount? = null)

/** Every UI callback carries its original account snapshot, never just a reusable numeric target. */
interface TypingController {
    val state: StateFlow<TypingState>
    fun select(expected: SessionState, target: TypingTarget?, viewing: Boolean, publishing: Boolean)
    fun release(expected: SessionState, target: TypingTarget)
    fun input(expected: SessionState, target: TypingTarget, hasContent: Boolean)
    fun sent(expected: SessionState, target: TypingTarget)
}
