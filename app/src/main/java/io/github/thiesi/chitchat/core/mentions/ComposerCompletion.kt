package io.github.thiesi.chitchat.core.mentions

/** Ephemeral editor snapshot. No generated representation, persistence or authorization. Offsets are UTF16. */
class ComposerEdit(val text: String, val start: Int, val end: Int = start, val composing: Boolean = false) {
    fun same(other: ComposerEdit) = text == other.text && start == other.start && end == other.end && composing == other.composing
    internal fun caret() = !composing && start == end && start in 0..text.length
}

class MentionToken internal constructor(val start: Int, val end: Int, val prefix: String)
private val mentionBeforeCaret = Regex("(?:^|[^A-Za-z0-9_.@-])@([A-Za-z0-9_.-]{0,32})\\z")
// ECMAScript whitespace: ASCII whitespace, Unicode separators and BOM; not NEL.
private val nameBeforeCaret = Regex("(^|[\\s\\p{Z}\\uFEFF])(@?)([A-Za-z0-9][A-Za-z0-9_.-]*)\\z")

fun mentionToken(edit: ComposerEdit): MentionToken? {
    if (!edit.caret()) return null
    val found = mentionBeforeCaret.find(edit.text.substring(0, edit.start)) ?: return null
    val prefix = found.groupValues[1]
    return MentionToken(edit.start - prefix.length - 1, edit.start, prefix)
}

fun insertMention(edit: ComposerEdit, token: MentionToken, username: String): ComposerEdit? {
    val actual = mentionToken(edit) ?: return null
    if (actual.start != token.start || actual.end != token.end || actual.prefix != token.prefix ||
        username.isBlank() || username.length > 80) return null
    val insertion = "@$username "
    return ComposerEdit(edit.text.substring(0, token.start) + insertion + edit.text.substring(token.end), token.start + insertion.length)
}

/** A bounded local cycle, tied to its exact complete output and selection. It cannot send a message. */
class NameCompletionCycle private constructor(private val original: ComposerEdit, private val start: Int,
    private val at: String, private val suffix: String, private val matches: List<String>, private val index: Int,
    val output: ComposerEdit) {
    fun next(current: ComposerEdit): NameCompletionCycle? {
        if (!current.same(output) || !current.caret()) return null
        return create(original, start, at, suffix, matches, (index + 1) % matches.size)
    }

    companion object {
        fun start(edit: ComposerEdit, names: List<String>): NameCompletionCycle? {
            if (!edit.caret()) return null
            val found = nameBeforeCaret.find(edit.text.substring(0, edit.start)) ?: return null
            val at = found.groupValues[2]; val prefix = found.groupValues[3]
            val matches = names.asSequence().take(1024).filter { it.isNotBlank() && it.length <= 80 && it.startsWith(prefix, ignoreCase = true) }
                .distinctBy { it.lowercase() }.toList()
            if (matches.isEmpty()) return null
            val start = edit.start - at.length - prefix.length
            return create(edit, start, at, if (start == 0 && at.isEmpty()) ": " else " ", matches, 0)
        }
        private fun create(original: ComposerEdit, start: Int, at: String, suffix: String,
            matches: List<String>, index: Int): NameCompletionCycle {
            val insertion = at + matches[index] + suffix
            val output = ComposerEdit(original.text.substring(0, start) + insertion + original.text.substring(original.end), start + insertion.length)
            return NameCompletionCycle(original, start, at, suffix, matches, index, output)
        }
    }
}
