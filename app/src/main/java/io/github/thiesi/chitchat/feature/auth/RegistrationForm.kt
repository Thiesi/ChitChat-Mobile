package io.github.thiesi.chitchat.feature.auth

import androidx.compose.foundation.layout.isImeVisible

import androidx.compose.foundation.layout.WindowInsets

import kotlinx.coroutines.launch

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.runtime.key

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.relocation.bringIntoViewRequester


import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.ui.layout.onSizeChanged

import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.session.RegistrationPreparation
import io.github.thiesi.chitchat.core.session.SessionState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegistrationForm(state: SessionState, model: AuthViewModel, enabled: Boolean,
    onPolicy: ((io.github.thiesi.chitchat.core.policies.PolicyDocumentName) -> Unit)? = null) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf<LocalDate?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        password = ""; confirmation = ""; birthDate = null; pickerOpen = false
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val problem = registrationProblem(username, password, confirmation)
    val ready = enabled && state.registration == RegistrationPreparation.READY
    val submit = {
        attempted = true
        if (ready && problem == null) {
            val entered = password
            val date = birthDate?.toString()
            password = ""; confirmation = ""; attempted = false
            focus.clearFocus(); keyboard?.hide()
            model.register(username, entered, date)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.create_account_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.registration_policy))
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().testTag("registration_username")
            .semantics { contentType = ContentType.NewUsername },
            label = { Text(stringResource(R.string.username)) }, enabled = enabled, singleLine = true,
            isError = attempted && problem == RegistrationProblem.USERNAME,
            supportingText = { Text(stringResource(R.string.username_policy)) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), shape = MaterialTheme.shapes.medium)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("registration_password")
            .semantics { contentType = ContentType.NewPassword },
            label = { Text(stringResource(R.string.password)) }, enabled = enabled, singleLine = true,
            isError = attempted && problem == RegistrationProblem.PASSWORD,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next), shape = MaterialTheme.shapes.medium)
        OutlinedTextField(confirmation, { confirmation = it }, Modifier.fillMaxWidth().testTag("registration_confirmation"),
            label = { Text(stringResource(R.string.confirm_password)) }, enabled = enabled, singleLine = true,
            isError = attempted && problem == RegistrationProblem.CONFIRMATION,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }), shape = MaterialTheme.shapes.medium)
        if (attempted && problem != null) InlineError(stringResource(when (problem) {
            RegistrationProblem.USERNAME -> R.string.username_policy
            RegistrationProblem.PASSWORD -> R.string.password_policy
            RegistrationProblem.CONFIRMATION -> R.string.password_mismatch
        }))
        Text(stringResource(R.string.birth_date_explanation))
        LanternOutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); pickerOpen = true }, enabled = enabled) {
            Text(birthDate?.let { stringResource(R.string.selected_birth_date, it.toString()) } ?: stringResource(R.string.add_birth_date))
        }
        if (birthDate != null) TextButton(onClick = { birthDate = null }, enabled = enabled) { Text(stringResource(R.string.remove_birth_date)) }
        when (state.registration) {
            RegistrationPreparation.PREPARING -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.preparing_registration))
            }
            RegistrationPreparation.READY -> Text(stringResource(R.string.registration_ready))
            RegistrationPreparation.IDLE, RegistrationPreparation.FAILED -> TextButton(onClick = model::prepareRegistration, enabled = enabled) {
                Text(stringResource(R.string.refresh_registration_check))
            }
        }
        CreateBlock(state, onPolicy, enabled = ready, submit = submit)
    }
    if (pickerOpen) {
        val today = remember { LocalDate.now(ZoneOffset.UTC) }
        val latest = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // Birth date, including the picker's internal selection, stays out of saved-instance state.
        val picker = remember { DatePickerState(
            locale = Locale.getDefault(),
            initialSelectedDateMillis = birthDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            yearRange = (today.year - 120)..today.year,
            initialDisplayMode = DisplayMode.Input,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest
            },
        ) }
        DatePickerDialog(onDismissRequest = { pickerOpen = false }, confirmButton = {
            TextButton(onClick = {
                birthDate = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                pickerOpen = false
            }, enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.use_birth_date)) }
        }, dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text(stringResource(android.R.string.cancel)) } }) {
            // Date-input children can be saveable internally even when the outer state is not.
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) { DatePicker(picker) }
        }
    }
}

/**
 * Above Create my account while the server publishes rules (play-readiness.md §1): "By creating an account, you agree
 * to {installation}'s rules" (and, with a privacy policy, "and acknowledge its privacy policy"). The linked words open
 * the document screen; registration then sends the rendered version (O3b1).
 */
@Composable
private fun RulesConsent(consent: ConsentText,
    onPolicy: ((io.github.thiesi.chitchat.core.policies.PolicyDocumentName) -> Unit)?,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {}) {
    val (sentence, rules, privacy) = Triple(consent.sentence, consent.rules, consent.privacy)
    val linkStyle = androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(
        color = io.github.thiesi.chitchat.core.theme.ChitChatTheme.colors.steward,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append(sentence)
        fun link(words: String, tag: String, name: io.github.thiesi.chitchat.core.policies.PolicyDocumentName) {
            val start = sentence.indexOf(words).takeIf { it >= 0 } ?: return
            if (onPolicy == null) return
            addLink(androidx.compose.ui.text.LinkAnnotation.Clickable(tag, linkStyle) { onPolicy(name) }, start, start + words.length)
        }
        link(rules, "registration_rules_link", io.github.thiesi.chitchat.core.policies.PolicyDocumentName.RULES)
        privacy?.let { link(it, "registration_privacy_link", io.github.thiesi.chitchat.core.policies.PolicyDocumentName.PRIVACY) }
    }
    Text(text, androidx.compose.ui.Modifier.testTag("registration_rules_consent"),
        color = io.github.thiesi.chitchat.core.theme.ChitChatTheme.colors.textMuted, onTextLayout = onTextLayout)
}

/** The consent line's composed sentence and its linked words, from one place (RulesConsent and #390's review key). */
private class ConsentText(val sentence: String, val rules: String, val privacy: String?)

@Composable
private fun consentText(state: SessionState): ConsentText {
    val installation = installationOf(state)
    val rules = stringResource(R.string.registration_rules_link, installation)
    val privacy = stringResource(R.string.registration_privacy_link).takeIf { state.public?.policies?.privacy != null }
    val sentence = if (privacy != null) stringResource(R.string.registration_rules_privacy_consent, rules, privacy)
        else stringResource(R.string.registration_rules_consent, rules)
    return ConsentText(sentence, rules, privacy)
}

/**
 * #390 (TL and PL ruling): what "the agreement" is for the review mark: the composed sentence, the published rules
 * document's identity and version, and whether a privacy link is shown. Any change resets the mark.
 */
private data class ConsentReviewKey(val sentence: String, val rulesUrl: String?, val rulesVersion: Int?, val privacyShown: Boolean)

/** #390: where the consent line is laid out (its coordinates and its first text line's bottom), read at a tap. Not state. */
private class ConsentPlace {
    var line: androidx.compose.ui.layout.LayoutCoordinates? = null
    var firstLineBottom = 0f
}

/**
 * The consent line and Create my account as one block (#275; PL and TL): the line is visible whenever Create can be
 * tapped.
 * - **Keyboard hidden:** pinned above the bottom, outside the scroller.
 * - **Keyboard up:** absent entirely, so there's no Create while typing. The last field's Done hides the keyboard and
 *   brings the block back. A hardware keyboard never shows the IME, so the block stays.
 * - **Very short windows:** when the block would take more than half the height, it sits in flow at the end of the
 *   scroller. A tap on Create while any part of the line is off screen only brings the block into view and announces
 *   why (announced only, no visible text); it doesn't submit.
 * - **The line taller than it can be shown with Create (#390, owner's rule):** see [consentTap]. The first tap brings the
 *   line's top into view, announces and marks it reviewed for this visit; a later tap submits.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CreateBlock(state: SessionState, onPolicy: ((io.github.thiesi.chitchat.core.policies.PolicyDocumentName) -> Unit)?,
    enabled: Boolean, submit: () -> Unit) {
    if (WindowInsets.isImeVisible) return
    val slot = io.github.thiesi.chitchat.core.ui.LocalPinnedActions.current
    var pinnedHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val inFlow = slot != null && slot.availableHeight > 0 && pinnedHeight * 2 > slot.availableHeight
    if (slot == null || inFlow) InFlowCreateBlock(state, onPolicy, enabled, submit, slot)
    else io.github.thiesi.chitchat.core.ui.PinnedAction(hideWhileImeVisible = true) {
        Column(Modifier.fillMaxWidth().onSizeChanged { pinnedHeight = it.height }.testTag("registration_create_block"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.public?.policies?.rules?.let { RulesConsent(consentText(state), onPolicy) }
            PrimaryButton(onClick = submit, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("registration_submit")) {
                Text(stringResource(R.string.onboarding_create_account))
            }
        }
    }
}

@Composable
private fun InFlowCreateBlock(state: SessionState, onPolicy: ((io.github.thiesi.chitchat.core.policies.PolicyDocumentName) -> Unit)?,
    enabled: Boolean, submit: () -> Unit, slot: io.github.thiesi.chitchat.core.ui.PinnedActionSlot?) {
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val placed = remember { ConsentPlace() }
    var announcement by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val policies = state.public?.policies
    val hasLine = policies?.rules != null
    val consent = if (hasLine) consentText(state) else null
    // #390: reviewed in this visit (memory only, never saved): reset by a change of the agreement or when the block leaves
    // composition (leaving the screen, a configuration change).
    val reviewKey = consent?.let { ConsentReviewKey(it.sentence, policies?.rules?.url, policies?.rules?.version, it.privacy != null) }
    var reviewed by remember(reviewKey) { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester).testTag("registration_create_block"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The line's own coordinates, placed in the viewport frame at the tap (unclipped: a clipped rect of an off-screen
        // line could look "inside" the viewport), and its first text line's bottom (#390's placement).
        if (consent != null) Box(Modifier.onGloballyPositioned { placed.line = it }) {
            RulesConsent(consent, onPolicy) { placed.firstLineBottom = it.getLineBottom(0) }
        }
        PrimaryButton(onClick = {
            val frame = slot?.frame?.takeIf { it.isAttached }
            val line = placed.line?.takeIf { it.isAttached }
            val tap = if (!hasLine || frame == null || line == null) ConsentTap.Submit else {
                val rect = frame.localBoundingBoxOf(line, clipBounds = false)
                with(density) {
                    consentTap(rect.top, rect.height, placed.firstLineBottom, frame.size.height.toFloat(), reviewed,
                        gap = 12.dp.toPx(), button = 48.dp.toPx(), margin = 8.dp.toPx())
                }
            }
            when (tap) {
                ConsentTap.Submit -> submit()
                ConsentTap.BringFits -> { scope.launch { requester.bringIntoView() }; announcement++ }
                is ConsentTap.BringTop -> {
                    reviewed = true
                    announcement++
                    scope.launch { slot?.scrollBy?.invoke(tap.distance) }
                }
            }
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("registration_submit")) {
            Text(stringResource(R.string.onboarding_create_account))
        }
        // Announced only: the scroll shows sighted users what happened (TL and PL, #275).
        if (announcement > 0) key(announcement) {
            val message = stringResource(R.string.registration_review_agreement)
            Box(Modifier.size(1.dp).testTag("registration_review_announcement").semantics {
                liveRegion = LiveRegionMode.Polite; contentDescription = message
            })
        }
    }
}
