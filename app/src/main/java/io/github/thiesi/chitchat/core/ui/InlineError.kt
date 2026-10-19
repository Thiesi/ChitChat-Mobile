package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/** Long forms must expose errors to sighted users as well as announcing them to TalkBack. */
@Composable
internal fun InlineError(message: String) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(message) { requester.bringIntoView() }
    Text(message, Modifier.bringIntoViewRequester(requester).semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error)
}
