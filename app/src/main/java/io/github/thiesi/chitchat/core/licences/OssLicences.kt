package io.github.thiesi.chitchat.core.licences

import android.content.res.AssetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One entry of the in-app open-source licences list (`assets/licenses/oss_licenses.json`). [covers] lists
 * the `group:artifact` rules (with `*`) that `:app:checkLicenceCoverage` matches against the release
 * runtime classpath, so a shipped artifact can't go uncredited.
 */
@Serializable
data class OssLicence(
    val name: String,
    val version: String,
    val license: String,
    val source: String,
    val copyright: String? = null,
    val texts: List<String>,
    val covers: List<String>,
)

object OssLicences {
    const val LIST = "licenses/oss_licenses.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<OssLicence> = json.decodeFromString(text)

    fun load(assets: AssetManager): List<OssLicence> =
        assets.open(LIST).bufferedReader().use { parse(it.readText()) }

    /**
     * Reflows a plain-text licence for a phone screen without changing a word: blank lines separate
     * paragraphs, single line breaks inside a paragraph become spaces, and indentation is trimmed. A
     * centred heading (indented by [HEADING_INDENT] or more) stays a paragraph of its own.
     */
    fun paragraphs(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() { if (current.isNotEmpty()) { result += current.toString(); current.clear() } }
        for (raw in text.replace("\r\n", "\n").replace('\u000C', '\n').lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                raw.length - raw.trimStart().length >= HEADING_INDENT -> { flush(); result += line }
                else -> { if (current.isNotEmpty()) current.append(' '); current.append(line) }
            }
        }
        flush()
        return result
    }

    private const val HEADING_INDENT = 10

    /** A licence text bundled next to the list, by its file name in [OssLicence.texts]. */
    fun text(assets: AssetManager, file: String): String =
        assets.open("licenses/$file").bufferedReader().use { it.readText() }
}
