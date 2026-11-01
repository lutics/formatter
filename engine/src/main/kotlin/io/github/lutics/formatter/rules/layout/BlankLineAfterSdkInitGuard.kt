package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtReturnExpression

// `if (!MobileAds.isInitialized) return …` is a statement group of its own: one blank line follows it, even when another
// guard comes next. As the last statement of a block it gets none, which keeps it out of no-blank-line-before-rbrace's way.
object BlankLineAfterSdkInitGuard : Rule {
    override val id = "blank-line-after-sdk-init-guard"

    override fun edits(file: KtFile): List<Edit> =
        file.descendants<KtIfExpression>().mapNotNull { guard ->
            if (!guard.isSdkInitGuard()) return@mapNotNull null
            val ws = lineBreakAfter(guard) ?: return@mapNotNull null
            if (ws.text.count { it == '\n' } >= 2) return@mapNotNull null
            Edit(ws.textRange.startOffset, ws.textRange.endOffset, "\n\n" + ws.text.substringAfterLast('\n'))
        }

    private fun KtIfExpression.isSdkInitGuard(): Boolean {
        if (parent !is KtBlockExpression || `else` != null || !then.isLoneReturn()) return false
        val negation = condition as? KtPrefixExpression ?: return false
        if (negation.operationToken != KtTokens.EXCL) return false
        val access = negation.baseExpression as? KtDotQualifiedExpression ?: return false
        return (access.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() == "MobileAds" &&
            (access.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() == "isInitialized"
    }

    // multiline-if-else braces a guard whose `return <expr>` spans lines, so `{ return … }` is the same guard.
    private fun PsiElement?.isLoneReturn(): Boolean =
        this is KtReturnExpression || (this is KtBlockExpression && statements.singleOrNull() is KtReturnExpression)

    // The whitespace that ends the guard's line, past a `;` or a trailing comment on that line. Null when another statement
    // shares the line or nothing but comments and the closing brace follow.
    private fun lineBreakAfter(guard: KtIfExpression): PsiWhiteSpace? {
        var sibling: PsiElement? = guard.nextSibling
        while (sibling != null) {
            when {
                sibling is PsiWhiteSpace && sibling.textContains('\n') -> break
                sibling is PsiWhiteSpace || sibling is PsiComment || sibling.node.elementType == KtTokens.SEMICOLON -> sibling = sibling.nextSibling
                else -> return null
            }
        }
        val ws = sibling as? PsiWhiteSpace ?: return null
        val next = generateSequence(ws.nextSibling) { it.nextSibling }.firstOrNull { it.isCode() } ?: return null
        if (next.node.elementType == KtTokens.RBRACE) return null
        return ws
    }
}
