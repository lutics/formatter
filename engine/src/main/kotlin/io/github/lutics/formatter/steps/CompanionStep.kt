package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Step

// Port of companion.py (with layout.companion_first and the helpers it uses).
object CompanionStep : Step {
    override val id = "companion"

    // UNIX_LINES (d): `^` follows only \n, as in Python's (?m).
    private val PRIVATE = Regex("""(?md)^(\s*)private companion object\b""")
    private val COMPANION = Regex("""(?md)^[ \t]*(?:(?:private|internal|public|protected)\s+)?companion\s+object\b""")
    private val OWNER = Regex("""\b(?:class|interface|object)\b""")
    private val ENUM_BEFORE = Regex("""\benum\s+$""")
    private val COMMENT_OR_ANNOTATION = listOf("//", "/*", "*", "@")

    override fun apply(path: String, text: String, calls: CallRecords?): String =
        companionFirst(PRIVATE.replace(text, "$1companion object"))

    private fun lineStart(s: String, i: Int): Int = s.lastIndexOf('\n', i - 1) + 1

    private fun nextLine(s: String, i: Int): Int {
        val j = s.indexOf('\n', i)
        return if (j < 0) s.length else j + 1
    }

    // Index of the unmatched `{` that encloses position i.
    private fun enclosingBrace(s: String, mask: BooleanArray, i: Int): Int? {
        var depth = 0
        for (j in i - 1 downTo 0) {
            if (!mask[j]) continue
            if (s[j] == '}') {
                depth++
            } else if (s[j] == '{') {
                if (depth == 0) return j
                depth--
            }
        }
        return null
    }

    // The companion object is the first member of its class body; an enum class's sits right after the entries' `;`.
    private fun companionFirst(input: String): String {
        var s = input
        repeat(100) {
            val mask = codeMask(s)
            var moved = false
            for (m in COMPANION.findAll(s)) {
                val start = m.range.first
                val mEnd = m.range.last + 1
                if (!mask[m.range.last]) continue
                val body = enclosingBrace(s, mask, start) ?: continue
                // The declaration that owns the body is the last class/interface/object keyword in code before its brace.
                val owner = OWNER.findAll(s.substring(0, body)).filter { mask[it.range.first] }.lastOrNull()
                var first = nextLine(s, body)
                if (owner != null && ENUM_BEFORE.containsMatchIn(s.substring(0, owner.range.first))) {
                    val close = matchClose(s, mask, body)
                    var depth = 0
                    var semi = -1
                    for (j in body + 1 until (close ?: s.length)) {
                        if (!mask[j]) continue
                        val c = s[j]
                        if (c in "([{") {
                            depth++
                        } else if (c in ")]}") {
                            depth--
                        } else if (c == ';' && depth == 0) {
                            semi = j
                            break
                        }
                    }
                    if (semi < 0) continue
                    first = nextLine(s, semi)
                }
                while (first < s.length && s.substring(first, nextLine(s, first)).isPyBlank()) first = nextLine(s, first)
                // The block carries the comment and annotation lines directly above it.
                var blockStart = lineStart(s, start)
                while (blockStart > first) {
                    val prev = lineStart(s, blockStart - 1)
                    val t = s.substring(prev, blockStart).pyTrim()
                    if (COMMENT_OR_ANNOTATION.none { t.startsWith(it) }) break
                    blockStart = prev
                }
                if (blockStart <= first) continue
                val brace = s.indexOf('{', mEnd)
                val lineEnd = nextLine(s, mEnd)
                val blockEnd = if (brace in 0 until lineEnd && mask[brace]) nextLine(s, matchClose(s, mask, brace) ?: continue) else lineEnd
                val block = s.substring(blockStart, blockEnd)
                var rest = s.substring(0, blockStart) + s.substring(blockEnd)
                // Drop the blank line the block leaves behind: the one above it, else the one below.
                val prev = lineStart(rest, blockStart - 1)
                val below = rest.substring(blockStart, nextLine(rest, blockStart)).pyTrim()
                if (rest.substring(prev, blockStart).isPyBlank() && (below.isEmpty() || below == "}")) {
                    rest = rest.substring(0, prev) + rest.substring(blockStart)
                } else if (below.isEmpty()) {
                    rest = rest.substring(0, blockStart) + rest.substring(nextLine(rest, blockStart))
                }
                s = rest.substring(0, first) + block + "\n" + rest.substring(first)
                moved = true
                break
            }
            if (!moved) return s
        }
        return s
    }
}
