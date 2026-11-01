package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Step
import java.io.File

// Names the arguments of every Kotlin call with 2+ arguments that spans lines. Its keys are record-time lines and
// naming only prefixes lines, so it runs first on the recorded layout. Only a `kotlin` call is named, from the
// compiler's parameter per positional argument; a vararg's elements stay positional, as Kotlin requires. A call whose
// argument count the record does not match is reported.
object NameCallsStep : Step {
    override val id = "name_calls"

    // per file: the callees named, and the calls a person names by hand
    private val reports = sortedMapOf<String, Pair<List<String>, List<String>>>()

    override fun apply(path: String, text: String, calls: CallRecords?): String {
        val records = calls as? Calls ?: return text
        val lines = pyLines(text).toMutableList()
        val file = File(path)
        val canonical = file.canonicalPath
        val named = mutableListOf<String>()
        val byHand = mutableListOf<String>()
        // naming only prefixes lines, so the calls are found in the text the compiler saw
        for ((i, line) in lines.toList().withIndex()) {
            val call = callAt(line) ?: continue
            val key = "$canonical:${i + 1}"
            if (records.kinds[key] != "kotlin") continue
            val starts = argStarts(lines, i)
            val positional = starts.filter { !NAMED.containsMatchIn(lines[it]) }
            // one argument: JoinCallsStep joins it or names it, once its inner calls are joined
            if (positional.isEmpty() || starts.size == 1) continue
            // the compiler plugin's mapping: one parameter per positional argument, null for a vararg, whose elements
            // (all the remaining arguments) stay positional, as Kotlin requires
            val exact = records.names[key].orEmpty()
            if (null !in exact && exact.size != positional.size) {
                byHand += "${file.name}:${i + 1}: ${line.pyTrim()}"
                continue
            }
            val toName = positional.zip(exact).takeWhile { it.second != null }
            for ((j, name) in toName) lines[j] = lines[j].afterIndent("$name = ")
            if (toName.isNotEmpty()) named += call.groupValues[1]
        }
        reports[path] = named to byHand
        return if (named.isEmpty()) text else lines.joinToString("\n")
    }

    internal fun report(): List<String> {
        if (reports.isEmpty()) return emptyList()
        return listOf("  named: " + counts(reports.values.flatMap { it.first })) + reports.values.flatMap { it.second }.map { "  name by hand: $it" }
    }
}
