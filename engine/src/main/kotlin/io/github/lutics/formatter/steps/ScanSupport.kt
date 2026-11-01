package io.github.lutics.formatter.steps

// Python's str whitespace (what str.strip and re's \s use) is wider than Kotlin's and Java regex's.
internal const val PY_SPACE = "[\\t-\\r\\u001c-\\u0020\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]"

// Python's \b and \w are Unicode-aware; Java's are not.
internal const val NOT_WORD = "(?![\\p{L}\\p{N}_])"

// Python's str.isspace: Kotlin's isWhitespace lacks only \u0085.
internal fun isPySpace(c: Char) = c.isWhitespace() || c == '\u0085'

internal fun String.pyTrim() = trim(::isPySpace)

internal fun String.pyTrimStart() = trimStart(::isPySpace)

internal fun String.pyTrimEnd() = trimEnd(::isPySpace)

internal fun String.isPyBlank() = all(::isPySpace)

// s[from:to] with Python's clamping.
internal fun String.pySlice(from: Int, to: Int = length): String {
    val a = from.coerceIn(0, length)
    val b = to.coerceIn(0, length)
    return if (a >= b) "" else substring(a, b)
}

// Python's read_text() turns \r\n and \r into \n; a file the pass writes back is written that way.
internal fun pyLines(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")

// Occurrences of """ counted left to right without overlap, as str.count does.
internal fun tripleQuotes(line: String) = line.split("\"\"\"").size - 1

internal class CodeToken(val index: Int, val char: Char, val depth: Int) {
    val delta get() = if (char in "([{") 1 else if (char in ")]}") -1 else 0
}

// Brackets and commas outside strings and comments, with the ( [ { depth (scan.py).
internal fun scanCode(line: String): List<CodeToken> {
    val out = mutableListOf<CodeToken>()
    val n = line.length
    var i = 0
    var depth = 0
    // string modes: '"' or '3' (""") with template nesting: '$' inside ${ }, '{' inside a nested brace
    val stack = ArrayDeque<Char>()
    fun pushString(): Int {
        val triple = line.startsWith("\"\"\"", i)
        stack.addLast(if (triple) '3' else '"')
        return if (triple) 3 else 1
    }
    while (i < n) {
        val c = line[i]
        if (stack.isNotEmpty()) {
            val mode = stack.last()
            if (mode == '$') {
                if (c == '}') {
                    stack.removeLast()
                } else if (c == '"') {
                    i += pushString()
                    continue
                } else if (c == '{') {
                    stack.addLast('{')
                }
                i++
                continue
            }
            if (mode == '{') {
                if (c == '}') stack.removeLast()
                i++
                continue
            }
            if (c == '\\' && mode == '"') {
                i += 2
                continue
            }
            if (line.startsWith("\${", i)) {
                stack.addLast('$')
                i += 2
                continue
            }
            if (mode == '3' && line.startsWith("\"\"\"", i)) {
                stack.removeLast()
                i += 3
                continue
            }
            if (mode == '"' && c == '"') stack.removeLast()
            i++
            continue
        }
        if (line.startsWith("//", i)) return out
        if (c == '\'') {
            val j = line.indexOf('\'', if (line.getOrNull(i + 1) == '\\') i + 2 else i + 1)
            i = if (j > 0) j + 1 else n
            continue
        }
        if (c == '"') {
            i += pushString()
            continue
        }
        if (c in "([{") {
            out += CodeToken(i, c, depth)
            depth++
        } else if (c in ")]}") {
            depth--
            out += CodeToken(i, c, depth)
        } else if (c == ',') {
            out += CodeToken(i, c, depth)
        }
        i++
    }
    return out
}

// True where s[i] is code: outside string/char literals and comments (string templates count as string).
// Unlike scanCode it spans lines and does not look inside templates.
internal fun codeMask(s: String): BooleanArray {
    val n = s.length
    val mask = BooleanArray(n) { true }
    var i = 0
    while (i < n) {
        val j = when {
            s.startsWith("//", i) -> s.indexOf('\n', i).let { if (it < 0) n else it }
            s.startsWith("/*", i) -> s.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
            s.startsWith("\"\"\"", i) -> {
                var j = s.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
                while (j < n && s[j] == '"') j++
                j
            }
            s[i] == '"' || s[i] == '\'' -> {
                var j = i + 1
                while (j < n && s[j] != s[i] && s[j] != '\n') j += if (s[j] == '\\') 2 else 1
                minOf(j + 1, n)
            }
            else -> {
                i++
                continue
            }
        }
        mask.fill(false, i, j)
        i = j
    }
    return mask
}

// Index of the bracket closing the one at i, or null; commas collects the code commas directly inside it.
internal fun matchClose(s: String, mask: BooleanArray, i: Int, commas: MutableList<Int>? = null): Int? {
    var depth = 0
    for (j in i until s.length) {
        if (!mask[j]) continue
        val c = s[j]
        if (c in "([{") {
            depth++
        } else if (c in ")]}") {
            depth--
            if (depth == 0) return j
        } else if (c == ',' && depth == 1) {
            commas?.add(j)
        }
    }
    return null
}
