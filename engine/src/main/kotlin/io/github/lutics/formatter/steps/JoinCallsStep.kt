package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Step
import java.io.File

// Joins a call back onto one line when its arguments cannot be named (a `java` or `kotlin-vararg` callee) or when it
// has a single argument that fits on one line, and names a Kotlin call's single argument that still spans lines. Only
// calls whose arguments each sit on one line are joined, innermost first. Keys are record-time lines: it runs after
// NameCallsStep (which keeps line counts), from the bottom of each file up.
object JoinCallsStep : Step {
    override val id = "join_calls"

    private val CONDITION = Regex("^$PY_SPACE*(\\} else )?(if|while|else if) \\(")

    // per file: the callees joined, the callees whose one argument was named, and the calls left split because the
    // compiler did not classify them
    private val reports = sortedMapOf<String, Triple<List<String>, List<String>, List<String>>>()

    override fun apply(path: String, text: String, calls: CallRecords?): String {
        val lines = pyLines(text).toMutableList()
        var count = joinAll(lines, path, calls as? Calls)
        while (closeConditions(lines)) count++
        return if (count == 0) text else lines.joinToString("\n")
    }

    internal fun report(): List<String> {
        if (reports.isEmpty()) return emptyList()
        val all = reports.values
        return listOf("  joined: " + counts(all.flatMap { it.first }), "  named its one argument: " + counts(all.flatMap { it.second })) +
            all.flatMap { it.third }.toSortedSet().map { "  left split: $it" }
    }

    // From the last line up, so a key's record-time line is still where the call sits, and an inner call joins
    // before the call around it.
    private fun joinAll(lines: MutableList<String>, path: String, records: Calls?): Int {
        val file = File(path)
        val canonical = file.canonicalPath
        val kinds = records?.kinds.orEmpty()
        val names = records?.names.orEmpty()
        val joined = mutableListOf<String>()
        val namedOne = mutableListOf<String>()
        val leftSplit = mutableListOf<String>()
        var count = 0
        for (i in lines.size - 1 downTo 0) {
            val line = lines[i]
            val callee = callAt(line) ?: continue
            val key = "$canonical:${i + 1}"
            val starts = argStarts(lines, i)
            // a single Kotlin argument that still spans lines once the calls inside it are joined is named instead
            if (kinds[key] == "kotlin" && starts.size == 1 && !NAMED.containsMatchIn(lines[starts[0]].pyTrim()) &&
                starts[0] + 1 < lines.size && !lines[starts[0] + 1].pyTrimStart().startsWith(")")
            ) {
                val param = names[key]?.firstOrNull()
                if (!param.isNullOrEmpty()) {
                    lines[starts[0]] = lines[starts[0]].afterIndent("$param = ")
                    namedOne += callee.groupValues[1]
                    count++
                }
                continue
            }
            // one argument per line; the call's `)` opens its own line, or (after a last argument that ends in a
            // lambda) closes the last argument's line
            val args = mutableListOf<String>()
            var j = i + 1
            var rest: String? = null
            while (j < lines.size) {
                val tokens = scanCode(lines[j])
                // the line's first bracket below its own depth is the call's `)`
                val close = tokens.firstOrNull { it.depth < 0 }
                if (close != null) {
                    rest = lines[j].substring(close.index)
                    val last = lines[j].substring(0, close.index).pyTrim()
                    if (last.isNotEmpty()) args += last
                    break
                }
                if (tokens.count { it.char in "([{" } != tokens.count { it.char in ")]}" }) break
                val a = lines[j].pyTrim()
                if (a.isEmpty() || a.startsWith("//")) break
                args += a
                j++
            }
            if (rest == null || args.isEmpty()) continue
            if (args.dropLast(1).any { !it.endsWith(",") }) continue
            val kind = kinds[key] ?: "unknown"
            val anyNamed = args.any { NAMED.containsMatchIn(it) }
            if (args.size > 1 && kind != "java" && kind != "kotlin-vararg") {
                if (kind == "unknown" && !anyNamed) leftSplit += "unknown: ${file.name}:${i + 1}: ${line.pyTrim()}"
                continue
            }
            if (anyNamed) continue // a single named argument stays as written
            joined += callee.groupValues[1]
            lines[i] = line.pyTrimEnd() + args.joinToString(", ") { it.trimEnd(',') } + rest.pyTrimStart()
            lines.subList(i + 1, j + 1).clear()
            count++
        }
        reports[path] = Triple(joined, namedOne, leftSplit)
        return count
    }

    // A condition that fits on one line after its calls were joined takes back the `)` AsFormatStep put below it.
    private fun closeConditions(lines: MutableList<String>): Boolean {
        for (i in 0 until lines.size - 1) {
            if (CONDITION.containsMatchIn(lines[i]) && lines[i + 1].pyTrim().startsWith(")") &&
                lines[i].count { it == '(' } == lines[i].count { it == ')' } + 1
            ) {
                lines[i] = lines[i].pyTrimEnd() + lines[i + 1].pyTrim()
                lines.removeAt(i + 1)
                return true
            }
        }
        return false
    }
}
