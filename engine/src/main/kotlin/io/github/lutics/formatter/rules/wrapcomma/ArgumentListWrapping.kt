package io.github.lutics.formatter.rules.wrapcomma

import org.jetbrains.kotlin.KtNodeTypes.BINARY_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.COLLECTION_LITERAL_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.DOT_QUALIFIED_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.FUNCTION_LITERAL
import org.jetbrains.kotlin.KtNodeTypes.OPERATION_REFERENCE
import org.jetbrains.kotlin.KtNodeTypes.TYPE_ARGUMENT_LIST
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT_LIST
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import org.jetbrains.kotlin.com.intellij.psi.tree.TokenSet
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.lexer.KtTokens.EQ
import org.jetbrains.kotlin.lexer.KtTokens.LPAR
import org.jetbrains.kotlin.lexer.KtTokens.RPAR

// When an argument list spans lines outside its lambdas, every argument and the
// `)` start lines of their own, one indent deeper than the line of `(` (the `)` at that line's indent), and `(` stays on
// the line of the callee. Lists of more than 8 arguments are left alone (the android_studio default).
object ArgumentListWrapping : TreeRule() {
    override val id = "argument-list-wrapping"

    private const val MAX_ARGUMENTS = 8

    private val controlFlowKeywords = TokenSet.create(
        KtTokens.IF_KEYWORD,
        KtTokens.WHEN_KEYWORD,
        KtTokens.TRY_KEYWORD,
        KtTokens.OBJECT_KEYWORD,
        KtTokens.FOR_KEYWORD,
        KtTokens.WHILE_KEYWORD,
        KtTokens.DO_KEYWORD,
    )

    override fun beforeVisit(node: ASTNode) {
        if (node.elementType == VALUE_ARGUMENT_LIST && needToWrap(node)) node.childNodes.forEach { wrap(it) }
    }

    private fun needToWrap(node: ASTNode) =
        node.firstChildNode?.treeNext?.elementType != RPAR &&
            node.parent?.elementType != FUNCTION_LITERAL &&
            node.childNodes.count { it.elementType == VALUE_ARGUMENT } <= MAX_ARGUMENTS &&
            node.textContainsIgnoringLambda()

    private fun wrap(child: ASTNode) {
        when (child.elementType) {
            LPAR -> child.prevLeaf?.takeIf { it.isWhiteSpaceWithNewline }?.psi?.delete()
            VALUE_ARGUMENT, RPAR -> {
                val prevLeaf = child.prevWhiteSpaceWithNewLine() ?: child.prevLeaf
                when {
                    // already wrapped: its indent is the indent rule's business
                    prevLeaf.isWhiteSpaceWithNewline -> return
                    prevLeaf.isWhiteSpace -> prevLeaf!!.replaceTextWith(intendedIndent(child))
                    else -> child.parent?.addChild(PsiWhiteSpaceImpl(intendedIndent(child)), child)
                }
            }
        }
    }

    private fun intendedIndent(child: ASTNode): String {
        val list = child.parent!!
        var level = when {
            // IntelliJ's quirks: `generic<\n T>(\n 1\n)` and `foo\n .bar = Baz(\n 1\n)` keep the arguments at the line's indent
            list.hasTypeArgumentListInFront() -> -1
            list.isPartOfDotQualifiedAssignmentExpression() -> -1
            else -> 0
        }
        if (list.isOnSameLineAsControlFlowKeyword()) level++
        if (child.elementType == VALUE_ARGUMENT) level++
        level += indentLevelFrom(list.indentWithoutNewlinePrefix)
        return "\n" + INDENT.repeat(maxOf(level, 0))
    }

    // a line break inside a lambda argument does not count, one in a collection literal or directly in an argument does
    private fun ASTNode.textContainsIgnoringLambda(): Boolean =
        childNodes.any { child ->
            (child.isWhiteSpace && child.textContains('\n')) ||
                (child.elementType == COLLECTION_LITERAL_EXPRESSION && child.textContains('\n')) ||
                (child.elementType == VALUE_ARGUMENT && child.childNodes.any { it.textContainsIgnoringLambda() })
        }

    private fun ASTNode.hasTypeArgumentListInFront(): Boolean =
        parent?.findChildByType(TYPE_ARGUMENT_LIST)?.childNodes?.any { it.isWhiteSpaceWithNewline } == true

    private fun ASTNode.isPartOfDotQualifiedAssignmentExpression(): Boolean =
        parent?.parent
            ?.takeIf { it.elementType == BINARY_EXPRESSION }
            ?.let {
                it.firstChildNode.elementType == DOT_QUALIFIED_EXPRESSION &&
                    it.findChildByType(OPERATION_REFERENCE)?.firstChildNode?.elementType == EQ
            } ?: false

    private fun ASTNode.prevWhiteSpaceWithNewLine(): ASTNode? =
        generateSequence(prevLeaf) { it.prevLeaf }.takeWhile { it.isWhiteSpace || it.isPartOfComment }.firstOrNull { it.isWhiteSpaceWithNewline }

    private fun ASTNode.isOnSameLineAsControlFlowKeyword(): Boolean =
        generateSequence(prevLeaf) { it.prevLeaf }.takeWhile { !it.isWhiteSpaceWithNewline }.any { it.elementType in controlFlowKeywords }
}
