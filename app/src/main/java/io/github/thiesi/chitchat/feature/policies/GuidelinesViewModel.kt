package io.github.thiesi.chitchat.feature.policies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.policies.RulesAcceptResult
import io.github.thiesi.chitchat.core.policies.RulesOperations
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.APP_GUIDELINES_VERSION
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptance
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptanceStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/** What the guidelines screen shows for one signed-in account (play-readiness.md §1). Memory only. */
data class GuidelinesUi(
    val account: Long? = null,
    val generation: Long? = null,
    val saving: Boolean = false,
    /** `409 rules_version_outdated`: the rules changed while the screen was open; the session was re-read. */
    val outdated: Boolean = false,
    val failure: ApiFailure? = null,
)

/**
 * The guidelines screen between sign-in and the shell (P-170, P-171). **I agree** records the app guidelines' local
 * acceptance; while the server says `acceptance_required`, it first sends one `policies/accept.php` POST of the shown
 * version and records only after the server confirms. Never resent: Outdated re-reads the session and asks again;
 * Unknown re-reads the session and moves on only if it no longer requires acceptance.
 */
class GuidelinesViewModel(private val rules: RulesOperations, private val store: GuidelinesAcceptanceStore) : ViewModel() {
    /** Null until the store's first read; a failed read is empty, so the screen shows rather than being skipped. */
    val accepted: StateFlow<Set<GuidelinesAcceptance>?> = store.accepted
        .catch { if (it is IOException) emit(emptySet()) else throw it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val mutable = MutableStateFlow(GuidelinesUi())
    val state: StateFlow<GuidelinesUi> = mutable.asStateFlow()
    private var job: Job? = null

    fun agree(session: SessionState) {
        if (job?.isActive == true) return
        val user = session.user ?: return
        val origin = session.server?.value ?: return
        val record = GuidelinesAcceptance(origin, user.id, APP_GUIDELINES_VERSION)
        val version = session.public?.policies?.rules?.version
        val scope = GuidelinesUi(account = user.id, generation = session.generation)
        job = viewModelScope.launch {
            if (version == null || session.rules?.acceptanceRequired != true) { save(record, scope); return@launch }
            mutable.value = scope.copy(saving = true)
            when (val result = rules.acceptRules(version, session)) {
                is RulesAcceptResult.Accepted -> save(record, scope)
                RulesAcceptResult.Outdated -> {
                    rules.refresh() // A GET; the screen then shows the new version. Never accepted automatically.
                    mutable.value = scope.copy(outdated = true)
                }
                is RulesAcceptResult.Unknown -> {
                    rules.refresh()
                    val now = rules.state.value
                    if (now.server == session.server && now.generation == session.generation && now.user?.id == user.id &&
                        now.rules?.acceptanceRequired == false) save(record, scope)
                    else mutable.value = scope.copy(failure = result.failure)
                }
                is RulesAcceptResult.Failed -> mutable.value = scope.copy(failure = result.failure)
            }
        }
    }

    private suspend fun save(record: GuidelinesAcceptance, scope: GuidelinesUi) {
        mutable.value = try { store.record(record); GuidelinesUi() }
            catch (_: IOException) { scope.copy(failure = ApiFailure(FailureKind.STORAGE)) }
    }

    companion object {
        fun factory(rules: RulesOperations, store: GuidelinesAcceptanceStore): ViewModelProvider.Factory =
            viewModelFactory { initializer { GuidelinesViewModel(rules, store) } }
    }
}
