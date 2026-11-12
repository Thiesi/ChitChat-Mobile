package io.github.thiesi.chitchat.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.theme.ChitChatTheme

/** Counts use persimmon, not the destructive-error role. */
@Composable
internal fun CountBadge(modifier: Modifier=Modifier,mention: Boolean=false,content: (@Composable RowScope.() -> Unit)?=null) {
    val colors=ChitChatTheme.colors
    Badge(modifier=modifier,containerColor=if (mention) colors.presence else colors.signature,
        contentColor=colors.onSignature,content=content)
}

internal enum class AttentionKind { GENERIC, UNCERTAIN, FAILED }

/** An unconfirmed outcome takes priority over a simultaneous error. */
internal fun attentionKind(uncertain: Boolean, failed: Boolean)=when {
    uncertain -> AttentionKind.UNCERTAIN
    failed -> AttentionKind.FAILED
    else -> AttentionKind.GENERIC
}

/** One spoken description per marker state; the generic state keeps the caller's existing text. */
@StringRes internal fun attentionDescription(kind: AttentionKind, @StringRes generic: Int): Int=when (kind) {
    AttentionKind.UNCERTAIN -> R.string.details_attention_uncertain
    AttentionKind.FAILED -> R.string.details_attention_failed
    AttentionKind.GENERIC -> generic
}

/** The Details button's spoken state. A blocked composer keeps its existing description first and
 * adds an uncertain or failed state after it, so neither fact is lost.
 */
@Composable
internal fun detailsStateDescription(kind: AttentionKind, @StringRes generic: Int, @StringRes blocked: Int?=null): String {
    val state=androidx.compose.ui.res.stringResource(attentionDescription(kind,generic))
    return if (blocked==null) state else {
        val unavailable=androidx.compose.ui.res.stringResource(blocked)
        if (kind==AttentionKind.GENERIC) unavailable else "$unavailable $state"
    }
}

/** States differ by shape, never color alone (A-05): a dot, a question mark or an octagon,
 * each on a ground-ringed 16dp badge so it holds against whatever is behind it.
 */
@Composable
internal fun AttentionBadge(modifier: Modifier=Modifier, uncertain: Boolean=false, failed: Boolean=false) {
    val colors=ChitChatTheme.colors
    val kind=attentionKind(uncertain,failed)
    Box(modifier.size(16.dp).background(colors.ground,CircleShape).testTag("attention_badge:${kind.name.lowercase()}"),
        contentAlignment=Alignment.Center) {
        when (kind) {
            AttentionKind.GENERIC -> Box(Modifier.size(8.dp).background(colors.signatureText,CircleShape))
            AttentionKind.UNCERTAIN -> Icon(painterResource(R.drawable.ic_help),contentDescription=null,
                Modifier.size(12.dp),tint=colors.warning)
            AttentionKind.FAILED -> Icon(painterResource(R.drawable.ic_error),contentDescription=null,
                Modifier.size(12.dp),tint=colors.error)
        }
    }
}

/** The Lantern more glyph for Details buttons; the caller keeps its 48dp target and label. */
@Composable
internal fun MoreIcon()=Icon(painterResource(R.drawable.ic_more),contentDescription=null,Modifier.size(24.dp),
    tint=ChitChatTheme.colors.textMuted)
