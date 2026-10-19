package io.github.thiesi.chitchat.core.session

/** No shell evaluation, logging or implicit environment fallback. Test code only. */
internal fun parseLiveConfiguration(lines: List<String>): Map<String, String> {
    val required = setOf("CHITCHAT_TEST_SERVER", "CHITCHAT_TEST_USERNAME", "CHITCHAT_TEST_PASSWORD")
    val result = mutableMapOf<String, String>()
    lines.forEach { original ->
        val line = original.removePrefix("\uFEFF")
        if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
        val separator = line.indexOf('=')
        check(separator > 0) { "Integration configuration must use KEY=value lines" }
        val key = line.substring(0, separator).trim()
        if (key !in required) return@forEach
        var value = line.substring(separator + 1)
        if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\''))) value = value.substring(1, value.length - 1)
        check(key !in result && value.isNotEmpty()) { "Integration configuration has duplicate or empty required keys" }
        result[key] = value
    }
    check(result.keys.containsAll(required)) { "Integration configuration needs all three CHITCHAT_TEST_* keys" }
    return result
}
