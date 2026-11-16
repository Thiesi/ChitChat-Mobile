package io.github.thiesi.chitchat.testing

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * A file channel for the Completion cleanup's failure-only sample (completion-ime-cleanup-observations.md). The logcat
 * copy can be lost: the journey wrapper reads logcat once after the suite, and the device's ring buffer may have rotated
 * past an early failure. This writes the same allowlisted scalar line, plus the test identity, to a small file in the
 * instrumentation's additional output, collected beside ime-lifecycle.txt. Measurement only: nothing here waits, probes,
 * hides or retries.
 */
internal object CompletionSampleFile {
    const val FOLDER = "ccm-completion-cleanup"
    const val MAX_FILES = 64
    const val MAX_BYTES = 256
    private val IDENTITY = Regex("[A-Za-z][A-Za-z0-9_]{0,63}\\.[A-Za-z][A-Za-z0-9_]{0,127}")
    /** Exactly the sample line the emitter already logs; anything else is refused. */
    val LINE = Regex("sample=last_wait_predicate compose=(?:unobserved|-?(?:0|[1-9][0-9]{0,9})) " +
        "native1=(?:unobserved|unknown|hidden|visible) native2=(?:unobserved|unknown|hidden|visible)")

    /** The written content, or null when [identity] or [line] is outside the allowlist or a bound would be exceeded. */
    fun write(identity: String, line: String, folder: File = defaultFolder()): String? {
        if (!IDENTITY.matches(identity) || !LINE.matches(line)) return null
        val content = "test=$identity $line\n"
        if (content.toByteArray().size > MAX_BYTES) return null
        if (!folder.isDirectory && !folder.mkdirs()) return null
        val target = File(folder, "$identity.txt")
        if (!target.exists() && (folder.listFiles()?.size ?: 0) >= MAX_FILES) return null
        target.writeText(content)
        return content
    }

    private fun defaultFolder(): File {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) }
            ?: checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        return File(output, FOLDER)
    }
}
