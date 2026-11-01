package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Step

// Port of lambda_body.py.
object LambdaBodyStep : Step {
    override val id = "lambda_body"

    private val PARAMS = Regex("""(?dU)\s*[\w(][\w\s,:<>?.()*]*""")

    override fun apply(path: String, text: String, calls: CallRecords?): String {
        val s = text
        val mask = codeMask(s)
        val breaks = sortedSetOf<Pair<Int, Int>>(compareBy({ it.first }, { it.second })) // whitespace run [start, end) becomes a line break
        for (i in s.indices) {
            if (s[i] != '{' || !mask[i] || whenBody(s, mask, i)) continue
            val a = arrow(s, mask, i)
            if (a < 0) continue
            val body = a + 2
            val close = matchClose(s, mask, i) ?: continue
            if (s.substring(body, maxOf(body, close)).isPyBlank()) continue
            var end = body
            while (end < s.length && (s[end] == ' ' || s[end] == '\t')) end++
            if (end < s.length && s[end] != '\n') breaks.add(body to end)
            var start = close
            while (start > 0 && (s[start - 1] == ' ' || s[start - 1] == '\t')) start--
            if (start > 0 && s[start - 1] != '\n') breaks.add(start to close)
        }
        val out = StringBuilder(s)
        for ((start, end) in breaks.descendingSet()) out.replace(start, end, "\n")
        return out.toString()
    }

    // True when the `{` at i opens a `when` body: `when {` or `when (...) {`.
    private fun whenBody(s: String, mask: BooleanArray, i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && isPySpace(s[j])) j--
        if (j >= 0 && s[j] == ')' && mask[j]) {
            var depth = 0
            while (j >= 0) {
                if (mask[j] && s[j] == ')') {
                    depth++
                } else if (mask[j] && s[j] == '(') {
                    depth--
                    if (depth == 0) break
                }
                j--
            }
            j--
            while (j >= 0 && isPySpace(s[j])) j--
        }
        if (j < 3 || s.substring(j - 3, j + 1) != "when") return false
        return j < 4 || !isAlnumBefore(s, j - 3)
    }

    // Python's str.isalnum or `_` on the code point ending just before index at.
    private fun isAlnumBefore(s: String, at: Int): Boolean {
        val cp = s.codePointBefore(at)
        if (cp == '_'.code) return true
        if (Character.isLetterOrDigit(cp)) return true
        val t = Character.getType(cp)
        return t == Character.LETTER_NUMBER.toInt() || t == Character.OTHER_NUMBER.toInt()
    }

    // Index of the `->` closing the parameter list of the lambda opened at i, or -1 when it declares none.
    private fun arrow(s: String, mask: BooleanArray, i: Int): Int {
        var j = i + 1
        while (j < s.length && s[j] != '\n') {
            if (!mask[j] || s[j] in "{};=") return -1
            if (s.startsWith("->", j)) {
                val params = s.substring(i + 1, j)
                if (!PARAMS.matches(params) || params.count { it == '(' } != params.count { it == ')' }) return -1
                return j
            }
            j++
        }
        return -1
    }
}
