package io.github.thiesi.chitchat.core.licences

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * The in-app list can't drift from the build: every runtime dependency declared in the version catalog
 * has its own entry with the catalog's version. Transitive artifacts are checked by the Gradle task
 * `:app:checkLicenceCoverage` against the resolved release runtime classpath.
 */
class OssLicencesTest {
    private val module = File(".").canonicalFile.let { if (File(it, "src/main/assets").isDirectory) it else File(it, "app") }
    private val assets = File(module, "src/main/assets/licenses")
    private val licences = OssLicences.parse(File(assets, "oss_licenses.json").readText())

    @Test fun everyEntryIsCompleteAndItsTextsAreBundled() {
        assertTrue(licences.isNotEmpty())
        for (licence in licences) {
            assertTrue(licence.name, licence.name.isNotBlank() && licence.version.isNotBlank() && licence.license.isNotBlank())
            assertTrue("${licence.name}: source must be HTTPS", licence.source.startsWith("https://"))
            assertTrue("${licence.name}: no licence text", licence.texts.isNotEmpty())
            for (text in licence.texts) assertTrue("${licence.name}: missing $text", File(assets, text).isFile)
        }
        val rules = licences.flatMap { it.covers }
        assertEquals("No rule appears twice", rules.size, rules.toSet().size)
    }

    @Test fun licencesThatNeedANoticeCarryIt() {
        fun entry(artifact: String) = licences.single { artifact in it.covers }
        assertEquals("Copyright 2008 Google Inc.", entry("com.google.protobuf:protobuf-java").copyright)
        assertTrue(entry("com.google.code.findbugs:jsr305").texts.containsAll(listOf("apache-2.0.txt", "bsd-3-jsr305.txt")))
        val fcm = entry("org.unifiedpush.android:embedded-fcm-distributor")
        assertEquals(listOf("lgpl-2.1.txt"), fcm.texts)
        assertTrue("Source at the tag", fcm.source.endsWith("/src/tag/3.1.0"))
        assertNotNull(fcm.copyright)
    }

    @Test fun everyCatalogRuntimeDependencyHasItsOwnEntryAtTheCatalogVersion() {
        val catalog = File(module.parentFile, "gradle/libs.versions.toml").readLines()
        val versions = section(catalog, "versions").associate { line -> line.substringBefore('=').trim() to value(line) }
        val libraries = section(catalog, "libraries").associate { line ->
            line.substringBefore('=').trim() to (Regex("""module = "([^"]+)"""").find(line)!!.groupValues[1] to
                Regex("""version\.ref = "([^"]+)"""").find(line)?.groupValues?.get(1)?.let(versions::getValue))
        }
        val build = File(module, "build.gradle.kts").readText()
        val runtime = Regex("""^\s*implementation\((?:platform\()?libs\.([a-z0-9.]+)\)""", RegexOption.MULTILINE)
            .findAll(build).map { it.groupValues[1].replace('.', '-') }.toList()
        assertTrue("Catalog runtime dependencies found", runtime.size >= 10)
        for (alias in runtime) {
            val (artifact, version) = libraries.getValue(alias)
            val entry = licences.singleOrNull { artifact in it.covers }
            assertNotNull("$artifact needs its own entry in oss_licenses.json", entry)
            // BOM-managed Compose entries show the BOM version.
            if (version != null) assertTrue("$artifact version $version, entry says ${entry!!.version}",
                entry.version == version || entry.version == "BOM $version")
        }
    }

    private fun section(lines: List<String>, name: String) = lines.dropWhile { it.trim() != "[$name]" }.drop(1)
        .takeWhile { !it.startsWith("[") }.filter { it.isNotBlank() && !it.trimStart().startsWith("#") && '=' in it }

    private fun value(line: String) = line.substringAfter('=').trim().removeSurrounding("\"")
}
