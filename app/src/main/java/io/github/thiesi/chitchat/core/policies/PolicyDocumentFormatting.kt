package io.github.thiesi.chitchat.core.policies

import io.github.thiesi.chitchat.core.formatting.MessageFormatting

/*
 * The display subset of a hosted policy document's Markdown (play-readiness.md §1, P-176). It is separate from the
 * chat grammar (MessageFormatting) and never renders server HTML:
 * - blocks: headings (a level-1 heading becomes level 2: the screen owns the title), paragraphs, list items, flattened
 *   block quotes, and code as plain text; thematic breaks are dropped;
 * - inlines: emphasis, strong, code as plain text, and links. Only http, https and mailto become links; other schemes
 *   keep their visible text. Images are dropped with their alt text. Raw HTML stays literal text.
 * Classification only: nothing here opens, fetches or logs anything. Input, work, node and depth bounds fall back to
 * literal paragraphs, never a blank document.
 */

sealed interface DocumentBlock {
    data class Heading(val level: Int, val content: List<DocumentInline>) : DocumentBlock
    data class Paragraph(val content: List<DocumentInline>) : DocumentBlock
    /** [indent] 0–3 (nesting depth, capped); [number] for ordered items. */
    data class ListItem(val ordered: Boolean, val number: Int?, val indent: Int, val content: List<DocumentInline>) : DocumentBlock
    /** Code, shown as plain body text with its line breaks. */
    data class Plain(val text: String) : DocumentBlock
}

sealed interface DocumentInline {
    data class Text(val text: String) : DocumentInline
    data class Strong(val content: List<DocumentInline>) : DocumentInline
    data class Emphasis(val content: List<DocumentInline>) : DocumentInline
    data class Code(val text: String) : DocumentInline
    /** A classified candidate only. [kind] decides how the screen offers it; nothing opens without an explicit choice. */
    data class Link(val content: List<DocumentInline>, val target: String, val kind: LinkKind) : DocumentInline
}

enum class LinkKind { HTTPS, HTTP, MAILTO }

data class FormattedDocument(val blocks: List<DocumentBlock>, val literalFallback: Boolean = false)

internal object PolicyDocumentFormatting {
    internal const val MAX_INPUT_CHARS = 200_000
    private const val MAX_NODES = 20_000
    private const val MAX_DEPTH = 8
    private const val WORK_PER_CHAR = 24

    fun parse(markdown: String): FormattedDocument {
        val text = markdown.replace("\r\n", "\n").replace('\r', '\n')
        if (text.length > MAX_INPUT_CHARS) return literal(text)
        return try { FormattedDocument(blocks(text, Budget(text.length))) }
        catch (_: TooComplex) { literal(text) }
    }

    /** The literal fallback: paragraphs split at blank lines, every character kept as text. */
    private fun literal(text: String) = FormattedDocument(
        text.split(Regex("\n[ \t]*\n")).filter { it.isNotBlank() }.map { DocumentBlock.Paragraph(listOf(DocumentInline.Text(it.trim()))) },
        literalFallback = true)

    private fun blocks(text: String, budget: Budget): List<DocumentBlock> {
        val result = mutableListOf<DocumentBlock>()
        var paragraph = mutableListOf<String>()
        var item: Pair<ListStart, MutableList<String>>? = null
        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            result += DocumentBlock.Paragraph(inlineLines(paragraph, budget)); budget.node(); paragraph = mutableListOf()
        }
        fun flushItem() {
            val (start, lines) = item ?: return
            result += DocumentBlock.ListItem(start.ordered, start.number, start.indent, inlineLines(lines, budget)); budget.node()
            item = null
        }
        val lines = text.split('\n')
        var index = 0
        while (index < lines.size) {
            budget.spend(lines[index].length + 1)
            val line = unquote(lines[index], budget)
            val stripped = line.trimStart(' ')
            val leading = line.length - stripped.length
            val fence = fenceOf(stripped).takeIf { leading <= 3 }
            when {
                fence != null -> {
                    flushParagraph(); flushItem()
                    val code = StringBuilder()
                    index++
                    while (index < lines.size) {
                        budget.spend(lines[index].length + 1)
                        val inner = unquote(lines[index], budget)
                        if (inner.trimStart(' ').startsWith(fence) && inner.trim().all { it == fence[0] }) break
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(inner)
                        index++
                    }
                    if (code.isNotEmpty()) { result += DocumentBlock.Plain(code.toString()); budget.node() }
                }
                stripped.isEmpty() -> { flushParagraph(); flushItem() }
                leading <= 3 && heading(stripped) != null -> {
                    flushParagraph(); flushItem()
                    val (level, body) = heading(stripped)!!
                    result += DocumentBlock.Heading(level, inline(body, 0, body.length, 0, budget)); budget.node()
                }
                leading <= 3 && thematicBreak(stripped) -> {
                    // A "---" under a paragraph is a setext heading (level 2 at most here); otherwise a dropped break.
                    if (paragraph.isNotEmpty() && stripped.all { it == '-' || it == ' ' } && item == null) {
                        result += DocumentBlock.Heading(2, inlineLines(paragraph, budget)); budget.node(); paragraph = mutableListOf()
                    } else { flushParagraph(); flushItem() }
                }
                leading <= 3 && paragraph.isNotEmpty() && item == null && stripped.all { it == '=' || it == ' ' } && stripped.trim().isNotEmpty() -> {
                    result += DocumentBlock.Heading(2, inlineLines(paragraph, budget)); budget.node(); paragraph = mutableListOf()
                }
                listStart(line) != null -> {
                    flushParagraph(); flushItem()
                    val start = listStart(line)!!
                    item = start to mutableListOf(start.body)
                }
                item != null && leading >= 2 -> item!!.second += stripped // A continuation of the open list item.
                else -> {
                    if (item != null) flushItem()
                    paragraph += if (leading >= 4) stripped else line.trimStart() // Indented code shows as plain text.
                }
            }
            index++
        }
        flushParagraph(); flushItem()
        return result
    }

    /** Strips block-quote markers (flattened to ordinary text). Deeper than [MAX_DEPTH] levels is too complex. */
    private fun unquote(line: String, budget: Budget): String {
        var current = line
        var depth = 0
        while (true) {
            budget.spend()
            val stripped = current.trimStart(' ')
            if (current.length - stripped.length > 3 || !stripped.startsWith('>')) return current
            if (++depth > MAX_DEPTH) throw TooComplex()
            current = stripped.substring(1).let { if (it.startsWith(' ')) it.substring(1) else it }
        }
    }

    private fun fenceOf(stripped: String): String? = when {
        stripped.startsWith("```") -> "```"
        stripped.startsWith("~~~") -> "~~~"
        else -> null
    }

    private fun heading(stripped: String): Pair<Int, String>? {
        val hashes = stripped.takeWhile { it == '#' }.length
        if (hashes !in 1..6 || (stripped.length > hashes && stripped[hashes] != ' ' && stripped[hashes] != '\t')) return null
        var body = stripped.substring(hashes).trim()
        // An optional closing sequence of #s (preceded by a space) is not part of the text.
        val closing = body.trimEnd('#')
        if (closing.length < body.length && (closing.isEmpty() || closing.endsWith(' '))) body = closing.trimEnd()
        return maxOf(2, hashes) to body
    }

    private fun thematicBreak(stripped: String): Boolean {
        val marks = stripped.filter { it != ' ' && it != '\t' }
        return marks.length >= 3 && marks.all { it == marks[0] } && marks[0] in "-*_"
    }

    private class ListStart(val ordered: Boolean, val number: Int?, val indent: Int, val body: String)

    private fun listStart(line: String): ListStart? {
        val stripped = line.trimStart(' ')
        val indent = ((line.length - stripped.length) / 2).coerceAtMost(3)
        if (stripped.length >= 2 && stripped[0] in "-*+" && stripped[1] == ' ') return ListStart(false, null, indent, stripped.substring(2).trimStart())
        val digits = stripped.takeWhile { it.isDigit() }
        if (digits.length in 1..9 && stripped.length > digits.length + 1 && stripped[digits.length] in ".)" &&
            stripped[digits.length + 1] == ' ') return ListStart(true, digits.toInt(), indent, stripped.substring(digits.length + 2).trimStart())
        return null
    }

    /** Lines of one paragraph: soft breaks become spaces; two trailing spaces or a backslash keep a line break. */
    private fun inlineLines(lines: List<String>, budget: Budget): List<DocumentInline> {
        val joined = StringBuilder()
        lines.forEachIndexed { i, raw ->
            val hard = raw.endsWith("  ") || raw.endsWith("\\")
            val line = raw.trimEnd().let { if (it.endsWith("\\")) it.dropLast(1) else it }
            joined.append(line)
            if (i < lines.lastIndex) joined.append(if (hard) '\n' else ' ')
        }
        val body = joined.toString()
        return inline(body, 0, body.length, 0, budget)
    }

    private fun inline(text: String, from: Int, until: Int, depth: Int, budget: Budget): List<DocumentInline> {
        if (depth > MAX_DEPTH) throw TooComplex()
        val out = mutableListOf<DocumentInline>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += DocumentInline.Text(plain.toString()); budget.node(); plain.clear() } }
        fun add(node: DocumentInline) { flush(); out += node; budget.node() }
        var i = from
        while (i < until) {
            budget.spend()
            val c = text[i]
            when {
                c == '\\' && i + 1 < until && text[i + 1].isAsciiPunctuation() -> { plain.append(text[i + 1]); i += 2 }
                c == '`' -> {
                    val run = run(text, i, until, '`')
                    val close = findRun(text, i + run, until, '`', run, budget)
                    if (close < 0) { plain.append(text, i, i + run); i += run }
                    else {
                        val code = text.substring(i + run, close).let { if (it.length >= 2 && it.startsWith(' ') && it.endsWith(' ')) it.substring(1, it.length - 1) else it }
                        add(DocumentInline.Code(code)); i = close + run
                    }
                }
                c == '!' && i + 1 < until && text[i + 1] == '[' -> {
                    val link = linkAt(text, i + 1, until, budget)
                    if (link == null) { plain.append(c); i++ } else i = link.end // An image: dropped, alt text too.
                }
                c == '[' -> {
                    val link = linkAt(text, i, until, budget)
                    if (link == null) { plain.append(c); i++ }
                    else {
                        val content = inline(text, link.textStart, link.textEnd, depth + 1, budget)
                        val target = classify(link.destination)
                        if (target != null) add(DocumentInline.Link(content, target.first, target.second))
                        else { flush(); out += content } // An unsupported scheme keeps only its visible text.
                        i = link.end
                    }
                }
                c == '<' -> {
                    val close = text.indexOf('>', i + 1).takeIf { it in (i + 1) until until } ?: -1
                    budget.spend(if (close < 0) 1 else close - i)
                    val inside = if (close > 0) text.substring(i + 1, close) else ""
                    val target = if (close > 0 && inside.none { it == ' ' || it == '<' }) classify(inside) else null
                    if (target == null) { plain.append(c); i++ }
                    else { add(DocumentInline.Link(listOf(DocumentInline.Text(inside)), target.first, target.second)); i = close + 1 }
                }
                (c == 'h' || c == 'H') && (i == from || !text[i - 1].isLetterOrDigit()) && bareStart(text, i, until) -> {
                    var end = i
                    while (end < until && !text[end].isWhitespace() && text[end] != '<') { budget.spend(); end++ }
                    while (end > i && text[end - 1] in ".,;:!?'\")") {
                        if (text[end - 1] == ')' && text.substring(i, end).count { it == '(' } >= text.substring(i, end).count { it == ')' }) break
                        end--
                    }
                    val candidate = text.substring(i, end)
                    val target = classify(candidate)
                    if (target == null) { plain.append(candidate); i = end }
                    else { add(DocumentInline.Link(listOf(DocumentInline.Text(candidate)), target.first, target.second)); i = end }
                }
                c == '*' || c == '_' -> {
                    val run = run(text, i, until, c)
                    val intraword = c == '_' && i > from && text[i - 1].isLetterOrDigit()
                    val opensOk = i + run < until && !text[i + run].isWhitespace() && !intraword
                    val emphasis = if (opensOk) delimited(text, i, until, c, run, depth, budget) else null
                    if (emphasis == null) { plain.append(text, i, i + run); i += run } else { add(emphasis.first); i = emphasis.second }
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return out
    }

    /** Strong for `**`/`__`, emphasis for `*`/`_`: the nearest matching closer that follows non-space text. */
    private fun delimited(text: String, start: Int, until: Int, mark: Char, run: Int, depth: Int, budget: Budget): Pair<DocumentInline, Int>? {
        val width = if (run >= 2) 2 else 1
        var j = start + width + 1
        while (j + width <= until) {
            budget.spend()
            if (text[j] == mark && run(text, j, until, mark) >= width && !text[j - 1].isWhitespace() &&
                (mark != '_' || j + width >= until || !text[j + width].isLetterOrDigit())) {
                val content = inline(text, start + width, j, depth + 1, budget)
                return (if (width == 2) DocumentInline.Strong(content) else DocumentInline.Emphasis(content)) to j + width
            }
            j++
        }
        return if (width == 2) delimited(text, start, until, mark, 1, depth, budget) else null
    }

    private class LinkSpan(val textStart: Int, val textEnd: Int, val destination: String, val end: Int)

    /** `[text](destination "optional title")` from [open], or null when it isn't one. */
    private fun linkAt(text: String, open: Int, until: Int, budget: Budget): LinkSpan? {
        var depth = 0
        var i = open
        while (i < until) {
            budget.spend()
            when (text[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> if (--depth == 0) break
            }
            i++
        }
        if (i >= until || i + 1 >= until || text[i + 1] != '(') return null
        val textEnd = i
        var j = i + 2
        while (j < until && text[j] == ' ') j++
        val destination: String
        if (j < until && text[j] == '<') {
            val close = text.indexOf('>', j + 1).takeIf { it in (j + 1) until until } ?: return null
            destination = text.substring(j + 1, close); j = close + 1
        } else {
            val begin = j
            var parens = 0
            while (j < until && !text[j].isWhitespace()) {
                budget.spend()
                if (text[j] == '(') parens++
                if (text[j] == ')') { if (parens == 0) break; parens-- }
                j++
            }
            destination = text.substring(begin, j)
        }
        while (j < until && text[j] == ' ') j++
        if (j < until && (text[j] == '"' || text[j] == '\'')) {
            val quote = text[j]
            val close = text.indexOf(quote, j + 1).takeIf { it in (j + 1) until until } ?: return null
            j = close + 1
            while (j < until && text[j] == ' ') j++
        }
        if (j >= until || text[j] != ')') return null
        return LinkSpan(open + 1, textEnd, destination, j + 1)
    }

    /** http(s) through the chat grammar's own safe-target check (no credentials or controls); mailto with an address. */
    private fun classify(candidate: String): Pair<String, LinkKind>? {
        if (candidate.startsWith("mailto:", ignoreCase = true)) {
            val address = candidate.substring(7)
            return if (address.isNotEmpty() && candidate.length <= 2048 && address.none { it.isWhitespace() || it.isISOControl() })
                ("mailto:$address" to LinkKind.MAILTO) else null
        }
        val safe = MessageFormatting.safeLinkTarget(candidate) ?: return null
        return safe to (if (safe.startsWith("https:", ignoreCase = true)) LinkKind.HTTPS else LinkKind.HTTP)
    }

    private fun bareStart(text: String, i: Int, until: Int) =
        (i + 8 <= until && text.regionMatches(i, "https://", 0, 8, ignoreCase = true)) ||
            (i + 7 <= until && text.regionMatches(i, "http://", 0, 7, ignoreCase = true))

    private fun run(text: String, from: Int, until: Int, mark: Char): Int {
        var end = from
        while (end < until && text[end] == mark) end++
        return end - from
    }

    private fun findRun(text: String, from: Int, until: Int, mark: Char, length: Int, budget: Budget): Int {
        var i = from
        while (i < until) {
            budget.spend()
            if (text[i] == mark) { val run = run(text, i, until, mark); if (run == length) return i; i += run } else i++
        }
        return -1
    }

    private fun Char.isAsciiPunctuation() = this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~'

    private class TooComplex : RuntimeException(null, null, false, false)
    private class Budget(length: Int) {
        private var work = length.toLong() * WORK_PER_CHAR + 4_096
        private var nodes = 0
        fun spend(count: Int = 1) { work -= count; if (work < 0) throw TooComplex() }
        fun node() { if (++nodes > MAX_NODES) throw TooComplex() }
    }
}
