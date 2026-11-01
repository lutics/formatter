package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Step

// Port of body_blank_line.py (with the layout.py helpers it uses).
object BodyBlankLineStep : Step {
    override val id = "body_blank_line"

    // The flags make ^, $ and \s, \w, \b read as Python's str patterns do (d: \n-only line ends, U: Unicode classes).
    private val OBJECT_HEAD = Regex("""(?sdU)^\s*(@\w+\s+)*((private|internal|public|data|sealed|fun|expect|actual)\s+)*(object|interface)\s+\w+.*\{\s*$""")
    private val CLASS_HEAD = Regex("""(?mdU)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|private|internal|protected|abstract|open|final|sealed|data|inner|value)\s+)*class\s+\w+""")
    private val ENUM_CLASS = Regex("""(?dU)\benum\s+class\b""")

    override fun apply(path: String, text: String, calls: CallRecords?): String = objectBlankLines(classBlankLines(text))

    // A class body opens with a blank line after its `{`; an enum class body does not.
    private fun classBlankLines(s: String): String {
        val mask = codeMask(s)
        val ins = sortedSetOf<Int>()
        for (m in CLASS_HEAD.findAll(s)) {
            if (!mask[m.range.last] || ENUM_CLASS.containsMatchIn(m.value)) continue
            var depth = 0
            var j = m.range.last + 1
            var body = -1
            while (j < s.length) {
                val c = s[j]
                if (mask[j]) {
                    if (c == '(' || c == '[' || c == '<') {
                        depth++
                    } else if ((c == ')' || c == ']' || c == '>') && s[j - 1] != '-') {
                        depth--
                    } else if (c == '{' && depth == 0) {
                        body = j
                        break
                    } else if (c == '\n' && depth == 0) {
                        val tail = s.substring(s.lastIndexOf('\n', j - 1) + 1, j).pyTrimEnd()
                        var n = j + 1
                        while (n < s.length && isPySpace(s[n])) n++
                        val next = if (n < s.length) s[n] else null
                        if (!(tail.endsWith(':') || tail.endsWith(',') || tail.endsWith('(') || next == ')' || next == ':' || next == ',')) break
                    }
                }
                j++
            }
            if (body < 0) continue
            var p = body + 1
            while (p < s.length && (s[p] == ' ' || s[p] == '\t')) p++
            if (p >= s.length || s[p] != '\n') continue
            // The line after the `{` line: blank or a lone `}` means no blank line is added.
            val nl = s.indexOf('\n', p + 1)
            val second = s.substring(p + 1, if (nl < 0) s.length else nl)
            if (second.isPyBlank() || second.pyTrim() == "}") continue
            ins.add(body + 1)
        }
        val out = StringBuilder(s)
        for (p in ins.descendingSet()) out.insert(p, '\n')
        return out.toString()
    }

    // An `object` or `interface` body opens with a blank line after its `{`.
    private fun objectBlankLines(s: String): String {
        val lines = s.split("\n")
        val out = ArrayList<String>(lines.size + 8)
        for ((k, line) in lines.withIndex()) {
            out.add(line)
            if (OBJECT_HEAD.containsMatchIn(line) && k + 1 < lines.size) {
                val next = lines[k + 1].pyTrim()
                if (next != "" && next != "}") out.add("")
            }
        }
        return out.joinToString("\n")
    }
}
