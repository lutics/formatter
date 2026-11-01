package io.github.lutics.formatter.rules.wrapcomma

import org.jetbrains.kotlin.KtNodeTypes.ANNOTATION_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.BLOCK
import org.jetbrains.kotlin.KtNodeTypes.CLASS
import org.jetbrains.kotlin.KtNodeTypes.CLASS_BODY
import org.jetbrains.kotlin.KtNodeTypes.ENUM_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.FUNCTION_LITERAL
import org.jetbrains.kotlin.KtNodeTypes.MODIFIER_LIST
import org.jetbrains.kotlin.KtNodeTypes.VALUE_PARAMETER_LIST
import org.jetbrains.kotlin.KtNodeTypes.WHEN
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.lexer.KtTokens.ARROW
import org.jetbrains.kotlin.lexer.KtTokens.ENUM_KEYWORD
import org.jetbrains.kotlin.lexer.KtTokens.LBRACE
import org.jetbrains.kotlin.lexer.KtTokens.RBRACE
import org.jetbrains.kotlin.lexer.KtTokens.SEMICOLON

// The statements of a block, class body or when start on the line after `{` (or
// after a lambda's `->`) and the `}` on a line of its own; a statement after `;` moves to the next line. Empty blocks,
// one-line lambdas with a single statement and one-line enum classes are left alone.
object StatementWrapping : TreeRule() {
    override val id = "statement-wrapping"

    override fun beforeVisit(node: ASTNode) {
        when (node.elementType) {
            // a lambda's braces sit outside its block
            BLOCK -> visitBlock(if (node.parent?.elementType == FUNCTION_LITERAL) node.parent!! else node)
            CLASS_BODY, WHEN -> visitBlock(node)
            SEMICOLON -> visitSemicolon(node)
        }
    }

    private fun visitBlock(node: ASTNode) {
        if (node.isBlockWithoutStatements() ||
            node.isFunctionLiteralWithSingleStatementOnSingleLine() ||
            node.parent!!.isEnumClassOnSingleLine()
        ) {
            return
        }
        val lbrace = node.findChildByType(LBRACE) ?: return
        // `foo { bar ->` keeps its parameters on the line of `{`
        val opening = if (node.elementType == FUNCTION_LITERAL && node.findChildByType(VALUE_PARAMETER_LIST) != null) {
            node.findChildByType(ARROW) ?: return
        } else {
            lbrace
        }
        val nextCodeLeaf = opening.nextCodeLeaf
        if (nextCodeLeaf != null && !hasNewLineInClosedRange(opening, nextCodeLeaf)) {
            val indent = if (node.elementType == WHEN) opening.indent + INDENT else opening.parent!!.indent + INDENT
            opening.upsertWhitespaceAfterMe(indent)
        }
        val rbrace = node.findChildByType(RBRACE) ?: return
        val prevCodeLeaf = rbrace.prevCodeLeaf
        if (prevCodeLeaf != null && !hasNewLineInClosedRange(prevCodeLeaf, rbrace)) rbrace.upsertWhitespaceBeforeMe(rbrace.parent!!.indent)
    }

    private fun ASTNode.isBlockWithoutStatements() = findChildByType(LBRACE)?.nextCodeLeaf?.elementType == RBRACE

    private fun ASTNode.isFunctionLiteralWithSingleStatementOnSingleLine() =
        elementType == FUNCTION_LITERAL &&
            !textContains('\n') &&
            findChildByType(BLOCK)?.childNodes?.count { it.elementType != VALUE_PARAMETER_LIST && it.elementType != ARROW }?.let { it <= 1 } == true

    private fun ASTNode.isEnumClassOnSingleLine(): Boolean {
        if (elementType != CLASS || !hasModifier(ENUM_KEYWORD)) return false
        // from the first token after the annotations above the class
        val first = findChildByType(MODIFIER_LIST)!!.childNodes
            .dropWhile { it.elementType == ANNOTATION_ENTRY || it.isWhiteSpace }
            .first()
            .firstChildLeafOrSelf
        return !hasNewLineInClosedRange(first, lastChildLeafOrSelf)
    }

    private fun visitSemicolon(node: ASTNode) {
        val previousCodeLeaf = node.prevCodeLeaf?.lastChildLeafOrSelf ?: return
        val nextCodeLeaf = node.nextCodeLeaf?.firstChildLeafOrSelf ?: return
        // keep `enum class A { ONE, TWO; }`
        if (previousCodeLeaf.parent?.elementType == ENUM_ENTRY && nextCodeLeaf.elementType == RBRACE) return
        if (!hasNewLineInClosedRange(previousCodeLeaf, nextCodeLeaf)) node.upsertWhitespaceAfterMe(previousCodeLeaf.indent)
    }
}
