package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPrimaryConstructor

// At most one blank line anywhere, none at the end of the file or between a class name and its constructor.
object NoConsecutiveBlankLines : Rule {
    override val id = "no-consecutive-blank-lines"

    override fun edits(file: KtFile): List<Edit> =
        file.descendants<PsiWhiteSpace>().mapNotNull { ws ->
            // leading whitespace of a node is left alone
            if (ws.prevSibling == null) return@mapNotNull null
            val text = ws.text
            val newlines = text.count { it == '\n' }
            if (newlines < 2) return@mapNotNull null
            val keepBlank = PsiTreeUtil.nextLeaf(ws) != null && !isBetweenClassAndPrimaryConstructor(ws)
            if (newlines <= 2 && keepBlank) return@mapNotNull null
            Edit(ws.textRange.startOffset, ws.textRange.endOffset, text.substringBefore('\n') + "\n" + (if (keepBlank) "\n" else "") + text.substringAfterLast('\n'))
        }

    private fun isBetweenClassAndPrimaryConstructor(ws: PsiWhiteSpace): Boolean {
        val prev = generateSequence(PsiTreeUtil.prevLeaf(ws)) { PsiTreeUtil.prevLeaf(it) }
            .firstOrNull { it !is PsiWhiteSpace && !it.isComment() } ?: return false
        return prev.node.elementType == KtTokens.IDENTIFIER && prev.parent is KtClass && ws.nextSibling is KtPrimaryConstructor
    }
}
