package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

// ij_kotlin_line_break_after_multiline_when_entry = false: no blank line between when entries.
object BlankLineBetweenWhenConditions : Rule {
    override val id = "blank-line-between-when-conditions"

    override fun edits(file: KtFile): List<Edit> =
        file.descendants<KtWhenExpression>().flatMap { whenExpr ->
            whenExpr.entries.drop(1).mapNotNull { entry -> blankBefore(entry) }
        }

    private fun blankBefore(entry: KtWhenEntry): Edit? {
        val prev = generateSequence(entry.prevSibling) { it.prevSibling }.firstOrNull { it.isCode() } ?: return null
        val leaf = generateSequence(prev) { it.lastChild }.last()
        val ws = PsiTreeUtil.nextLeaf(leaf) as? PsiWhiteSpace ?: return null
        if (ws.text.count { it == '\n' } < 2) return null
        return Edit(ws.textRange.startOffset, ws.textRange.endOffset, "\n" + entry.lineIndent())
    }
}
