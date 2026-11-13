package io.github.thiesi.chitchat.feature.licences

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.licences.OssLicence
import io.github.thiesi.chitchat.core.licences.OssLicences
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the bundled list off the main thread; null while loading. */
@Composable
private fun rememberLicences(): List<OssLicence>? {
    val assets = LocalContext.current.assets
    val list by produceState<List<OssLicence>?>(null, assets) { value = withContext(Dispatchers.IO) { OssLicences.load(assets) } }
    return list
}

/** Every project the app is built with: name, version and licence. Tapping one shows its full terms. */
@Composable
internal fun LicencesScreen(onOpen: (Int) -> Unit) {
    val licences = rememberLicences() ?: return
    val colors = ChitChatTheme.colors
    Column(Modifier.fillMaxWidth().testTag("licences_list")) {
        Text(stringResource(R.string.oss_licences_intro), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        licences.forEachIndexed { index, licence ->
            if (index > 0) HorizontalDivider()
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(index) }
                .padding(vertical = 12.dp).testTag("licence:$index"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(licence.name, style = MaterialTheme.typography.bodyLarge)
                Text("${licence.version} · ${licence.license}", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
            }
        }
    }
}

/** One project's terms: copyright where its licence requires one, the source link (an explicit tap) and the full texts. */
@Composable
internal fun LicenceScreen(index: Int) {
    val context = LocalContext.current
    val licence = rememberLicences()?.getOrNull(index) ?: return
    val texts by produceState<List<String>?>(null, licence) {
        value = withContext(Dispatchers.IO) { licence.texts.map { OssLicences.text(context.assets, it) } }
    }
    val colors = ChitChatTheme.colors
    Column(Modifier.fillMaxWidth().testTag("licence_detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(licence.name, style = MaterialTheme.typography.titleMedium)
        Text("${licence.version} · ${licence.license}", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        licence.copyright?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        LanternOutlinedButton(onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, licence.source.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
            catch (_: ActivityNotFoundException) { /* No browser: the address is shown below. */ }
        }, modifier = Modifier.heightIn(min = 48.dp).testTag("licence_source")) { Text(stringResource(R.string.oss_licence_source)) }
        Text(licence.source, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        // Reflowed into paragraphs so the terms read at any width and font size (presentation lead, PR220).
        texts?.forEach { text ->
            HorizontalDivider()
            OssLicences.paragraphs(text).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
