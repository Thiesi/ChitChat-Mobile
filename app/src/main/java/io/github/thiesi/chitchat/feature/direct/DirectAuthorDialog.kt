package io.github.thiesi.chitchat.feature.direct

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.ui.AuthorDialogPresentation
import io.github.thiesi.chitchat.core.ui.MessageAuthorChangeDialog

@Composable
internal fun DirectAuthorChangeDialog(dialog: DirectAuthorDialog, targetCurrent: Boolean, available: Boolean,
    busy: Boolean, onChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit,
    pending: DirectAuthorUnknown?, canConfirm: Boolean, currentPreview: String?, error: String?) {
    val validation = dialog.validation?.let { stringResource(when (it) {
        "message_unchanged" -> R.string.author_unchanged
        "empty_message" -> R.string.dm_empty_body
        else -> R.string.dm_long_body
    }) }
    MessageAuthorChangeDialog(AuthorDialogPresentation(dialog.request.kind, dialog.body,
        dialog.request.original.body.orEmpty(), validation, dialog.checked, pending?.messageId),
        targetCurrent, available, busy, onChange, onConfirm, onDismiss, canConfirm, currentPreview, error)
}
