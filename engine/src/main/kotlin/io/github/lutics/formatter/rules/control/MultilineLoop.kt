package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtFile

// The body of a loop spanning several lines gets braces; a one-line loop keeps its bare statement.
object MultilineLoop : Rule {
    override val id = "multiline-loop"

    private val stops = setOf(KtTokens.RPAR, KtTokens.DO_KEYWORD)

    override fun edits(file: KtFile): List<Edit> = batchWraps(
        PsiTreeUtil.findChildrenOfType(file, KtContainerNodeForControlStructureBody::class.java).asSequence()
            .filter { it.type == KtNodeTypes.BODY }
            .map { body ->
                val first = body.firstChild
                body.takeIf { first != null && first.type != KtNodeTypes.BLOCK && it.parent.textContains('\n') }
                    ?.let { wrapInBlock(it, it.parent, stops, fixSpaceAfter = true) }
            },
    )
}
