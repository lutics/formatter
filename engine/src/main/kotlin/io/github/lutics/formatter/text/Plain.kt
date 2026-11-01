package io.github.lutics.formatter.text

import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.TextRule

// *.toml and *.sql: LF line endings, no trailing whitespace and one final newline. A line break inside a TOML string
// keeps the spaces before it, which are the string's. In SQL a string literal, quoted name or dollar-quoted body keeps
// every byte, line endings included: E'a\r\nb' and a plpgsql body are values the database stores as written.
object Whitespace : TextRule {
    override val id = "whitespace"

    override fun applies(path: String) = path.endsWith(".toml") || path.endsWith(".sql")

    override fun format(path: String, text: String): String {
        if (text.isEmpty()) return text
        if (path.endsWith(".sql")) return sql(text)
        val lf = text.replace("\r\n", "\n").replace('\r', '\n')
        val kept = Toml.newlinesInStrings(lf)
        val sb = StringBuilder(lf.length)
        var lineStart = 0
        while (true) {
            val nl = lf.indexOf('\n', lineStart)
            val end = if (nl < 0) lf.length else nl
            var cut = end
            if (nl < 0 || nl !in kept) {
                while (cut > lineStart && (lf[cut - 1] == ' ' || lf[cut - 1] == '\t')) cut--
            }
            sb.append(lf, lineStart, cut)
            if (nl < 0) break
            sb.append('\n')
            lineStart = nl + 1
        }
        val body = sb.trimEnd('\n')
        return if (body.isEmpty()) "" else "$body\n"
    }

    // Outside the string literals, quoted names and dollar-quoted bodies (comments included): CRLF and CR to LF and the
    // spaces and tabs before each line break dropped. Inside them every byte stays.
    private fun sql(text: String): String {
        val sb = StringBuilder(text.length)
        // sb's length after the last span copied: what lies before it is the span's and is never trimmed
        var floor = 0
        fun outside(from: Int, to: Int) {
            var j = from
            while (j < to) {
                val c = text[j]
                if (c == '\r' || c == '\n') {
                    while (sb.length > floor && (sb.last() == ' ' || sb.last() == '\t')) sb.setLength(sb.length - 1)
                    sb.append('\n')
                    j += if (c == '\r' && j + 1 < to && text[j + 1] == '\n') 2 else 1
                } else {
                    sb.append(c)
                    j++
                }
            }
        }
        var i = 0
        for ((kind, s, e) in Sql.spans(text)) {
            if (kind == Sql.Span.COMMENT) continue
            outside(i, s)
            sb.append(text, s, e)
            floor = sb.length
            i = e
        }
        outside(i, text.length)
        // the file's end: trailing whitespace, blank lines and then one final newline, unless it ends inside a span
        while (sb.length > floor && sb.last().let { it == '\n' || it == ' ' || it == '\t' }) sb.setLength(sb.length - 1)
        if (sb.isEmpty()) return ""
        if (sb.length == floor && i == text.length && text.last().let { it == '\n' || it == '\r' }) return sb.toString()
        return "$sb\n"
    }
}

object Toml {

    // Offsets of the line breaks inside multi-line strings ("""…""" and '''…''').
    fun newlinesInStrings(text: String): Set<Int> {
        val out = mutableSetOf<Int>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '#' -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("\"\"\"", i) || text.startsWith("'''", i) -> {
                    val delimiter = text.substring(i, i + 3)
                    var j = i + 3
                    while (j < text.length && !(text.startsWith(delimiter, j) && (delimiter == "'''" || text[j - 1] != '\\'))) {
                        if (text[j] == '\n') out += j
                        j++
                    }
                    i = j + 3
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != c && text[j] != '\n') j += if (c == '"' && text[j] == '\\') 2 else 1
                    i = j + 1
                }
                else -> i++
            }
        }
        return out
    }

    // (table, key, offset) for every key at the top of a line outside brackets, values spanning lines included.
    fun keys(text: String): List<Triple<String, String, Int>> {
        val out = mutableListOf<Triple<String, String, Int>>()
        var table = ""
        var depth = 0
        var lineStart = 0
        val inString = newlinesInStrings(text)
        while (lineStart < text.length) {
            val nl = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, nl)
            val trimmed = line.trimStart()
            val startsInString = lineStart > 0 && (lineStart - 1) in inString
            if (depth == 0 && !startsInString) {
                if (trimmed.startsWith("[")) {
                    table = trimmed.removePrefix("[[").removePrefix("[").substringBefore(']').trim()
                } else {
                    KEY.find(line)?.let { m -> out += Triple(table, m.groupValues[1].removeSurrounding("\""), lineStart + m.groups[1]!!.range.first) }
                }
            }
            if (!(depth == 0 && trimmed.startsWith("["))) depth += bracketDelta(line)
            lineStart = nl + 1
        }
        return out
    }

    private val KEY = Regex("""^\s*("[^"]*"|[A-Za-z0-9_.-]+)\s*=""")

    // [ and { opened minus closed on a line, outside strings and comments.
    private fun bracketDelta(line: String): Int {
        var d = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when (c) {
                '#' -> return d
                '"', '\'' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != c) j += if (c == '"' && line[j] == '\\') 2 else 1
                    i = j
                }
                '[', '{' -> d++
                ']', '}' -> d--
            }
            i++
        }
        return d
    }
}

// gradle/libs.versions.toml: [versions] keys camelCase, [libraries]/[plugins]/[bundles] aliases kebab-case. Reported,
// never renamed: the build scripts reference them as libs.versions.x and libs.x.y.
object VersionCatalogNames : TextRule {
    override val id = "version-catalog-names"

    private val CAMEL = Regex("^[a-z][a-zA-Z0-9]*$")
    private val KEBAB = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

    override fun applies(path: String) = path.endsWith(".versions.toml")

    override fun findings(path: String, text: String): List<Finding> = Toml.keys(text).mapNotNull { (table, key, offset) ->
        when {
            table == "versions" && !CAMEL.matches(key) -> Finding(offset, "[versions] key '$key' is not camelCase; rename it and its libs.versions references by hand.")
            table in setOf("libraries", "plugins", "bundles") && !KEBAB.matches(key) -> Finding(offset, "[$table] alias '$key' is not kebab-case; rename it and its libs references by hand.")
            else -> null
        }
    }
}

object Sql {

    enum class Span { COMMENT, STRING, QUOTED, DOLLAR }

    // The comments, string literals, quoted identifiers and dollar-quoted bodies, as (kind, start, end).
    fun spans(text: String): List<Triple<Span, Int, Int>> {
        val out = mutableListOf<Triple<Span, Int, Int>>()
        var i = 0
        fun identifierChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')
        while (i < text.length) {
            val c = text[i]
            val start = i
            when {
                text.startsWith("--", i) -> {
                    // to the line break, CR included
                    while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
                    out += Triple(Span.COMMENT, start, i)
                }
                text.startsWith("/*", i) -> {
                    i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    out += Triple(Span.COMMENT, start, i)
                }
                c == '\'' -> {
                    // E'…' takes backslash escapes; every string takes ''
                    val escapes = text.getOrNull(i - 1)?.let { it == 'E' || it == 'e' } == true && !identifierChar(text.getOrNull(i - 2))
                    var j = i + 1
                    while (j < text.length) {
                        if (escapes && text[j] == '\\') {
                            j += 2
                            continue
                        }
                        if (text[j] == '\'') {
                            if (text.getOrNull(j + 1) == '\'') {
                                j += 2
                                continue
                            }
                            break
                        }
                        j++
                    }
                    i = minOf(j + 1, text.length)
                    out += Triple(Span.STRING, start, i)
                }
                c == '"' || c == '`' -> {
                    i = text.indexOf(c, i + 1).let { if (it < 0) text.length else it + 1 }
                    out += Triple(Span.QUOTED, start, i)
                }
                c == '$' && !identifierChar(text.getOrNull(i - 1)) && DOLLAR.matchAt(text, i) != null -> {
                    val delimiter = DOLLAR.matchAt(text, i)!!.value
                    i = text.indexOf(delimiter, i + delimiter.length).let { if (it < 0) text.length else it + delimiter.length }
                    out += Triple(Span.DOLLAR, start, i)
                }
                else -> i++
            }
        }
        return out
    }

    private val DOLLAR = Regex("""\$([A-Za-z_][A-Za-z_0-9]*)?\$""")
}

// A .sql file keeps one keyword case — UPPER or lower, whichever the file uses (the fleet has both by design). Counted
// outside comments, strings, quoted names and dollar-quoted bodies (a plpgsql body is code of its own), and only over
// Postgres's reserved keywords (RESERVED_KEYWORD and TYPE_FUNC_NAME_KEYWORD in src/include/parser/kwlist.h), which
// cannot be a bare name: an unreserved one (index, set, key, language, trigger, function, insert, by) can, and is
// written either way. Left out of those: the values and functions among them (null, true, current_date, user, left(…),
// current_schema), offset, which the rule's BigQuery files use as a name (WITH OFFSET AS offset), and the with of
// timestamp with time zone, a type name. Reported, never rewritten.
object SqlKeywordCase : TextRule {
    override val id = "sql-keyword-case"

    private val KEYWORDS = """
        all analyse analyze and any array as asc asymmetric both case cast check collate column constraint create default
        deferrable desc distinct do else end except fetch for foreign from grant group having in initially intersect into
        lateral leading limit not on only or order placing primary references returning select some symmetric table
        then to trailing union unique using variadic when where window with
        authorization binary collation concurrently cross freeze full ilike inner is isnull join like natural notnull outer
        overlaps similar tablesample verbose
    """.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    private val WORD = Regex("[A-Za-z_][A-Za-z_0-9]*")

    override fun applies(path: String) = path.endsWith(".sql")

    override fun findings(path: String, text: String): List<Finding> {
        val masked = StringBuilder(text)
        Sql.spans(text).forEach { (_, s, e) -> for (k in s until e) if (masked[k] != '\n') masked[k] = ' ' }
        val words = WORD.findAll(masked).toList()
        val hits = words.indices.mapNotNull { i ->
            val w = words[i]
            val lower = w.value.lowercase()
            if (lower !in KEYWORDS) return@mapNotNull null
            if (masked.getOrNull(w.range.first - 1) == '.' || masked.getOrNull(w.range.last + 1) == '.') return@mapNotNull null
            val next = words.getOrNull(i + 1)?.value?.lowercase()
            if (lower == "with" && next == "time") null else w
        }
        val upper = hits.count { it.value == it.value.uppercase() }
        val lower = hits.count { it.value == it.value.lowercase() }
        val other = hits.size - upper - lower
        val dominantUpper = upper >= lower
        val odd = hits.firstOrNull { if (dominantUpper) it.value != it.value.uppercase() else it.value != it.value.lowercase() } ?: return emptyList()
        return listOf(Finding(odd.range.first, "Keywords mix case outside comments, strings and \$\$ bodies ($upper UPPER, $lower lower, $other mixed); '${odd.value}' breaks the file's ${if (dominantUpper) "UPPER" else "lower"} case."))
    }
}
