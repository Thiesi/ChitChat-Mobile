package io.github.thiesi.chitchat.core.formatting

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** ChitChat v3.2.1's small plaintext grammar, not HTML or general Markdown.
 * Browser contract: public/assets/js/message-format.js at 4f53ff4 (BSD-2-Clause).
 * No global content cache, logging, network access, mention authority or link activation.
 */
internal object MessageFormatting {
    private const val MAX_INPUT_CHARS = 16_384
    private const val MAX_NODES = 4_096
    private const val MAX_DEPTH = 16

    fun parseMessage(text: String): FormattedMessage {
        if (text.length > MAX_INPUT_CHARS) return literalMessage(text)
        return try { FormattedMessage(blocks(text, Budget(text.length))) }
        catch (_: TooComplex) { literalMessage(text) }
    }

    fun parseInline(text: String): FormattedInline {
        if (text.length > MAX_INPUT_CHARS) return literalInline(text)
        return try { FormattedInline(inline(text, 0, text.length, 0, Budget(text.length))) }
        catch (_: TooComplex) { literalInline(text) }
    }

    /** A normalized candidate only, never permission to browse or use the authenticated transport.
     * Unsupported URLs, credentials and controls remain literal instead of becoming link actions.
     */
    fun safeLinkTarget(candidate: String): String? {
        if (candidate.length > MAX_INPUT_CHARS || !httpPrefix(candidate, 0) ||
            candidate.any { it <= '\u001f' || it in '\u007f'..'\u009f' || space(it) }) return null
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        // Keep candidates inside the same limit after Unicode/percent normalization as on export.
        return url.toString().takeIf { it.length <= MAX_INPUT_CHARS }
    }

    private fun literalMessage(text: String) = FormattedMessage(
        listOf(MessageBlock.Paragraph(listOf(MessageInline.Text(text)))), literalFallback = true)
    private fun literalInline(text: String) = FormattedInline(listOf(MessageInline.Text(text)), literalFallback = true)

    private fun blocks(text: String, budget: Budget): List<MessageBlock> {
        val lines = text.split('\n')
        val nextFence = IntArray(lines.size)
        var closing = -1
        for (index in lines.indices.reversed()) {
            budget.spend()
            nextFence[index] = closing
            if (trim(lines[index], budget) == "```") closing = index
        }
        val result = mutableListOf<MessageBlock>()
        var paragraph = mutableListOf<String>()
        var quote = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                budget.node()
                val body = paragraph.joinToString("\n")
                result += MessageBlock.Paragraph(inline(body, 0, body.length, 0, budget))
            }
            if (quote.isNotEmpty()) {
                budget.node()
                val body = quote.joinToString("\n")
                result += MessageBlock.Quote(inline(body, 0, body.length, 0, budget))
            }
            paragraph = mutableListOf(); quote = mutableListOf()
        }
        var index = 0
        while (index < lines.size) {
            budget.spend()
            val line = lines[index]
            val opening = trimEnd(line, budget)
            val end = nextFence[index]
            if (opening.startsWith("```") && end != -1) {
                flush(); budget.node()
                val first = opening.substring(3)
                val body = mutableListOf<String>()
                // The browser preserves a language-like suffix as code, not syntax metadata.
                if (trim(first, budget).isNotEmpty()) body += first
                for (at in index + 1 until end) { budget.spend(lines[at].length + 1); body += lines[at] }
                result += MessageBlock.CodeBlock(body.joinToString("\n"))
                index = end + 1
                continue
            }
            val quoted = line.startsWith('>') && line.none { it == '\r' || it == '\u2028' || it == '\u2029' }
            if (quoted) {
                if (paragraph.isNotEmpty()) flush()
                quote += line.substring(if (line.startsWith("> ")) 2 else 1)
            } else {
                if (quote.isNotEmpty()) flush()
                paragraph += line
            }
            index++
        }
        flush()
        return result.toList()
    }

    private fun inline(text: String, from: Int, until: Int, depth: Int, budget: Budget): List<MessageInline> {
        if (depth > MAX_DEPTH) throw TooComplex()
        val result = mutableListOf<MessageInline>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) { budget.node(); result += MessageInline.Text(plain.toString()); plain.setLength(0) }
        }
        var index = from
        while (index < until) {
            budget.spend()
            val escaped = index > from && text[index - 1] == '\\' && text[index] in "*_`"
            val token = if (escaped) null else when (text[index]) {
                '`' -> {
                    val end = marker(text, "`", index + 1, until, budget)
                    if (end <= index + 1) null else {
                        budget.spend(end - index); budget.node()
                        Token(end + 1, MessageInline.Code(text.substring(index + 1, end)))
                    }
                }
                'h', 'H' -> link(text, index, from, until, budget)
                '@' -> mention(text, index, until, budget)
                '*' -> emphasis(text, index, from, until, if (index + 1 < until && text[index + 1] == '*') "**" else "*", depth, budget)
                '_' -> emphasis(text, index, from, until, "_", depth, budget)
                else -> null
            }
            if (token == null) plain.append(text[index++])
            else {
                if (token.node != null) { flush(); result += token.node }
                else plain.append(text, index, token.end)
                index = token.end
            }
        }
        flush()
        return result.toList()
    }

    private fun emphasis(text: String, index: Int, from: Int, until: Int, mark: String, depth: Int, budget: Budget): Token? {
        val start = index + mark.length
        if (index > from && word(text[index - 1]) || start >= until || space(text[start]) || text[start] == mark[0]) return null
        var search = start
        while (search < until) {
            val end = marker(text, mark, search, until, budget)
            if (end == -1) return null
            val after = end + mark.length
            if (!space(text[end - 1]) && (after == until || !word(text[after])) && text[end - 1] != mark[0]) {
                budget.node()
                return Token(after, MessageInline.Styled(if (mark == "_") MessageInline.Emphasis.EMPHASIS else MessageInline.Emphasis.STRONG,
                    inline(text, start, end, depth + 1, budget)))
            }
            search = end + 1
        }
        return null
    }

    private fun marker(text: String, mark: String, start: Int, until: Int, budget: Budget): Int {
        var at = start
        while (at < until) {
            budget.spend()
            if (text[at] == '\n') return -1
            if (at + mark.length <= until && text.regionMatches(at, mark, 0, mark.length)) return at
            at++
        }
        return -1
    }

    private fun mention(text: String, index: Int, until: Int, budget: Budget): Token? {
        if (index + 1 >= until || !asciiWord(text[index + 1])) return null
        var at = index + 2
        while (at < until && at < index + 33 && (asciiWord(text[at]) || text[at] in "_.-")) { budget.spend(); at++ }
        return if (at - index - 1 >= 3) Token(at) else null
    }

    private fun link(text: String, index: Int, from: Int, until: Int, budget: Budget): Token? {
        if (index > from && word(text[index - 1]) || !httpPrefix(text, index)) return null
        val prefixLength = if (text.regionMatches(index, "https://", 0, 8, ignoreCase = true)) 8 else 7
        if (index + prefixLength >= until) return null
        var end = index + prefixLength
        while (end < until && !space(text[end]) && text[end] !in "<>\"'`") { budget.spend(); end++ }
        fun punctuation() { while (end > index && text[end - 1] in ".,;:!?'\"") { budget.spend(); end-- } }
        punctuation()
        var opening = 0; var closing = 0
        for (at in index until end) { budget.spend(); if (text[at] == '(') opening++; if (text[at] == ')') closing++ }
        while (end > index && text[end - 1] == ')' && opening < closing) { budget.spend(); end--; closing--; punctuation() }
        if (end - index <= 8) return null // Preserve the browser's shortest-link boundary.
        budget.spend(end - index)
        val raw = text.substring(index, end)
        val target = safeLinkTarget(raw)
        // Preserve rejected URL-shaped content literally, including any formatting markers.
        if (target == null) return Token(end)
        budget.node()
        return Token(end, MessageInline.Link(raw, target))
    }

    private fun httpPrefix(text: String, index: Int) = text.regionMatches(index, "http://", 0, 7, ignoreCase = true) ||
        text.regionMatches(index, "https://", 0, 8, ignoreCase = true)

    // ECMAScript whitespace, rather than platform-dependent trim/regex whitespace classes.
    private fun space(c: Char) = c in '\u0009'..'\u000d' || c == ' ' || c == '\u00a0' || c == '\u1680' ||
        c in '\u2000'..'\u200a' || c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000' || c == '\ufeff'
    private fun asciiWord(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'
    private fun word(c: Char) = when (c.category) {
        CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
        CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER, CharCategory.DECIMAL_DIGIT_NUMBER,
        CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER -> true
        else -> false
    }

    private fun trimEnd(text: String, budget: Budget): String {
        var end = text.length
        while (end > 0 && space(text[end - 1])) { budget.spend(); end-- }
        return text.substring(0, end)
    }
    private fun trim(text: String, budget: Budget): String {
        val value = trimEnd(text, budget)
        var start = 0
        while (start < value.length && space(value[start])) { budget.spend(); start++ }
        return value.substring(start)
    }

    private class Token(val end: Int, val node: MessageInline? = null)
    private class TooComplex : RuntimeException(null, null, false, false)
    private class Budget(length: Int) {
        private var work = 1_024 + length * 32
        private var nodes = 0
        fun spend(count: Int = 1) { work -= count; if (work < 0) throw TooComplex() }
        fun node() { if (++nodes > MAX_NODES) throw TooComplex() }
    }
}
