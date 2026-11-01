package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Step

// Port of open_calls.py.
object OpenCallsStep : Step {
    override val id = "open_calls"

    private val KEYWORDS = setOf("if", "while", "when", "for", "catch", "fun", "constructor", "class", "init", "return", "throw", "in", "is")
    private val CALLEE = Regex("([\\p{L}\\p{N}_]+)$PY_SPACE*(<[^()]*>)?\\z")
    private val DECLARATION = Regex("(?<![\\p{L}\\p{N}_])(?:fun|class|constructor|interface)$NOT_WORD")

    override fun apply(path: String, text: String, calls: CallRecords?): String {
        val lines = pyLines(text).toMutableList()
        var opened = 0
        while (openOnce(lines)) opened++
        return if (opened > 0) lines.joinToString("\n") else text
    }

    // (line, col) of the `)` matching the `(` at lines[i][col], or null.
    private fun closeOf(lines: List<String>, i: Int, col: Int): Pair<Int, Int>? {
        var depth = 0
        for (j in i until lines.size) {
            for (t in scanCode(lines[j])) {
                if (j == i && t.index < col) continue
                depth += t.delta
                if (depth == 0) return j to t.index
            }
        }
        return null
    }

    private fun openOnce(lines: MutableList<String>): Boolean {
        var raw = false // inside a multi-line """ string, which scanCode cannot see from one line
        for (i in lines.indices) {
            val line = lines[i]
            val wasRaw = raw
            raw = raw xor (tripleQuotes(line) % 2 == 1)
            if (wasRaw || raw || DECLARATION.containsMatchIn(line)) continue
            for (t in scanCode(line)) {
                val col = t.index
                if (t.char != '(' || line.substring(col + 1).isPyBlank()) continue
                val callee = CALLEE.find(line.substring(0, col)) ?: continue
                if (callee.groupValues[1] in KEYWORDS) continue
                val (j, close) = closeOf(lines, i, col) ?: continue
                if (j == i) continue
                val indent = line.takeWhile(::isPySpace)
                val before = lines[j].substring(0, close)
                if (!before.isPyBlank()) {
                    lines.add(j + 1, indent + lines[j].substring(close).pyTrimStart())
                    lines[j] = before.pyTrimEnd()
                }
                lines[i] = line.substring(0, col + 1)
                lines.add(i + 1, "$indent    " + line.substring(col + 1).pyTrimStart())
                return true
            }
        }
        return false
    }
}
