package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.psi.KtFile

// A file ends with a line break. Extra trailing blank lines are no-consecutive-blank-lines' concern.
object FinalNewline : Rule {
    override val id = "final-newline"

    override fun edits(file: KtFile): List<Edit> {
        val text = file.text
        if (text.isEmpty()) return emptyList()
        val trailing = text.substring(text.trimEnd().length)
        if ('\n' in trailing) return emptyList()
        return listOf(Edit(text.length, text.length, "\n"))
    }
}
