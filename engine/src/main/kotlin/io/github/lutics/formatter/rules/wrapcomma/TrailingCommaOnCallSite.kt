package io.github.lutics.formatter.rules.wrapcomma

import org.jetbrains.kotlin.KtNodeTypes.COLLECTION_LITERAL_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.FUNCTION_LITERAL
import org.jetbrains.kotlin.KtNodeTypes.INDICES
import org.jetbrains.kotlin.KtNodeTypes.TYPE_ARGUMENT_LIST
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT_LIST
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.lexer.KtTokens.COMMA
import org.jetbrains.kotlin.lexer.KtTokens.GT
import org.jetbrains.kotlin.lexer.KtTokens.RBRACKET
import org.jetbrains.kotlin.lexer.KtTokens.RPAR

// Trailing commas at call sites: a multi-line
// argument list, type argument list, index list or collection literal ends in a comma, a single-line one does not.
// An argument list counts as multi-line only when a line break follows its first argument.
object TrailingCommaOnCallSite : TreeRule() {
    override val id = "trailing-comma-on-call-site"

    override fun beforeVisit(node: ASTNode) {
        val closing = when (node.elementType) {
            COLLECTION_LITERAL_EXPRESSION, INDICES -> node.childNodes.last { it.elementType == RBRACKET }
            TYPE_ARGUMENT_LIST -> node.childNodes.first { it.elementType == GT }
            VALUE_ARGUMENT_LIST ->
                if (node.parent?.elementType == FUNCTION_LITERAL) null else node.childNodes.lastOrNull { it.elementType == RPAR }
            else -> null
        } ?: return
        val trailingComma = closing.prevCodeLeaf?.takeIf { it.elementType == COMMA }
        when {
            !node.isMultiline() -> trailingComma?.remove()
            trailingComma == null ->
                // right after the last entry, ahead of any comment or line break
                closing.prevCodeSibling?.treeNext?.let { before -> before.parent?.addChild(LeafPsiElement(COMMA, ","), before) }
        }
    }

    private fun ASTNode.isMultiline(): Boolean =
        if (elementType == VALUE_ARGUMENT_LIST) {
            findChildByType(VALUE_ARGUMENT)?.nextSibling { it.isWhiteSpaceWithNewline } != null
        } else {
            textContains('\n')
        }
}
