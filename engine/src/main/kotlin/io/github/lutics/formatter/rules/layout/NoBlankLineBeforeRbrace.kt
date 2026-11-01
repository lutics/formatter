package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile

// No blank line directly before a closing brace.
object NoBlankLineBeforeRbrace : Rule {
    override val id = "no-blank-line-before-rbrace"

    override fun edits(file: KtFile): List<Edit> =
        file.descendants<PsiWhiteSpace>().mapNotNull { ws ->
            val text = ws.text
            if (text.count { it == '\n' } < 2) return@mapNotNull null
            if (PsiTreeUtil.nextLeaf(ws)?.node?.elementType != KtTokens.RBRACE) return@mapNotNull null
            Edit(ws.textRange.startOffset, ws.textRange.endOffset, text.substringBefore('\n') + "\n" + text.substringAfterLast('\n'))
        }
}
