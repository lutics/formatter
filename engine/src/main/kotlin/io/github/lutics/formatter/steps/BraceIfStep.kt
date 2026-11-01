package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Step

// Port of brace_if.py.
object BraceIfStep : Step {
    override val id = "brace_if"

    private const val LIMIT = 100
    private val EXPRESSION_BEFORE = listOf("(", ",", "=", "?:", "&&", "||")
    private val COMMENT = listOf("//", "*", "/*")
    private val HEAD = Regex("^($PY_SPACE*)if \\(")

    // `->` also ends a lambda's parameters, so an `else` before it is a when branch, not an else
    private val CONTINUATION = Regex("^(?:\\.|\\?\\.|else$NOT_WORD(?!$PY_SPACE*->))")
    private val STRING = Regex("\"(?:[^\"\\\\]++|\\\\.)*+\"", RegexOption.DOT_MATCHES_ALL)
    private val ELSE = Regex("(?<![\\p{L}\\p{N}_])else$NOT_WORD")

    private class Event(val line: Int, val col: Int, val c: String, val depth: Int)

    override fun apply(path: String, text: String, calls: CallRecords?): String {
        val lines = pyLines(text)
        val out = mutableListOf<String>()
        var i = 0
        var count = 0
        while (i < lines.size) {
            var m = HEAD.find(lines[i])
            if (m != null) {
                val prev = out.asReversed()
                    .firstOrNull { line -> line.pyTrimStart().let { it.isNotEmpty() && COMMENT.none(it::startsWith) } }
                    ?.substringBefore("//")?.pyTrimEnd().orEmpty()
                // an `if` expression (an argument, a `when` branch value, an assignment): the formatter's multiline-if-else braces it
                if (EXPRESSION_BEFORE.any { prev.endsWith(it) }) m = null
            }
            if (m == null) {
                out += lines[i]
                i++
                continue
            }
            var j = i
            var d = depthOf(lines[i])
            while (j + 1 < lines.size && (d > 0 || CONTINUATION.containsMatchIn(lines[j + 1].pyTrimStart()))) {
                j++
                d += depthOf(lines[j])
            }
            val stmt = lines.subList(i, j + 1)
            val new = if (j > i || lines[i].codePointCount(0, lines[i].length) > LIMIT) rebuild(stmt, m.groupValues[1]) else null
            if (new == null) {
                out += lines[i]
                i++
                continue
            }
            out += new
            count++
            i = j + 1
        }
        return if (count > 0) out.joinToString("\n") else text
    }

    private fun depthOf(line: String): Int = scanCode(line).sumOf { it.delta }

    // Brackets plus `else` markers over stmt, depth absolute from its first line.
    private fun events(stmt: List<String>): List<Event> {
        var depth = 0
        val out = mutableListOf<Event>()
        for ((k, line) in stmt.withIndex()) {
            val masked = STRING.replace(line) { "\"" + " ".repeat(it.value.length - 2) + "\"" }
            val elses = ELSE.findAll(masked).map { it.range.first }.toSet()
            val brackets = scanCode(line).filter { it.char in "()[]{}" }.associate { it.index to it.char }
            for (col in (brackets.keys + elses).sorted()) {
                val c = brackets[col]
                if (c == null) {
                    out += Event(k, col, "else", depth)
                } else if (c in "([{") {
                    out += Event(k, col, c.toString(), depth)
                    depth++
                } else {
                    depth--
                    out += Event(k, col, c.toString(), depth)
                }
            }
        }
        return out
    }

    // Text between positions a=(line, col) and b=(line, col) as stripped first line + following lines.
    private fun take(stmt: List<String>, a: Pair<Int, Int>, b: Pair<Int, Int>): List<String> {
        val (la, ca) = a
        val (lb, cb) = b
        if (la == lb) return listOf(stmt[la].pySlice(ca, cb).pyTrim())
        val middle = if (la + 1 < lb) stmt.subList(la + 1, lb) else emptyList()
        val parts = listOf(stmt[la].pySlice(ca).pyTrim()) + middle + listOf(stmt[lb].pySlice(0, cb).pyTrimEnd())
        return parts.filterNot { it.isPyBlank() }
    }

    private fun rebuild(stmt: List<String>, ind: String): List<String>? {
        val top = events(stmt).filter { it.depth == 0 }
        // each branch's condition (null for a bare `else`) and where its body starts; each `else` ends the branch before it
        val marks = mutableListOf<Pair<List<String>?, Pair<Int, Int>>>()
        val ends = mutableListOf<Pair<Int, Int>>()
        var wantCond = true // at the `if`, or after `else if`
        var k = 0
        while (k < top.size) {
            val e = top[k]
            if (e.c == "(" && wantCond) {
                val closeAt = (k + 1 until top.size).first { top[it].c == ")" }
                val close = top[closeAt]
                val after = close.line to close.col + 1
                marks += take(stmt, e.line to e.col, after) to after
                wantCond = false
                k = closeAt + 1
                continue
            }
            if (e.c == "else") {
                ends += e.line to e.col
                wantCond = stmt[e.line].pySlice(e.col + 4).pyTrimStart().startsWith("if (")
                if (!wantCond) marks += null to (e.line to e.col + 4)
            }
            k++
        }
        ends += stmt.size - 1 to stmt.last().length
        val branches = mutableListOf<Pair<List<String>?, List<String>>>()
        for ((m, e) in marks.zip(ends)) {
            val body = take(stmt, m.second, e)
            if (body.isEmpty() || body[0].startsWith("{")) return null
            branches += m.first to body
        }
        if (branches.size != ends.size) return null
        val out = mutableListOf<String>()
        for ((n, branch) in branches.withIndex()) {
            val (cond, body) = branch
            val head = if (!cond.isNullOrEmpty()) "if " + cond.joinToString("\n") + " " else ""
            out += if (n == 0) "$ind$head{" else "$ind} else $head{"
            out += "$ind    ${body[0]}"
            out += body.drop(1).map { "    $it" }
        }
        out += "$ind}"
        return out
    }
}
