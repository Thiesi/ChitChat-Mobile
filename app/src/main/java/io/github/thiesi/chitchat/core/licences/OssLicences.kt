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

    /** A licence text bundled next to the list, by its file name in [OssLicence.texts]. */
    fun text(assets: AssetManager, file: String): String =
        assets.open("licenses/$file").bufferedReader().use { it.readText() }
}
