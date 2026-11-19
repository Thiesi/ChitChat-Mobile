package io.github.thiesi.chitchat.feature.auth

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Every step-up dialog names its own action (copy-deck.md; the export title once leaked into Replace key). Each caller
 * passes a title resource of its own, no generic title exists to fall back to, and the shared step-up and account lines
 * carry no export wording.
 */
class StepUpTitleContractTest {
    private fun root(name: String) = listOf(File(name), File("app/$name")).single { it.exists() }
    private val sources by lazy { root("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    private val strings by lazy {
        root("src/main/res/values").listFiles()!!.filter { it.extension == "xml" }.flatMap { file ->
            Regex("""<string name="([a-z0-9_]+)">([^<]*)</string>""").findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }
        }.toMap()
    }

    /** Each `StepUpDialog(` call and the title resources in its argument list (up to the methods argument). */
    private fun callers(): List<Pair<String, List<String>>> = sources.flatMap { file ->
        val text = file.readText()
        Regex("""\bStepUpDialog\(""").findAll(text).filterNot { text.lastIndexOf("fun ", it.range.first).let { f ->
            f >= 0 && text.substring(f, it.range.first).trim() == "fun" } }.map { call ->
            val args = text.substring(call.range.last, minOf(text.length, call.range.last + 400)).substringBefore("methods")
            file.name to Regex("""R\.string\.([a-z0-9_]+)""").findAll(args).map { it.groupValues[1] }.toList()
        }.toList()
    }

    @Test fun everyCallerPassesATitleOfItsOwn() {
        val calls = callers()
        assertTrue("Export, Recovery key and room transfer at least: $calls", calls.size >= 3)
        val seen = mutableMapOf<String, String>()
        for ((file, titles) in calls) {
            assertTrue("$file passes a title resource", titles.isNotEmpty())
            for (title in titles) {
                assertTrue("$file: $title is a step-up title resource", title.startsWith("step_up_title_") || title.endsWith("_step_up_title"))
                assertNotNull("$file: $title exists", strings[title])
                val owner = seen.putIfAbsent(title, file)
                assertTrue("$title belongs to $owner, not also $file", owner == null || owner == file)
            }
        }
        val values = seen.keys.map { strings.getValue(it) }
        assertEquals("No two flows share wording: $values", values.size, values.toSet().size)
    }

    @Test fun noGenericTitleRemains() {
        assertNull("The shared export title is retired", strings["step_up_title"])
        for (file in sources) assertFalse(file.name, Regex("""R\.string\.step_up_title\b(?!_)""").containsMatchIn(file.readText()))
    }

    @Test fun sharedLinesCarryNoExportWording() {
        for (name in listOf("step_up_recovery_warning", "step_up_retry_rejected", "step_up_password_explanation"))
            assertFalse("$name: ${strings[name]}", strings.getValue(name).contains("export", ignoreCase = true))
        // accountError serves Replace key, Create key and later Delete account: only exportError may use export lines.
        val screen = sources.single { it.name == "AccountScreen.kt" }.readText()
        val shared = screen.substringAfter("internal fun accountError(").substringBefore("\n})")
        assertFalse(shared, shared.contains("R.string.export_"))
    }
}
