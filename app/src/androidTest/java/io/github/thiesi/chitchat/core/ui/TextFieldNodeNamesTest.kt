package io.github.thiesi.chitchat.core.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.feature.auth.StepUpDialog
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/**
 * #331a (TL plan, 2026-11-23): measurement only. For each way the app names a text field, read the real
 * AccessibilityNodeInfo the framework gives an accessibility service (UiAutomation with every interactive window, as
 * ReportDetailsAccessibilityTest does), empty and filled, and write the facts to `ccm-a11y-nodes`. It asserts no outcome:
 * #331b turns these into strict asserts or removes the probe. It reads nodes only: no keyboard, IME or focus request.
 * It fails only if a field's node can't be obtained or the file can't be written, so an empty result never passes.
 *
 * #331b step 0 (TL, 2026-11-23): also the field node's stateDescription, tooltipText and error, and its full descendant
 * subtree (each node's class, text, contentDescription, importantForAccessibility, visibleToUser and whether it's
 * focusable to a screen reader). That answers whether the label Text reaches an accessibility service on the field, as a
 * focusable descendant or merged into the node. Still measurement only.
 *
 * #331b mechanisms (TL, 2026-11-23): what puts a name on the EditText node itself with no visible change. Compose UI
 * 1.11.4 has no semantics property for a hint or labeledBy (its accessibility delegate only calls setContentDescription,
 * setError and setStateDescription), so the candidate is a contentDescription on the field's own node, measured here
 * with the visible label still showing: MessageInput (both overloads) with it passed as the modifier, which lands on the
 * inner BasicTextField, and an OutlinedTextField with a label and the same modifier.
 *
 * #331b second signal (TL, 2026-11-23): the Accessibility Test Framework (ATF 4.1.1, which Compose ui-test's
 * enableAccessibilityChecks uses), run with that integration's own validator settings (checks from the root view) on
 * each root's view (API 34+), recording ATF's SpeakableTextPresentCheck verdict for the field per case and state, never
 * failing on it. It calls ATF's `checkAndReturnResults` directly: the results listener hands back a Guava type that
 * isn't on our test compile classpath, and adding Guava would change the compatible set. ATF composes a node's description from its children much as TalkBack does, so it's
 * evidence, not proof of spoken output. `synthetic-unnamed` (no label, placeholder or description) is the control: ATF
 * must flag it, or its silence on the other cases proves nothing.
 *
 * Cases (see docs/milestones/text-field-names.md for the call sites each covers):
 * - real: the app's own composables, reusable in isolation (MessageInput in both overloads, StepUpDialog's secret).
 * - material: the Material OutlinedTextField with exactly the parameters the call sites pass (label, singleLine,
 *   supportingText, PasswordVisualTransformation, multiline). Those call sites add no semantics of their own.
 * - synthetic: comparison patterns the app doesn't use as such (placeholder only, contentDescription only, a visible
 *   label kept before an unlabelled field, the report details pattern). Labelled as such.
 * Fixture strings only, never user text.
 */
class TextFieldNodeNamesTest {
    @get:Rule val compose = createEmptyComposeRule()

    private class Case(val id: String, val kind: String, val canFill: Boolean = true,
        val content: @Composable (filled: Boolean) -> Unit)

    private val label = "Fixture label"
    private val typed = "Fixture text"

    private val cases = listOf(
        Case("message-input-string", "real") { filled ->
            MessageInput(if (filled) typed else "", {}, enabled = true, label = label, compact = false)
        },
        Case("message-input-value", "real") { filled ->
            MessageInput(TextFieldValue(if (filled) typed else ""), {}, enabled = true, label = label, compact = false)
        },
        Case("mechanism-message-input-description", "mechanism") { filled ->
            MessageInput(if (filled) typed else "", {}, enabled = true, label = label, compact = false,
                modifier = Modifier.semantics { contentDescription = label })
        },
        Case("mechanism-message-input-value-description", "mechanism") { filled ->
            MessageInput(TextFieldValue(if (filled) typed else ""), {}, enabled = true, label = label, compact = false,
                modifier = Modifier.semantics { contentDescription = label })
        },
        Case("mechanism-outlined-label-description", "mechanism") { filled ->
            OutlinedTextField(if (filled) typed else "", {}, Modifier.semantics { contentDescription = label },
                label = { Text(label) }, singleLine = true)
        },
        // The secret lives in the dialog's own state; filling it needs text input, which requests focus. Empty only.
        Case("step-up-password", "real", canFill = false) { _ ->
            StepUpDialog("Fixture step-up title", VerificationMethods(false, true, 8), busy = false, errorMessage = null,
                onVerify = { _, _ -> }, onCancel = {})
        },
        Case("outlined-label-single-line", "material") { filled ->
            OutlinedTextField(if (filled) typed else "", {}, label = { Text(label) }, singleLine = true)
        },
        Case("outlined-label-password", "material") { filled ->
            OutlinedTextField(if (filled) typed else "", {}, label = { Text(label) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
        },
        Case("outlined-label-supporting", "material") { filled ->
            OutlinedTextField(if (filled) typed else "", {}, label = { Text(label) }, singleLine = true,
                supportingText = { Text("Fixture supporting text") })
        },
        Case("outlined-label-supporting-password", "material") { filled ->
            OutlinedTextField(if (filled) typed else "", {}, label = { Text(label) },
                supportingText = { Text("Fixture supporting text") }, visualTransformation = PasswordVisualTransformation())
        },
        Case("outlined-label-multiline", "material") { filled ->
            OutlinedTextField(if (filled) typed else "", {}, label = { Text(label) }, singleLine = false, minLines = 3)
        },
        Case("synthetic-unnamed", "synthetic") { filled ->
            OutlinedTextField(if (filled) typed else "", {})
        },
        Case("synthetic-placeholder-only", "synthetic") { filled ->
            OutlinedTextField(if (filled) typed else "", {}, placeholder = { Text("Fixture placeholder") })
        },
        Case("synthetic-content-description-only", "synthetic") { filled ->
            OutlinedTextField(if (filled) typed else "", {}, Modifier.semantics { contentDescription = "Fixture description" })
        },
        Case("synthetic-visible-label-before", "synthetic") { filled ->
            Column {
                Text("Fixture question")
                OutlinedTextField(TextFieldValue(if (filled) typed else ""), {}, minLines = 3)
            }
        },
    )

    private fun automation() = InstrumentationRegistry.getInstrumentation().uiAutomation.also { automation ->
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    /** Each window's nodes in traversal order (pre-order), refreshed to the app's current state. */
    private fun ordered(): List<List<AccessibilityNodeInfo>> = automation().windows.map { window ->
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            node.refresh()
            nodes += node
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(window.root)
        nodes
    }

    private fun CharSequence?.fact(): String? = this?.toString()?.take(160)

    private fun facts(case: Case, state: String): JSONObject {
        var found: Pair<List<AccessibilityNodeInfo>, AccessibilityNodeInfo>? = null
        compose.waitUntil(5_000) {
            val candidates = ordered().flatMap { nodes -> nodes.filter { it.isEditable }.map { nodes to it } }
            found = candidates.singleOrNull()
            found != null
        }
        val (nodes, node) = checkNotNull(found) { "${case.id} $state: no single editable node" }
        val at = nodes.indexOf(node)
        val preceding = nodes.subList(0, at).lastOrNull { !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty() }
        val labeledBy = node.labeledBy
        return JSONObject()
            .put("case", case.id).put("kind", case.kind).put("state", state)
            .put("className", node.className.fact() ?: JSONObject.NULL)
            .put("text", node.text.fact() ?: JSONObject.NULL)
            .put("hintText", node.hintText.fact() ?: JSONObject.NULL)
            .put("contentDescription", node.contentDescription.fact() ?: JSONObject.NULL)
            .put("labeledBy", (labeledBy?.text ?: labeledBy?.contentDescription).fact()
                ?: (if (labeledBy != null) "node" else null) ?: JSONObject.NULL)
            .put("showingHintText", node.isShowingHintText)
            .put("password", node.isPassword)
            .put("preceding", (preceding?.text ?: preceding?.contentDescription).fact() ?: JSONObject.NULL)
            .put("stateDescription", (if (android.os.Build.VERSION.SDK_INT >= 30) node.stateDescription else null).fact() ?: JSONObject.NULL)
            // tooltipText and isScreenReaderFocusable are API 28+; minSdk is 26 (review of 23bf3d6).
            .put("tooltipText", (if (android.os.Build.VERSION.SDK_INT >= 28) node.tooltipText else null).fact() ?: JSONObject.NULL)
            .put("error", node.error.fact() ?: JSONObject.NULL)
            .put("descendants", descendants(node))
            .put("atfSpeakable", atfVerdict())
    }

    /** ATF's validator with enableAccessibilityChecks' default settings, and never throwing (the probe records). */
    private val atf by lazy {
        com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator()
            .setRunChecksFromRootView(true).setThrowExceptionForErrors(false)
    }

    /**
     * Runs ATF on every root (a dialog has its own) and reports the worst SpeakableTextPresentCheck finding for an
     * editable element: "error", "warning", "info", or "pass" when none. "notrun" below API 34, where the checks aren't
     * enabled.
     */
    private fun atfVerdict(): String {
        if (android.os.Build.VERSION.SDK_INT < 34) return "notrun"
        val results = mutableListOf<com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult>()
        val views = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
            .mapNotNull { (it.root as? androidx.compose.ui.platform.ViewRootForTest)?.view }
        check(views.isNotEmpty()) { "No root view for ATF" }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { views.forEach { results += atf.checkAndReturnResults(it) } }
        val types = results.filter {
            it.sourceCheckClass.simpleName == "SpeakableTextPresentCheck" &&
                it.element?.className?.toString()?.contains("EditText") == true
        }.map { it.type.name }
        return when {
            "ERROR" in types -> "error"
            "WARNING" in types -> "warning"
            "INFO" in types -> "info"
            else -> "pass"
        }
    }

    /** The field node's whole subtree in pre-order (bounded), as an accessibility service would find it. */
    private fun descendants(field: AccessibilityNodeInfo): JSONArray {
        val out = JSONArray()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            for (index in 0 until node.childCount) {
                if (out.length() >= 32) return
                val child = node.getChild(index) ?: continue
                child.refresh()
                out.put(JSONObject().put("depth", depth)
                    .put("className", child.className.fact() ?: JSONObject.NULL)
                    .put("text", child.text.fact() ?: JSONObject.NULL)
                    .put("contentDescription", child.contentDescription.fact() ?: JSONObject.NULL)
                    .put("important", child.isImportantForAccessibility)
                    .put("visible", child.isVisibleToUser)
                    .put("focusable", child.isFocusable)
                    .put("screenReaderFocusable", android.os.Build.VERSION.SDK_INT >= 28 && child.isScreenReaderFocusable))
                if (depth < 16) walk(child, depth + 1)
            }
        }
        walk(field, 1)
        return out
    }

    @Test fun recordEachTextFieldPatternsAccessibilityNode() {
        val entries = JSONArray()
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            for (case in cases) for (filled in if (case.canFill) listOf(false, true) else listOf(false)) {
                scenario.onActivity { activity -> activity.setContent { ChitChatTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) { case.content(filled) } }
                } } }
                compose.waitForIdle()
                entries.put(facts(case, if (filled) "filled" else "empty"))
            }
        }
        val api = android.os.Build.VERSION.SDK_INT
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-a11y-nodes")
        check(folder.isDirectory || folder.mkdirs()) { "Can't create the node probe folder" }
        val file = java.io.File(folder, "text-field-nodes-api$api.json")
        file.writeText(JSONObject().put("api", api).put("entries", entries).toString(), Charsets.UTF_8)
        check(file.length() > 0) { "The node probe wrote nothing" }
    }
}
