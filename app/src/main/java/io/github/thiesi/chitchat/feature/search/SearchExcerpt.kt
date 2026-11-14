package io.github.thiesi.chitchat.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
