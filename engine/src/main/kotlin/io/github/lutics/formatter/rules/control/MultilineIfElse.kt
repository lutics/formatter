package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtFile

// A branch of an if that is not a block gets braces unless the whole if is a simple one-liner.
object MultilineIfElse : Rule {
    override val id = "multiline-if-else"

    private val stops = setOf(KtTokens.RPAR, KtTokens.ELSE_KEYWORD)

    override fun edits(file: KtFile): List<Edit> = batchWraps(
        PsiTreeUtil.findChildrenOfType(file, KtContainerNodeForControlStructureBody::class.java).asSequence()
            .filter { it.type == KtNodeTypes.THEN || it.type == KtNodeTypes.ELSE }
            .map { branch -> branch.takeIf(::needsBraces)?.let { wrapInBlock(it, it.parent, stops, it.type == KtNodeTypes.THEN) } },
    )

    private fun needsBraces(node: PsiElement): Boolean {
        val first = node.firstChild
        if (first?.type == KtNodeTypes.BLOCK) return false
        if (node.type == KtNodeTypes.ELSE && first != null) {
            // `if (a) {} else if (b) {} else {}` followed by an operator or a call stays as written
            val head = first.takeIf { it.type == KtNodeTypes.BINARY_EXPRESSION || it.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION }
            if (head?.firstChild?.type == KtNodeTypes.IF) return false
        }
        if (!node.prevSibling.textContains('\n')) {
            if (first == null) return false
            if (first.type == KtNodeTypes.IF) return false
            // a really simple if stays on one line, except a one-liner nested in an else
            if (!node.parent.textContains('\n') && node.parent.parent?.type != KtNodeTypes.ELSE) return false
        }
        return true
    }
}
