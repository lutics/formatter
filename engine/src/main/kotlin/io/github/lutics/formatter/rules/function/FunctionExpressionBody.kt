package io.github.lutics.formatter.rules.function

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import io.github.lutics.formatter.rules.wrapcomma.childNodes
import io.github.lutics.formatter.rules.wrapcomma.isWhiteSpace
import io.github.lutics.formatter.rules.wrapcomma.nextSibling
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile

// A function body that only returns or throws one expression becomes `= expression`.
// The expression keeps its text as is; function-signature then joins it to the `=` line.
object FunctionExpressionBody : Rule {
    override val id = "function-expression-body"

    override fun edits(file: KtFile): List<Edit> {
        val pass = Pass()
        for (block in file.node.preOrder()) {
            if (pass.stopped) break
            if (block.elementType != KtNodeTypes.BLOCK || block.treeParent?.elementType != KtNodeTypes.FUN) continue
            val only = block.childNodes
                .filterNot { it.elementType == KtTokens.LBRACE || it.elementType == KtTokens.RBRACE || it.isWhiteSpace }
                .singleOrNull() ?: continue
            when (only.elementType) {
                KtNodeTypes.RETURN -> {
                    if (block.leaves.count { it.elementType == KtTokens.RETURN_KEYWORD } > 1) continue
                    val keyword = only.findChildByType(KtTokens.RETURN_KEYWORD) ?: continue
                    val value = keyword.nextSibling { !it.isWhiteSpace } ?: continue
                    // Only the node after `return` moves, which drops the value of a labeled return; never seen
                    // in compiling code, so it is left alone here
                    if (value.elementType == KtNodeTypes.LABEL_QUALIFIER) continue
                    pass.offer(block.start, block.end, listOf(Edit(block.start, block.end, "= " + value.text)))
                }
                KtNodeTypes.THROW -> {
                    val fn = block.treeParent
                    val from = block.treePrev?.takeIf { it.isWhiteSpace }?.start ?: block.start
                    // without a declared type the function would no longer compile; any type reference counts
                    val type = if (fn.findChildByType(KtNodeTypes.TYPE_REFERENCE) == null) ": Unit" else ""
                    pass.offer(from, block.end, listOf(Edit(from, block.end, "$type = " + only.text)))
                }
            }
        }
        return pass.edits
    }
}
