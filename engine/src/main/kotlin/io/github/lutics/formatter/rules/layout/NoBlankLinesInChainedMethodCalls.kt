package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile

// No blank line inside a `a.b\n\n.c` chain; a `?.` chain is not one either.
object NoBlankLinesInChainedMethodCalls : Rule {
    override val id = "no-blank-lines-in-chained-method-calls"

    override fun edits(file: KtFile): List<Edit> =
        file.descendants<PsiWhiteSpace>().mapNotNull { ws ->
            if (ws.parent !is KtDotQualifiedExpression || "\n\n" !in ws.text) return@mapNotNull null
            Edit(ws.textRange.startOffset, ws.textRange.endOffset, "\n" + ws.text.split("\n\n")[1])
        }
}
