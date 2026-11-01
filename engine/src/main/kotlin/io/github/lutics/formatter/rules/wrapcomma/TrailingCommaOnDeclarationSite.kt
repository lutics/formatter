package io.github.lutics.formatter.rules.wrapcomma

import org.jetbrains.kotlin.KtNodeTypes.CLASS
import org.jetbrains.kotlin.KtNodeTypes.CLASS_BODY
import org.jetbrains.kotlin.KtNodeTypes.DESTRUCTURING_DECLARATION
import org.jetbrains.kotlin.KtNodeTypes.ENUM_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.FUNCTION_LITERAL
import org.jetbrains.kotlin.KtNodeTypes.TYPE_PARAMETER_LIST
import org.jetbrains.kotlin.KtNodeTypes.VALUE_PARAMETER
import org.jetbrains.kotlin.KtNodeTypes.VALUE_PARAMETER_LIST
import org.jetbrains.kotlin.KtNodeTypes.WHEN_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.WHEN_ENTRY_GUARD
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import org.jetbrains.kotlin.lexer.KtTokens.ARROW
import org.jetbrains.kotlin.lexer.KtTokens.COMMA
import org.jetbrains.kotlin.lexer.KtTokens.ENUM_KEYWORD
import org.jetbrains.kotlin.lexer.KtTokens.GT
import org.jetbrains.kotlin.lexer.KtTokens.LPAR
import org.jetbrains.kotlin.lexer.KtTokens.RPAR
import org.jetbrains.kotlin.lexer.KtTokens.SEMICOLON
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

// Trailing commas in declarations: a multi-line parameter
// list, type parameter list, destructuring declaration, lambda parameter list, when-entry condition list (of a when with
// a subject) and enum entry list ends in a comma, a single-line one does not. A when entry or lambda that gains the comma
// also gets its `->` on a line of its own.
object TrailingCommaOnDeclarationSite : TreeRule() {
    override val id = "trailing-comma-on-declaration-site"

    override fun beforeVisit(node: ASTNode) {
        when (node.elementType) {
            CLASS -> visitClass(node)
            DESTRUCTURING_DECLARATION -> node.correctTrailingCommaBefore(node.childNodes.last { it.elementType == RPAR })
            FUNCTION_LITERAL -> node.childNodes.lastOrNull { it.elementType == ARROW }?.let { node.correctTrailingCommaBefore(it) }
            TYPE_PARAMETER_LIST -> node.correctTrailingCommaBefore(node.childNodes.first { it.elementType == GT })
            VALUE_PARAMETER_LIST ->
                if (node.parent?.elementType != FUNCTION_LITERAL) {
                    node.childNodes.lastOrNull { it.elementType == RPAR }?.let { node.correctTrailingCommaBefore(it) }
                }
            WHEN_ENTRY -> visitWhenEntry(node)
        }
    }

    private fun visitWhenEntry(node: ASTNode) {
        val entry = node.psi as KtWhenEntry
        // no comma after `else`, nor in a when without a subject
        if (entry.isElse || (entry.parent as KtWhenExpression).leftParenthesis == null) return
        node.correctTrailingCommaBefore(node.childNodes.first { it.elementType == ARROW })
    }

    private fun visitClass(node: ASTNode) {
        if (!node.hasModifier(ENUM_KEYWORD)) return
        val body = node.findChildByType(CLASS_BODY) ?: return
        val entries = body.childNodes.filter { it.elementType == ENUM_ENTRY }.toList()
        if (entries.isEmpty()) return
        // the `;` that ends the entries, else the `}`
        val after = entries.last().childNodes.singleOrNull { it.elementType == SEMICOLON } ?: body.lastChildNode ?: return
        // keep `A, B }` when the last two entries share a line
        if (entries.size >= 2 && !hasNewLineInClosedRange(entries[entries.size - 2], entries.last())) return
        node.correctTrailingCommaBefore(after)
    }

    private fun ASTNode.correctTrailingCommaBefore(inspectNode: ASTNode) {
        val trailingComma = inspectNode.prevCodeLeaf?.takeIf { it.elementType == COMMA }
        when {
            // the compiler allows no comma in a when entry with a guard
            elementType == WHEN_ENTRY && childNodes.any { it.elementType == WHEN_ENTRY_GUARD } -> Unit
            !isMultiline() -> trailingComma?.remove()
            trailingComma != null -> {
                val beforeArrow = inspectNode.parent?.takeIf { it.elementType == WHEN_ENTRY }?.findChildByType(ARROW)?.prevLeaf
                if (beforeArrow != null && !beforeArrow.isWhiteSpaceWithNewline) beforeArrow.upsertWhitespaceAfterMe(inspectNode.parent!!.indent)
            }
            else -> addTrailingComma(inspectNode)
        }
    }

    private fun ASTNode.addTrailingComma(inspectNode: ASTNode) {
        val beforeArrow = takeIf { it.elementType == WHEN_ENTRY || it.elementType == FUNCTION_LITERAL }?.findChildByType(ARROW)?.prevLeaf
        val prevNode = inspectNode.prevCodeLeaf!!
        if (beforeArrow != null && !beforeArrow.isWhiteSpaceWithNewline) {
            val indent = prevNode.parent!!.indent
            if (beforeArrow.isWhiteSpace) {
                beforeArrow.replaceTextWith(indent)
            } else {
                inspectNode.prevCodeLeaf?.nextLeaf?.let { it.parent?.addChild(PsiWhiteSpaceImpl(indent), it) }
            }
        }
        if (inspectNode.parent?.elementType == ENUM_ENTRY) {
            // `A;` becomes `A,` with the `;` on the next line
            val parentIndent = prevNode.parent?.prevLeaf?.takeIf { it.isWhiteSpace }?.text ?: prevNode.indent
            inspectNode.parent!!.apply {
                addChild(LeafPsiElement(COMMA, ","), inspectNode)
                addChild(PsiWhiteSpaceImpl(parentIndent), null)
                addChild(LeafPsiElement(SEMICOLON, ";"), null)
            }
            inspectNode.remove()
        } else {
            inspectNode.prevCodeLeaf?.nextLeaf?.let { it.parent?.addChild(LeafPsiElement(COMMA, ","), it) }
        }
    }

    private fun ASTNode.isMultiline(): Boolean =
        when {
            parent?.elementType == FUNCTION_LITERAL -> parent!!.isMultiline()
            elementType == FUNCTION_LITERAL -> hasNewLineInClosedRange(findChildByType(VALUE_PARAMETER_LIST)!!, findChildByType(ARROW)!!)
            elementType == WHEN_ENTRY -> hasNewLineInClosedRange(firstChildNode, findChildByType(ARROW)!!)
            elementType == DESTRUCTURING_DECLARATION -> hasNewLineInClosedRange(findChildByType(LPAR)!!, findChildByType(RPAR)!!)
            elementType == VALUE_PARAMETER_LIST && findChildByType(VALUE_PARAMETER) == null -> false
            else -> textContains('\n')
        }
}
