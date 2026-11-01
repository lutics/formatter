package io.github.lutics.formatter

import io.github.lutics.formatter.text.ManifestSections
import io.github.lutics.formatter.text.SqlKeywordCase
import io.github.lutics.formatter.text.VersionCatalogNames
import io.github.lutics.formatter.text.Whitespace
import io.github.lutics.formatter.text.XmlLayout

// A rule over a file that is not Kotlin: it sees the real path, since what it does depends on it (a res/values file
// keeps its attributes on one line; the version catalog and the manifest have rules of their own).
interface TextRule {
    val id: String

    fun applies(path: String): Boolean

    // The whole file brought into line; the text itself when it already is.
    fun format(path: String, text: String): String = text

    // What the rule reports but never rewrites.
    fun findings(path: String, text: String): List<Finding> = emptyList()
}

// The text rules in the order they run; the manifest's sections before the layout, whose output they already are.
val TEXT_RULES: List<TextRule> = listOf(
    ManifestSections,
    XmlLayout,
    Whitespace,
    VersionCatalogNames,
    SqlKeywordCase,
)

// Every file the formatter takes, dispatched by extension: *.kt and *.kts to the PSI rules, the rest to the text rules
// that apply to its path.
object Files {

    fun isKotlin(path: String) = path.endsWith(".kt") || path.endsWith(".kts")

    fun supported(path: String, textRules: List<TextRule> = TEXT_RULES) = isKotlin(path) || textRules.any { it.applies(path) }

    fun format(path: String, text: String, rules: List<Rule> = RULES, textRules: List<TextRule> = TEXT_RULES): String {
        if (isKotlin(path)) return Engine.format(path, text, rules)
        val applicable = textRules.filter { it.applies(path) }
        var s = text
        repeat(4) {
            val before = s
            for (rule in applicable) s = rule.format(path, s)
            if (s == before) return s
        }
        return s
    }

    // As Engine.check: one finding per rule that would still rewrite the file (at the first offset it changes), and
    // one per report.
    fun check(path: String, text: String, rules: List<Rule> = RULES, textRules: List<TextRule> = TEXT_RULES): List<Triple<String, Int, String>> {
        if (isKotlin(path)) return Engine.check(path, text, rules)
        return textRules.filter { it.applies(path) }.flatMap { rule ->
            val formatted = rule.format(path, text)
            val edit = if (formatted == text) emptyList() else listOf(Triple(rule.id, text.commonPrefixWith(formatted).length.coerceAtMost(text.length), "not formatted"))
            edit + rule.findings(path, text).map { Triple(rule.id, it.offset, it.detail) }
        }
    }
}
