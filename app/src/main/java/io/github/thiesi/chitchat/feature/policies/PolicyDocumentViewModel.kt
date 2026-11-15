package io.github.thiesi.chitchat.feature.policies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.policies.FormattedDocument
import io.github.thiesi.chitchat.core.policies.PolicyDocument
import io.github.thiesi.chitchat.core.policies.PolicyDocumentFormatting
import io.github.thiesi.chitchat.core.policies.PolicyDocumentName
import io.github.thiesi.chitchat.core.policies.PolicyReads
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the document screen shows. In memory only while it is open (play-readiness.md §1); never saved or logged. */
sealed interface PolicyDocumentStatus {
    data object Loading : PolicyDocumentStatus
    data class Hosted(val document: PolicyDocument.Hosted, val formatted: FormattedDocument) : PolicyDocumentStatus
    data class External(val url: String) : PolicyDocumentStatus
    data object NotPublished : PolicyDocumentStatus
    data class Failed(val failure: ApiFailure) : PolicyDocumentStatus
}

data class PolicyDocumentUi(val name: PolicyDocumentName, val scope: String, val status: PolicyDocumentStatus)

/** One read per opening; Retry is a fresh GET. A session change or close drops the document. */
class PolicyDocumentViewModel(private val reads: PolicyReads) : ViewModel() {
    private val mutable = MutableStateFlow<PolicyDocumentUi?>(null)
    val state: StateFlow<PolicyDocumentUi?> = mutable.asStateFlow()
    private var job: Job? = null
    private var session: SessionState? = null

    /** Opens [name] for the rendered [current] session. Reopening the same document under the same scope keeps it. */
    fun open(name: PolicyDocumentName, current: SessionState) {
        val scope = scopeOf(current)
        val shown = mutable.value
        if (shown != null && shown.name == name && shown.scope == scope && shown.status !is PolicyDocumentStatus.Failed) return
        session = current
        load(name, scope, current)
    }

    fun retry() {
        val shown = mutable.value ?: return
        val current = session ?: return
        if (shown.status is PolicyDocumentStatus.Loading) return
        load(shown.name, shown.scope, current)
    }

    fun close() { job?.cancel(); job = null; session = null; mutable.value = null }

    private fun load(name: PolicyDocumentName, scope: String, current: SessionState) {
        job?.cancel()
        mutable.value = PolicyDocumentUi(name, scope, PolicyDocumentStatus.Loading)
        job = viewModelScope.launch {
            val status = try {
                when (val document = reads.policyDocument(name, current)) {
                    is PolicyDocument.Hosted -> PolicyDocumentStatus.Hosted(document,
                        withContext(Dispatchers.Default) { PolicyDocumentFormatting.parse(document.markdown) })
                    is PolicyDocument.External -> PolicyDocumentStatus.External(document.url)
                    PolicyDocument.NotPublished -> PolicyDocumentStatus.NotPublished
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) { PolicyDocumentStatus.Failed(failure) }
            val shown = mutable.value
            if (shown != null && shown.name == name && shown.scope == scope) mutable.value = shown.copy(status = status)
        }
    }

    companion object {
        internal fun scopeOf(session: SessionState) = "${session.server?.value}|${session.generation}"
        fun factory(reads: PolicyReads): ViewModelProvider.Factory = viewModelFactory { initializer { PolicyDocumentViewModel(reads) } }
    }
}
