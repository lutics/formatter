package io.github.lutics.formatter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

// Runs rules the way the plugin does, on one file named x.kt.
object Fixtures {
    fun format(source: String, rules: List<Rule>): String = Engine.format("x.kt", source, rules)

    // What check reports for one rule id after the given rules ran.
    fun reported(source: String, id: String): List<String> =
        Engine.check("x.kt", source, RULES).filter { it.first == id }.map { it.third }

    // The text the given rules produce, then the whole rule set: it must settle (formatting its own output changes
    // nothing) and leave nothing for the rule id to report.
    fun assertFormats(id: String, before: String, after: String, rules: List<Rule>) {
        assertEquals(after, format(before, rules))
        assertSettles(id, before)
    }

    fun assertSettles(id: String, before: String) {
        val once = format(before, RULES)
        assertEquals(once, format(once, RULES), "the full rule set does not settle")
        assertEquals(emptyList<String>(), reported(once, id), "$id still reports after format")
    }

    // Untouched by the given rules, and still reported by check (which runs the full rule set).
    fun assertReported(id: String, source: String, rules: List<Rule>, reason: String? = null) {
        assertEquals(source, format(source, rules), "$id rewrote a case it must leave alone")
        val found = reported(source, id)
        assertTrue(found.isNotEmpty(), "$id does not report a case it leaves alone")
        reason?.let { expected -> assertTrue(found.any { expected in it }, "no finding names \"$expected\": $found") }
    }
}
