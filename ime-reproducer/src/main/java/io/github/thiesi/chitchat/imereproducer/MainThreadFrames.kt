package io.github.thiesi.chitchat.imereproducer

/** Keep Java class/method names only; no arguments, source locations, thread names or raw lines. */
internal fun mainThreadFrames(raw: CharSequence, truncated: Boolean = false): List<String>? {
    if (truncated) return null
    val mainHeader = Regex("^\"main\".*\\btid=1\\b.*$")
    val frame = Regex("^\\s*at ([A-Za-z_$][A-Za-z_0-9$]*(?:\\.[A-Za-z_$][A-Za-z_0-9$]*)+)\\([^\\r\\n]*\\)\\s*$")
    var inMain = false
    var count = 0
    val frames = mutableListOf<String>()
    raw.lineSequence().forEach { line ->
        if (line.startsWith('"')) {
            inMain = mainHeader.matches(line)
            if (inMain) count++
        } else if (inMain && frames.size < 64) {
            frame.matchEntire(line)?.let { frames += it.groupValues[1] }
        }
    }
    return frames.toList().takeIf { count == 1 && it.isNotEmpty() }
}
