package io.github.thiesi.chitchat.feature.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.ui.AuthorDialogPresentation
import io.github.thiesi.chitchat.core.ui.MessageAuthorChangeDialog

/** Room-specific state is adapted at the presentation boundary, never shared with a DM request. */
@Composable
internal fun RoomAuthorChangeDialog(dialog: RoomAuthorDialog, targetCurrent: Boolean, available: Boolean,
    busy: Boolean, onChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit,
    pending: RoomAuthorUnknown? = null, canConfirm: Boolean = available, currentPreview: String? = null, error: String? = null) {
    val validation = dialog.validation?.let {
        if (it == "message_unchanged") stringResource(R.string.author_unchanged) else validationMessage(it)
    }
    MessageAuthorChangeDialog(AuthorDialogPresentation(dialog.request.kind, dialog.body,
        dialog.request.original.body.orEmpty(), validation, dialog.checked, pending?.messageId),
        targetCurrent, available, busy, onChange, onConfirm, onDismiss, canConfirm, currentPreview, error)
}
