package io.github.thiesi.chitchat.feature.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.search.MessageSearchHit
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import io.github.thiesi.chitchat.core.search.ExcerptMatch
import io.github.thiesi.chitchat.core.theme.ChitChatTheme

/**
 * The server's match ranges (#283) as a presenceWash background plus SemiBold: two cues, never colour alone, and
 * no underline (that means "link"). Everything else, including the server's "… " and " …", is plain text as given.
 * TalkBack reads the excerpt as plain text: spans add no speech. Ranges arrive already validated, all or nothing.
 */
@Composable
internal fun highlightedExcerpt(excerpt: String, matches: List<ExcerptMatch>): AnnotatedString {
    val wash = ChitChatTheme.colors.presenceWash
    return remember(excerpt, matches, wash) { excerptWithMatches(excerpt, matches, wash) }
}

internal fun excerptWithMatches(excerpt: String, matches: List<ExcerptMatch>, wash: Color): AnnotatedString =
    buildAnnotatedString {
        append(excerpt)
        for (match in matches) addStyle(SpanStyle(background = wash, fontWeight = FontWeight.SemiBold), match.start, match.end)
    }

/** A result row's text: where it is, the excerpt with its highlighted matches, the sender and the time. */
@Composable
internal fun SearchResultText(row: MessageSearchHit, time: String) {
    Text(when (row) {
        is MessageSearchHit.Room -> stringResource(R.string.message_search_room, row.room.name)
        is MessageSearchHit.Direct -> stringResource(R.string.message_search_direct, row.peer.username)
    }, style = MaterialTheme.typography.titleMedium)
    Text(highlightedExcerpt(row.excerpt, row.excerptMatches), Modifier.testTag("message_search_excerpt"))
    Text(row.sender?.username ?: stringResource(R.string.message_search_sender_unknown), style = MaterialTheme.typography.bodySmall)
    Text(time + if (row.editedAt != null) stringResource(R.string.message_search_edited) else "", style = MaterialTheme.typography.bodySmall)
}

