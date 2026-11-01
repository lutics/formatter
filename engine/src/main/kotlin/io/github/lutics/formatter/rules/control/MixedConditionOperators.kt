package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtFile

// `a && b || c` is reported, never rewritten: only the author knows which grouping was meant.
object MixedConditionOperators : Rule {
    override val id = "mixed-condition-operators"

    override fun findings(file: KtFile): List<Finding> =
        PsiTreeUtil.findChildrenOfType(file, KtBinaryExpression::class.java)
            .filter { it.logicalOperator() != null && it.isPartOfMixedExpression() }
            .mapNotNull { node ->
                // the outermost binary expression above this one, which may enclose it through parentheses
                node.ancestors().firstOrNull { it.type == KtNodeTypes.BINARY_EXPRESSION && it.parent?.type != KtNodeTypes.BINARY_EXPRESSION }
            }
            .map { Finding(it.textRange.startOffset, "A condition with mixed usage of '&&' and '||' is hard to read. Use parenthesis to clarify the (sub)condition.") }

    private fun PsiElement.logicalOperator() =
        (this as? KtBinaryExpression)?.operationReference?.firstChild?.type?.takeIf { it == KtTokens.OROR || it == KtTokens.ANDAND }

    private fun PsiElement.isPartOfMixedExpression(): Boolean {
        val first = logicalOperator() ?: return false
        return generateSequence(this) { it.parent }
            .takeWhile { it.type == KtNodeTypes.BINARY_EXPRESSION }
            .any { parent -> parent.logicalOperator()?.let { it != first } ?: false }
    }
}
