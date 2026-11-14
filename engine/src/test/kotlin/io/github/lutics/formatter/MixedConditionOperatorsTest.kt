package io.github.lutics.formatter

import io.github.lutics.formatter.rules.control.MixedConditionOperators
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MixedConditionOperatorsTest {
    private val id = MixedConditionOperators.id
    private val rule = listOf(MixedConditionOperators)

    private fun fn(body: String) = "fun f(a: Boolean, b: Boolean, c: Boolean, d: Boolean, e: Boolean) {\n$body\n}\n"

    @Test
    fun `an && operand of || gets parentheses`() =
        Fixtures.assertFormats(id, fn("    if (a || b && c) println()"), fn("    if (a || (b && c)) println()"), rule)

    @Test
    fun `an && on the left of || gets parentheses`() =
        Fixtures.assertFormats(id, fn("    if (a && b || c) println()"), fn("    if ((a && b) || c) println()"), rule)

    @Test
    fun `a chain of && is wrapped whole`() =
        Fixtures.assertFormats(id, fn("    val x = a && b && c || d"), fn("    val x = (a && b && c) || d"), rule)

    @Test
    fun `both sides of one || are wrapped`() =
        Fixtures.assertFormats(id, fn("    val x = a && b || c && d"), fn("    val x = (a && b) || (c && d)"), rule)

    @Test
    fun `nested groups are wrapped at every level in one pass`() {
        val before = fn("    val x = a || b && (c || d && e)")
        val after = fn("    val x = a || (b && (c || (d && e)))")
        assertEquals(after, Engine.apply(before, MixedConditionOperators.edits(Engine.parse("x.kt", before))))
        assertEquals(after, Fixtures.format(before, rule))
        Fixtures.assertSettles(id, before)
    }

    @Test
    fun `a nested group ending where its outer group ends`() =
        Fixtures.assertFormats(
            id,
            fn("    val x = a || b && if (c) d else e || a && b"),
            fn("    val x = a || (b && if (c) d else e || (a && b))"),
            rule,
        )

    @Test
    fun `fleet shape with negated is checks and a call`() =
        Fixtures.assertFormats(
            id,
            "fun f(e: Throwable) {\n    if (e is A || e is B || e is C && e.code !in setOf(401, 403)) record(e)\n}\n",
            "fun f(e: Throwable) {\n    if (e is A || e is B || (e is C && e.code !in setOf(401, 403))) record(e)\n}\n",
            rule,
        )

    @Test
    fun `a when branch condition`() =
        Fixtures.assertFormats(
            id,
            fn("    when {\n        a || b && c -> Unit\n        else -> Unit\n    }"),
            fn("    when {\n        a || (b && c) -> Unit\n        else -> Unit\n    }"),
            rule,
        )

    @Test
    fun `a multi-line condition keeps its line breaks and comments and settles`() {
        val before = fn("    if (a ||\n        b && // why\n        c\n    ) {\n        println()\n    }")
        val after = Fixtures.format(before, rule)
        assertEquals(fn("    if (a ||\n        (b && // why\n        c)\n    ) {\n        println()\n    }"), after)
        Fixtures.assertSettles(id, before)
    }

    @Test
    fun `check reports the rewrite before format`() {
        assertTrue(Fixtures.reported(fn("    val x = a || b && c"), id).isNotEmpty())
    }

    @Test
    fun `check reports each group once, at its start, nested groups inside it included`() {
        val source = fn("    val x = a || b && (c || d && e)\n    val y = a && b || c")
        val offsets = Engine.check("x.kt", source, rule).filter { it.first == id }.map { it.second }
        assertEquals(listOf(source.indexOf("b && (c"), source.indexOf("a && b ||")), offsets)
    }

    @Test
    fun `already grouped or unmixed conditions are left alone`() {
        for (body in listOf("    val x = a || (b && c)", "    val x = (a && b) || c", "    val x = a && b && c", "    val x = a || b || c", "    val x = (a || b) && c")) {
            assertEquals(fn(body), Fixtures.format(fn(body), rule))
            assertEquals(emptyList<String>(), Fixtures.reported(fn(body), id))
        }
    }

    @Test
    fun `a lambda or call argument inside the operand is not a separate group`() =
        Fixtures.assertFormats(
            id,
            fn("    val x = a || listOf(b).any { it && c } && d"),
            fn("    val x = a || (listOf(b).any { it && c } && d)"),
            rule,
        )
}
