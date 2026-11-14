package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtFile

// `a || b && c` becomes `a || (b && c)`, and `a && b || c` becomes `(a && b) || c`: the parentheses spell out the
// grouping Kotlin already applies. `&&` binds tighter than `||` and no operator binds between them, so the only mixed
// shape is an `&&` that is an operand of an `||` without parentheses, and wrapping exactly that operand changes no
// grouping, no evaluation order and no short-circuit. One edit per outermost such operand, with the parentheses of the
// ones nested inside it (inside a parenthesized operand, a call or a lambda) written into the same text, so every level
// is wrapped at once, a multi-line operand keeps its line breaks, and check reports each outermost group once.
object MixedConditionOperators : Rule {
    override val id = "mixed-condition-operators"

    override fun edits(file: KtFile): List<Edit> {
        val groups = PsiTreeUtil.findChildrenOfType(file, KtBinaryExpression::class.java)
            .filter { it.operationToken == KtTokens.ANDAND && (it.parent as? KtBinaryExpression)?.operationToken == KtTokens.OROR }
            .map { it.textRange }
        val text = file.text
        return groups.filter { group -> groups.none { it != group && it.contains(group) } }.map { outer ->
            // a nested group starts after a bracket or keyword of the outer one, so marks that share an offset all close
            // (`z || x && if (c) a else b || d && e` ends both groups at `e`) and their order does not matter
            val marks = groups.filter { outer.contains(it) }.flatMap { listOf(it.startOffset to "(", it.endOffset to ")") }.sortedBy { it.first }
            val out = StringBuilder()
            var at = outer.startOffset
            for ((offset, mark) in marks) {
                out.append(text, at, offset).append(mark)
                at = offset
            }
            out.append(text, at, outer.endOffset)
            Edit(outer.startOffset, outer.endOffset, out.toString())
        }
    }
}
