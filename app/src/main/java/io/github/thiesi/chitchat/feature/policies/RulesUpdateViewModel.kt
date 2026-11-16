package io.github.thiesi.chitchat.feature.policies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.policies.PolicyDocument
import io.github.thiesi.chitchat.core.policies.PolicyDocumentName
import io.github.thiesi.chitchat.core.policies.PolicyReads
import io.github.thiesi.chitchat.core.policies.RulesAcceptResult
import io.github.thiesi.chitchat.core.policies.RulesOperations
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which required rules a decision belongs to: the account, its session generation, the version and the latest refusal. */
data class RulesUpdateScope(val account: Long, val generation: Long, val version: Int, val refusal: Long)

/** The re-acceptance dialog's memory-only state (play-readiness.md §1, "Re-acceptance"). */
data class RulesUpdateUi(
    val dismissed: RulesUpdateScope? = null,
    val scope: RulesUpdateScope? = null,
    val saving: Boolean = false,
    val outdated: Boolean = false,
    val failure: ApiFailure? = null,
    /** The document's `change_summary`, plain text; null while loading, on failure, or when empty. */
    val summary: String? = null,
    val summaryFor: RulesUpdateScope? = null,
)

/** The required rules for [session], or null when nothing is required (or no version is published to accept). */
fun rulesUpdateScope(session: SessionState): RulesUpdateScope? {
    val user = session.user ?: return null
    if (session.phase != SessionPhase.AUTHENTICATED || session.updateRequired) return null
    if (session.rules?.acceptanceRequired != true && !session.rulesRefused) return null
    val version = session.public?.policies?.rules?.version ?: return null
    return RulesUpdateScope(user.id, session.generation, version, session.rulesRefusal)
}

/**
 * Re-acceptance (O3b3a): shows while the server requires the current rules or a write was refused with
 * `403 rules_acceptance_required`. **I agree** posts the shown version once (the O3b1 outcomes); the refused write is
 * never resent. **Not now** dismisses it for this account, generation, version and refusal: it comes back on a new
 * refusal, on Review, in a new process or for a newer version, never on a plain foreground or refresh.
 */
class RulesUpdateViewModel(private val rules: RulesOperations, private val reads: PolicyReads) : ViewModel() {
    private val mutable = MutableStateFlow(RulesUpdateUi())
    val state: StateFlow<RulesUpdateUi> = mutable.asStateFlow()
    private var job: Job? = null
    private var summaryJob: Job? = null

    fun visible(session: SessionState): Boolean = rulesUpdateScope(session)?.let { it != mutable.value.dismissed } == true

    fun notNow(session: SessionState) {
        val scope = rulesUpdateScope(session) ?: return
        if (mutable.value.saving) return
        mutable.value = mutable.value.copy(dismissed = scope, outdated = false, failure = null)
    }

    /** The conversation banner's Review: show the dialog again. */
    fun review() { mutable.value = mutable.value.copy(dismissed = null) }

    /** One `show.php?document=rules` GET per required scope for "What changed"; a failure just omits the card. */
    fun loadSummary(session: SessionState) {
        val scope = rulesUpdateScope(session) ?: return
        val current = mutable.value.summaryFor
        if (current != null && current.account == scope.account && current.generation == scope.generation && current.version == scope.version) return
        mutable.value = mutable.value.copy(summary = null, summaryFor = scope)
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            val summary = try {
                (reads.policyDocument(PolicyDocumentName.RULES, session) as? PolicyDocument.Hosted)?.changeSummary?.trim()?.takeIf { it.isNotEmpty() }
            } catch (failure: ApiFailure) { null }
            if (mutable.value.summaryFor == scope) mutable.value = mutable.value.copy(summary = summary)
        }
    }

    fun agree(session: SessionState) {
        if (job?.isActive == true) return
        val scope = rulesUpdateScope(session) ?: return
        mutable.value = mutable.value.copy(scope = scope, saving = true, outdated = false, failure = null)
        job = viewModelScope.launch {
            try {
                when (val result = rules.acceptRules(scope.version, session)) {
                    is RulesAcceptResult.Accepted -> settle(scope)
                    RulesAcceptResult.Outdated -> {
                        rules.refresh() // A GET; the dialog then shows the new version. Never accepted automatically.
                        settle(scope, outdated = true)
                    }
                    is RulesAcceptResult.Unknown -> {
                        rules.refresh() // Settles acceptance_required; the dialog closes on its own if it's no longer required.
                        settle(scope, failure = result.failure.takeIf { rulesUpdateScope(rules.state.value) != null })
                    }
                    is RulesAcceptResult.Failed -> settle(scope, failure = result.failure)
                }
            } catch (cancelled: CancellationException) {
                if (mutable.value.scope == scope) mutable.value = mutable.value.copy(saving = false)
                throw cancelled
            }
        }
    }

    private fun settle(scope: RulesUpdateScope, outdated: Boolean = false, failure: ApiFailure? = null) {
        if (mutable.value.scope == scope) mutable.value = mutable.value.copy(saving = false, outdated = outdated, failure = failure)
    }

    companion object {
        fun factory(rules: RulesOperations, reads: PolicyReads): ViewModelProvider.Factory =
            viewModelFactory { initializer { RulesUpdateViewModel(rules, reads) } }
    }
}
