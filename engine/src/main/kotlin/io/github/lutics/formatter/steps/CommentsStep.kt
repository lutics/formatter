package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Step

// Port of comments.py.
object CommentsStep : Step {
    override val id = "comments"

    // Python's \s is Unicode and `.` takes any char of a line.
    private val OPEN = Regex("(?U)^(\\s*)/\\*\\*?(.*)$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.UNIX_LINES))
    private val STAR = Regex("(?U)^\\s*\\*")
    private val STAR_PREFIX = Regex("(?U)^\\s*\\* ?")

    // Python rewrites only a file it converted.
    override fun apply(path: String, text: String, calls: CallRecords?): String {
        val lines = pyLines(text)
        val out = ArrayList<String>()
        var count = 0
        var raw = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val m = if (raw) null else OPEN.matchEntire(line)
            if (m == null) {
                if (tripleQuotes(line) % 2 == 1) raw = !raw
                out.add(line)
                i++
                continue
            }
            val indent = m.groupValues[1]
            val body = ArrayList<String>()
            var j = i
            var part = m.groupValues[2]
            var after: String?
            while (true) {
                val end = part.indexOf("*/")
                if (end >= 0) {
                    body.add(part.substring(0, end))
                    after = part.substring(end + 2).trim()
                    break
                }
                body.add(part)
                j++
                if (j >= lines.size) {
                    after = null
                    break
                }
                part = lines[j]
            }
            if (after == null || after.isNotEmpty()) {
                out.add(line) // unterminated, or code after `*/`: not a whole-line comment
                i++
                continue
            }
            val texts = body.mapIndexed { k, b ->
                when {
                    k == 0 -> b.trim()
                    STAR.containsMatchIn(b) -> b.replace(STAR_PREFIX, "").trimEnd()
                    else -> (if (b.startsWith(indent)) b.substring(indent.length) else b.trimStart()).trimEnd()
                }
            }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            for (t in texts) out.add(if (t.isNotBlank()) "$indent// $t" else "$indent//")
            count++
            i = j + 1
        }
        return if (count == 0) text else out.joinToString("\n")
    }
}
