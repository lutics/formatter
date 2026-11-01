package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import java.io.File

// A Unicode \w (Java's is ASCII; (?U) also counts U+FE0F as a word character).
private const val WORD = "\\p{L}\\p{N}_"

private val CALL = Regex("([$WORD]+)$PY_SPACE*(<[^()]*>)?\\(\$")
private val DECLARATION = Regex("(?<![$WORD])(fun|class|constructor|interface)(?![$WORD])")
private val TYPE_ARGUMENTS = Regex("(?<=[$WORD])<[$WORD$PY_SPACE,.?*:]*>")

// An argument that is already named, on a line or a stripped argument.
internal val NAMED = Regex("^$PY_SPACE*[$WORD]+$PY_SPACE*=[^=]")

// What the steps look up, keyed by `<canonical path>:<record-time line of the callee>`.
// kinds: `java` when the callee has no stable parameter names (Java, or a Kotlin function that opts out),
// `kotlin-vararg` when its first positional argument goes to a vararg (nothing can be named), `conflict` for two
// different calls on one line (the steps leave it alone), else `kotlin`. names: a `kotlin` call's parameter for each
// argument inside the parentheses that is not named yet, in order, null for a vararg element.
class Calls(val kinds: Map<String, String>, val names: Map<String, List<String?>>) : CallRecords {
    private class Arg(val start: Int, val param: String, val vararg: Boolean)

    companion object {
        private val CALL_ENDS_LINE = Regex("[$WORD]+$PY_SPACE*(<[^()]*>)?\\(")
        private val NAMED_BEFORE = Regex("(?<![=!<>])(?<![$WORD])[$WORD]+$PY_SPACE*=$PY_SPACE*\$")

        // The inverse of CallRecorder's record templates, which escape nothing.
        private val RECORD = Regex("""\{"file":"([^"]*)","callee":(-?\d+),"name":"([^"]*)","java":(true|false),"args":\[(.*)]}""")
        private val ARG = Regex("""\{"start":(-?\d+),"named":(?:true|false),"param":"([^"]*)","vararg":(true|false)}""")

        // Reads the call-recording compiler plugin's calls.jsonl. Runs once before any step, while the recorded files
        // still have the layout the compiler saw: its offsets become line keys against each file's current text.
        fun load(file: File): Calls {
            val kinds = HashMap<String, String>()
            val names = HashMap<String, List<String?>>()
            val texts = HashMap<String, Pair<String, String>?>()
            for (record in file.readLines()) {
                if (record.isBlank()) continue
                val (path, calleeAt, name, java, argList) = (RECORD.matchEntire(record) ?: error("not a call record: $record")).destructured
                // a generated source the record compile saw (build/generated) may be gone; no step walks it
                if (path !in texts) texts[path] = File(path).takeIf { it.isFile }?.let { it.readText().replace("\r\n", "\n").replace('\r', '\n') to it.canonicalPath }
                val (text, canonical) = texts[path] ?: continue
                val callee = offset(text, calleeAt)
                // a call the compiler adds at that spot: a delegate's getValue, a for loop's iterator
                if (!text.startsWith(name, callee)) continue
                // only a call whose `name(` ends its line is looked up by the steps (with no newline after it, the
                // text's last character is left out)
                val end = text.indexOf('\n', callee).let { if (it < 0) text.length - 1 else it }
                if (!CALL_ENDS_LINE.matches(text.pySlice(callee, end).pyTrim())) continue
                val key = "$canonical:${(0 until callee).count { text[it] == '\n' } + 1}"
                var args = ARG.findAll(argList).map { Arg(offset(text, it.groupValues[1]), it.groupValues[2], it.groupValues[3] == "true") }.toList()
                // a trailing lambda sits after the closing parenthesis
                val last = args.lastOrNull()?.start
                if (last != null && text.startsWith("{", last) && text.substring(0, last).pyTrimEnd().endsWith(")")) args = args.dropLast(1)
                // the plugin's argument list is the resolved one, where a named argument has lost its wrapper: read the
                // source, the 120 code points before the argument
                val positional = args.filter {
                    val from = if (text.codePointCount(0, it.start) <= 120) 0 else text.offsetByCodePoints(it.start, -120)
                    !NAMED_BEFORE.containsMatchIn(text.substring(from, it.start))
                }
                val kind = when {
                    java == "true" -> "java"
                    positional.firstOrNull()?.vararg == true -> "kotlin-vararg"
                    else -> "kotlin"
                }
                val mapping = if (kind == "kotlin") positional.map { if (it.vararg) null else it.param } else null
                if (key in kinds && (kinds[key] to names[key]) != (kind to mapping)) {
                    kinds[key] = "conflict"
                    names.remove(key)
                    continue
                }
                kinds[key] = kind
                if (mapping != null) names[key] = mapping
            }
            // the steps' reports, on stderr once they have seen every file
            Runtime.getRuntime().addShutdownHook(Thread { (NameCallsStep.report() + JoinCallsStep.report()).forEach(System.err::println) })
            return Calls(kinds, names)
        }

        // The plugin's offsets count UTF-16 units, as a Kotlin string does. It writes -1 for an argument with no
        // source, read as the end of the text.
        private fun offset(text: String, value: String): Int = value.toInt().let { if (it < 0) text.length else it }
    }
}

// The call opened at the end of a line (a declaration's own parameter list is not one; a call after its `=`, an
// expression body, is), as the `name(` match.
internal fun callAt(line: String): MatchResult? =
    CALL.find(line.pyTrimEnd())?.takeUnless { DECLARATION.containsMatchIn(line) && '=' !in line.substring(0, it.range.first) }

// Line indexes where each top-level argument of the call opened at the end of lines[i] starts.
internal fun argStarts(lines: List<String>, i: Int): List<Int> {
    var depth = 1
    val starts = mutableListOf<Int>()
    var j = i + 1
    var start = true
    while (j < lines.size && depth > 0) {
        val s = lines[j].pyTrim()
        if (start && depth == 1 && (s.isEmpty() || s.startsWith("//") || s.startsWith("/*") || s.startsWith("*"))) {
            j++
            continue // a comment or blank line above an argument is not where it starts
        }
        if (start && depth == 1 && !s.startsWith(")")) starts += j
        start = false
        for (t in scanCode(maskTypeArguments(lines[j]))) {
            when (t.char) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 1) start = true
            }
            if (depth == 0) break
        }
        j++
    }
    return starts
}

// Blank `<A, B>` after a name, innermost first, so its comma does not read as the end of an argument.
private fun maskTypeArguments(line: String): String {
    var s = line
    while (true) {
        val masked = TYPE_ARGUMENTS.replace(s) { " ".repeat(it.value.length) }
        if (masked == s) return s
        s = masked
    }
}

// The text goes after the line's indent.
internal fun String.afterIndent(text: String) = takeWhile(::isPySpace) + text + pyTrimStart()

// `name×count, …`: most first, ties in the order first seen
internal fun counts(names: List<String>) = names.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}×${it.value}" }
