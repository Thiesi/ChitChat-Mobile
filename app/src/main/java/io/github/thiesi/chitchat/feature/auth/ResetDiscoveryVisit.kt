package io.github.thiesi.chitchat.feature.auth

import androidx.compose.ui.semantics.SemanticsPropertyKey
import io.github.thiesi.chitchat.core.session.ResetSupport
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState

/**
 * When one visit of step 4a's discovery row asks (O4b2; #298). Pure, so every interleaving is JVM-tested; the screen keeps
 * it in saveable state, so it survives recomposition and configuration change and ends with the visit.
 *
 * At most one completed discovery per runtime (the session generation) per visit. The visit asks while it can
 * (ANONYMOUS, not busy, not already checking or supported) until an ask has taken effect in this generation: the
 * repository drops an ask silently while the session is busy, so a dropped ask is asked again once the session settles.
 * A 404 or failure completes the discovery and is never retried; a confirmed SUPPORTED is never asked again. A runtime
 * replacement always starts a new visit on the real root (the onboarding page is keyed on the generation).
 */
internal data class ResetDiscoveryVisit(
    /** The generation in which this visit called discovery (recorded before the call: a fixture may answer inside it). */
    val requestedIn: Long? = null,
    /** The generation whose discovery this visit saw take effect (CHECKING) or complete after its own call. */
    val askedIn: Long? = null,
) {
    private val SessionState.completed get() = reset.support == ResetSupport.SUPPORTED || reset.support == ResetSupport.UNSUPPORTED

    /** Folds in an ask that took effect. */
    fun observe(state: SessionState): ResetDiscoveryVisit =
        if (state.reset.support == ResetSupport.CHECKING || (state.completed && requestedIn == state.generation))
            copy(askedIn = state.generation) else this

    fun shouldAsk(state: SessionState): Boolean = state.phase == SessionPhase.ANONYMOUS && !state.busy &&
        state.reset.support != ResetSupport.CHECKING && state.reset.support != ResetSupport.SUPPORTED &&
        askedIn != state.generation

    fun asked(state: SessionState) = copy(requestedIn = state.generation)

    /** Scalars only, for the row's [ResetVisitState] semantics. */
    fun scalars() = "requestedIn=${requestedIn ?: "none"},askedIn=${askedIn ?: "none"}"

    companion object {
        /** Saveable form: two strings, empty for null. */
        fun save(visit: ResetDiscoveryVisit) = arrayListOf(visit.requestedIn?.toString().orEmpty(), visit.askedIn?.toString().orEmpty())
        fun restore(saved: List<String>) = ResetDiscoveryVisit(saved[0].toLongOrNull(), saved[1].toLongOrNull())
    }
}

/**
 * The visit's ask state on the row's node, read only by #298's failure-only test scalars. A custom key is not exposed to
 * accessibility services, so the row stays quiet (O4b2).
 */
internal val ResetVisitState = SemanticsPropertyKey<String>("ResetVisitState")
