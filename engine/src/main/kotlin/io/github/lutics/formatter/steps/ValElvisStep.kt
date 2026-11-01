package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Step

// Port of join_val_elvis.py.
object ValElvisStep : Step {
    override val id = "val_elvis"

    private val VAL = Regex("""^\s*((private|internal|public|protected|override|const|lateinit|open)\s+)*(val|var)\s""")
    private val CONT = listOf(".", "?.", "?:")
    private val OPENERS = listOf("{", "(", "[")

    override fun apply(path: String, text: String, calls: CallRecords?): String {
        val lines = text.split("\n")
        val out = ArrayList<String>(lines.size)
        var i = 0
        var count = 0
        while (i < lines.size) {
            val line = lines[i]
            var j = i
            while (VAL.containsMatchIn(line) && j + 1 < lines.size &&
                CONT.any { lines[j + 1].pyTrimStart().startsWith(it) } &&
                OPENERS.none { lines[j].pyTrimEnd().endsWith(it) }
            ) {
                j++
            }
            var joined = line
            for (k in i + 1..j) {
                val nxt = lines[k].pyTrim()
                joined = joined.pyTrimEnd() + (if (nxt.startsWith("?:")) " " else "") + nxt
            }
            if (j > i && "?:" in joined) {
                out.add(joined)
                count++
                i = j + 1
            } else {
                out.add(line)
                i++
            }
        }
        return if (count > 0) out.joinToString("\n") else text
    }
}
