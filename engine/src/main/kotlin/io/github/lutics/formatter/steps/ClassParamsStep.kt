package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Step

// Port of class_params.py (layout.split_params).
object ClassParamsStep : Step {
    override val id = "class_params"

    private const val MODS = "public|private|internal|protected|abstract|open|final|override|suspend|inline|operator|infix|tailrec|external|data|sealed|enum|inner|value|annotation"

    // Python's \s and \w are Unicode.
    private val DECL = Regex("(?U)^(\\s*)((?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*(?:(?:$MODS)\\s+)*)(fun|class|constructor)\\b")
    private val FUN_HEAD = Regex("(?U)\\s*(?:<[^()]*>\\s*)?[\\w.<>, ?]*?\\w\\s*\\(")
    private val CTOR_HEAD = Regex("(?U)\\s*\\(")

    // Python rewrites only a file that changed.
    override fun apply(path: String, text: String, calls: CallRecords?): String {
        val lines = pyLines(text)
        val s = lines.joinToString("\n")
        val new = lines.mapIndexed { i, line -> splitParams(lines, i) ?: line }.joinToString("\n")
        return if (new != s) new else text
    }

    // The line with a class/constructor or @Composable fun parameter list opened one parameter per line, or null.
    private fun splitParams(lines: List<String>, i: Int): String? {
        val line = lines[i]
        val m = DECL.find(line) ?: return null
        if (line.trimStart().startsWith("//")) return null
        val kw = m.groupValues[3]
        val composable = "@Composable" in m.groupValues[2] ||
            (maxOf(0, i - 3) until i).any { lines[it].trim() == "@Composable" }
        if (kw == "fun" && !composable) return null
        val pm = (if (kw == "constructor") CTOR_HEAD else FUN_HEAD).matchAt(line, m.range.last + 1) ?: return null
        val openAt = pm.range.last
        val commas = ArrayList<Int>()
        val close = matchClose(line, codeMask(line), openAt, commas) ?: return null
        if (line.substring(openAt + 1, close).isBlank()) return null
        // a set: with a trailing comma, comma + 1 is the close
        val positions = sortedSetOf(openAt + 1, close)
        for (c in commas) positions.add(c + 1)
        val sb = StringBuilder(line)
        for (p in positions.descendingSet()) sb.insert(p, '\n')
        return sb.toString()
    }
}
