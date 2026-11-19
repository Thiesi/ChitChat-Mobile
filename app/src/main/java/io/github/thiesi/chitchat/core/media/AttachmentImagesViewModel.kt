package io.github.thiesi.chitchat.core.media

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * The one owner of the received-image loader (received files F3; TL, #308). Activity-scoped like RecoveryKeyViewModel,
 * so it survives configuration change, and fenced by the loader itself: any change of server, account, generation or
 * phase drops every image and job. Rooms and DMs share it, because the memory budget is process-wide; conversation
 * view models never own it. Screens reach it only through an [ImagePanes.Pane] bound to their owner token.
 */
class AttachmentImagesViewModel internal constructor(session: StateFlow<SessionState>, network: NetworkConditions,
    source: AttachmentSource, capacityBytes: Long, animated: AnimatedDecoding? = platformAnimatedDecoding()) : ViewModel() {
    internal val images = AttachmentImages(viewModelScope, session, network.state, source, AndroidImageDecoding, capacityBytes)
    /** The full-screen viewer's one session (F4), opened only through a pane. */
    internal val viewer = AttachmentViewer(viewModelScope, session, network.state, source, images, AndroidImageDecoding, animated)
    internal val panes = ImagePanes(images, viewer)

    companion object {
        /** Held decoded images may use half the shared budget, so they never crowd out upload drafts entirely (TL). */
        internal fun factory(context: Context, session: StateFlow<SessionState>, downloads: AttachmentDownloads, budget: AttachmentMemoryBudget):
            ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AttachmentImagesViewModel(session, AndroidNetworkConditions(context.applicationContext), downloads, budget.capacityBytes / 2)
            }
        }
    }
}
