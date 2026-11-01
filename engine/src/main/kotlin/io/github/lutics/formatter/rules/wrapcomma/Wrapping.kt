package io.github.lutics.formatter.rules.wrapcomma

import org.jetbrains.kotlin.KtNodeTypes.ANNOTATION
import org.jetbrains.kotlin.KtNodeTypes.BLOCK
import org.jetbrains.kotlin.KtNodeTypes.CALL_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.CONDITION
import org.jetbrains.kotlin.KtNodeTypes.DESTRUCTURING_DECLARATION
import org.jetbrains.kotlin.KtNodeTypes.FOR
import org.jetbrains.kotlin.KtNodeTypes.FUN
import org.jetbrains.kotlin.KtNodeTypes.FUNCTION_LITERAL
import org.jetbrains.kotlin.KtNodeTypes.LAMBDA_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.OBJECT_LITERAL
import org.jetbrains.kotlin.KtNodeTypes.STRING_TEMPLATE
import org.jetbrains.kotlin.KtNodeTypes.SUPER_TYPE_CALL_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.SUPER_TYPE_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.SUPER_TYPE_LIST
import org.jetbrains.kotlin.KtNodeTypes.TYPE_ARGUMENT_LIST
import org.jetbrains.kotlin.KtNodeTypes.TYPE_PARAMETER
import org.jetbrains.kotlin.KtNodeTypes.TYPE_PARAMETER_LIST
import org.jetbrains.kotlin.KtNodeTypes.TYPE_PROJECTION
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT_LIST
import org.jetbrains.kotlin.KtNodeTypes.VALUE_PARAMETER
import org.jetbrains.kotlin.KtNodeTypes.VALUE_PARAMETER_LIST
import org.jetbrains.kotlin.KtNodeTypes.WHEN_ENTRY
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.lexer.KtTokens.ARROW
import org.jetbrains.kotlin.lexer.KtTokens.CLOSING_QUOTE
import org.jetbrains.kotlin.lexer.KtTokens.COMMA
import org.jetbrains.kotlin.lexer.KtTokens.DOT
import org.jetbrains.kotlin.lexer.KtTokens.EOL_COMMENT
import org.jetbrains.kotlin.lexer.KtTokens.GT
import org.jetbrains.kotlin.lexer.KtTokens.LBRACE
import org.jetbrains.kotlin.lexer.KtTokens.LBRACKET
import org.jetbrains.kotlin.lexer.KtTokens.LPAR
import org.jetbrains.kotlin.lexer.KtTokens.LT
import org.jetbrains.kotlin.lexer.KtTokens.RBRACE
import org.jetbrains.kotlin.lexer.KtTokens.RBRACKET
import org.jetbrains.kotlin.lexer.KtTokens.RPAR
import org.jetbrains.kotlin.psi.KtSuperTypeList
import org.jetbrains.kotlin.psi.psiUtil.leaves

// A construct that spans lines puts its brackets, list entries, lambda arrow and closing raw
// quote on lines of their own. A line break it inserts takes the indent of the line it is on plus one indent; the indent
// rule, not this one, settles the final indent of every line. max_line_length is off, so nothing wraps for length.
object Wrapping : TreeRule() {
    override val id = "wrapping"

    private val matching = mapOf(LPAR to RPAR, LBRACE to RBRACE, LBRACKET to RBRACKET, LT to GT)

    override fun beforeVisit(node: ASTNode) {
        when (node.elementType) {
            BLOCK -> beforeVisitBlock(node)
            LPAR, LBRACKET -> rearrangeBlock(node)
            SUPER_TYPE_LIST -> rearrangeSuperTypeList(node)
            VALUE_PARAMETER_LIST, VALUE_ARGUMENT_LIST -> rearrangeValueList(node)
            TYPE_ARGUMENT_LIST, TYPE_PARAMETER_LIST -> rearrangeTypeArgumentList(node)
            ARROW -> rearrangeArrow(node)
            CLOSING_QUOTE -> rearrangeClosingQuote(node)
        }
    }

    override fun afterVisit(node: ASTNode) {
        if (node.elementType != BLOCK) return
        val lbrace = node.startOfBlock()?.takeIf { it.elementType == LBRACE } ?: return
        val rbrace = node.endOfBlock()?.takeIf { it.elementType == RBRACE && !it.prevLeaf.isWhiteSpaceWithNewline } ?: return
        if (hasNewLineInClosedRange(lbrace, rbrace)) rbrace.upsertWhitespaceBeforeMe(node.parent!!.indent)
    }

    private fun beforeVisitBlock(node: ASTNode) {
        val lbrace = node.startOfBlock()?.takeIf { it.elementType == LBRACE } ?: return
        if (lbrace.nextLeaf.isWhiteSpaceWithNewline ||
            lbrace.followedByEolComment() ||
            lbrace.followedByFunctionLiteralParameterList() ||
            lbrace.isPartOf(LONG_STRING_TEMPLATE_ENTRY)
        ) {
            return
        }
        if (node.firstChildLeafOrSelf.elementType == EOL_COMMENT) return
        val rbrace = node.endOfBlock()?.takeIf { it.elementType == RBRACE } ?: return
        if (hasNewLineInClosedRange(lbrace, rbrace)) newlineAfter(lbrace)
    }

    private fun ASTNode.followedByEolComment() =
        leaves().takeWhile { it.isWhiteSpaceWithoutNewline || it.elementType == EOL_COMMENT }.any { it.elementType == EOL_COMMENT }

    private fun ASTNode.followedByFunctionLiteralParameterList() =
        parent?.elementType == FUNCTION_LITERAL && nextCodeSibling?.elementType == VALUE_PARAMETER_LIST

    // ( and [ of a list that spans lines go at the end of their line, the closing one at the start of its own
    private fun rearrangeBlock(node: ASTNode) {
        val closingType = matching[node.elementType]
        var newlineInBetween = false
        var numberOfArgs = 0
        var arg: ASTNode? = null
        val closing = node.nextSibling {
            val isValueArgument = it.elementType == VALUE_ARGUMENT
            newlineInBetween = newlineInBetween || if (isValueArgument) it.hasLineBreak(LAMBDA_EXPRESSION, FUN) else it.hasLineBreak()
            if (isValueArgument) {
                numberOfArgs++
                arg = it
            }
            it.elementType == closingType
        } ?: return
        if (!newlineInBetween ||
            // keep foo({ ... }) and foo(object : C { ... })
            (numberOfArgs == 1 && arg?.firstChildNode?.elementType.let { it == OBJECT_LITERAL || it == LAMBDA_EXPRESSION })
        ) {
            return
        }
        if (node.isPartOfForLoopConditionWithMultilineExpression()) return
        val beforeNextCode = node.nextCodeLeaf?.prevLeaf { !it.isPartOfComment && !it.isWhiteSpaceWithoutNewline && it.textLength > 0 }
        // IntelliJ keeps `if (a &&` on the keyword's line
        if (!beforeNextCode.isWhiteSpaceWithNewline && node.treeNext?.elementType != CONDITION) newlineAfter(node)
        if (!closing.prevLeaf.isWhiteSpaceWithNewline) closing.upsertWhitespaceBeforeMe(node.parent!!.indent)
    }

    private fun rearrangeSuperTypeList(node: ASTNode) {
        val entries = (node.psi as KtSuperTypeList).entries.map { it.node }
        if (!node.textContains('\n') || entries.size <= 1) return
        // keep `class A : B, C, D({ ... })`
        if (entries.dropLast(1).all { it.elementType == SUPER_TYPE_ENTRY } && entries.last().elementType == SUPER_TYPE_CALL_ENTRY) return
        if (!node.prevLeaf.isWhiteSpaceWithNewline) {
            val colon = node.prevCodeLeaf!!
            if (!colon.prevLeaf.isWhiteSpaceWithNewline &&
                colon.prevCodeLeaf.let { it?.elementType != RPAR || !it.prevLeaf.isWhiteSpaceWithNewline }
            ) {
                newlineAfter(colon)
            }
        }
        for (c in node.childNodes) {
            if (c.elementType == COMMA &&
                !c.treeNext.isWhiteSpaceWithNewline &&
                c.nextLeaf { !it.isWhiteSpaceWithoutNewline }?.isPartOfComment != true
            ) {
                newlineAfter(c, node.indent)
            }
        }
    }

    // a multi-line entry of a parameter or argument list goes on lines of its own
    private fun rearrangeValueList(node: ASTNode) {
        for (c in node.childNodes) {
            val hasLineBreak = when (c.elementType) {
                VALUE_ARGUMENT -> c.hasLineBreak(LAMBDA_EXPRESSION, FUN)
                VALUE_PARAMETER, ANNOTATION -> c.hasLineBreak()
                else -> false
            }
            if (!hasLineBreak) continue
            val prev = c.prevSibling { !it.isWhiteSpace }
            if (prev?.elementType == COMMA && !prev.treeNext.isWhiteSpaceWithNewline) newlineAfter(prev)
            val next = c.nextSibling { !it.isWhiteSpace }
            val destructuringLast = c.elementType == VALUE_PARAMETER &&
                c.nextSibling { it.elementType == VALUE_PARAMETER } == null &&
                c.firstChildNode.elementType == DESTRUCTURING_DECLARATION
            if (next?.elementType == COMMA &&
                !destructuringLast &&
                !next.treeNext.isWhiteSpaceWithNewline &&
                // keep `value(\n), // a comment`
                next.treeNext?.treeNext?.let { it.elementType !in KtTokens.COMMENTS } != false
            ) {
                newlineAfter(next)
            }
        }
    }

    private fun rearrangeTypeArgumentList(node: ASTNode) {
        if (!node.textContains('\n')) return
        node.childNodes
            .filter { it.elementType == TYPE_PROJECTION || it.elementType == TYPE_PARAMETER }
            .forEach { projection ->
                val prev = projection.prevSibling { !it.isPartOfComment }
                if (prev?.elementType == LT || prev.isWhiteSpaceWithoutNewline) {
                    projection.upsertWhitespaceBeforeMe(node.parent!!.indent + INDENT)
                }
            }
        node.findChildByType(GT)?.let { closingAngle ->
            if (!closingAngle.prevSibling { !it.isPartOfComment }.isWhiteSpaceWithNewline) {
                closingAngle.upsertWhitespaceBeforeMe(node.parent!!.indent + INDENT)
            }
        }
    }

    // `"""` closing a multi-line raw string that is trimmed starts its own line
    private fun rearrangeClosingQuote(node: ASTNode) {
        val template = node.parent?.takeIf { it.elementType == STRING_TEMPLATE } ?: return
        if (template.childNodes.none { it.elementType == LITERAL_STRING_TEMPLATE_ENTRY && it.text == "\n" }) return
        val call = template.nextSibling { it.elementType != DOT }
        if (call?.elementType != CALL_EXPRESSION || (call.text != "trimIndent()" && call.text != "trimMargin()")) return
        if (node.treePrev?.text.isNullOrBlank()) return
        (node as LeafPsiElement).rawInsertBeforeMe(LeafPsiElement(LITERAL_STRING_TEMPLATE_ENTRY, "\n"))
    }

    private fun mustBeFollowedByNewline(node: ASTNode): Boolean {
        val nextCodeSibling = node.nextCodeSibling
        var lToken = nextCodeSibling?.nextLeaf { it.isWhiteSpaceWithNewline }?.prevCodeLeaf
        if (lToken != null && lToken.elementType !in matching) {
            // x = y.f({ z ->
            // })
            lToken = lToken.prevLeaf { it.elementType in matching || it == node }
        }
        if (lToken != null && lToken.elementType in matching) {
            val rType = matching[lToken.elementType]
            return lToken.nextSibling { it.elementType == rType }?.parent == lToken.parent
        }
        return nextCodeSibling?.textContains('\n') == false
    }

    // the body after `->` of a multi-line lambda or when entry starts on its own line
    private fun rearrangeArrow(node: ASTNode) {
        val parent = node.parent!!
        if (parent.elementType.let { it != FUNCTION_LITERAL && it != WHEN_ENTRY } ||
            !parent.textContains('\n') ||
            (parent.elementType == WHEN_ENTRY && mustBeFollowedByNewline(node)) ||
            // keep a when condition list whose `->` starts a line
            (parent.elementType == WHEN_ENTRY && node.prevLeaf.isWhiteSpaceWithNewline)
        ) {
            return
        }
        if (!node.nextCodeLeaf?.prevLeaf.isWhiteSpaceWithNewline) newlineAfter(node)
        val rbrace = node.nextSibling { it.elementType == RBRACE } ?: return
        if (!rbrace.prevLeaf.isWhiteSpaceWithNewline) rbrace.upsertWhitespaceBeforeMe(node.indent)
    }

    private fun newlineAfter(node: ASTNode, indent: String = node.indent + INDENT) = node.upsertWhitespaceAfterMe(indent)

    private fun ASTNode.hasLineBreak(vararg ignore: IElementType): Boolean =
        when {
            isWhiteSpaceWithNewline -> true
            ignore.isEmpty() -> textContains('\n')
            else -> elementType !in ignore && childNodes.any { it.textContains('\n') && it.elementType !in ignore }
        }

    // keep `for (foo in listOf(\n...\n)) {` when only the iterated expression spans lines
    private fun ASTNode.isPartOfForLoopConditionWithMultilineExpression(): Boolean =
        parent?.elementType == FOR &&
            generateSequence(this) { it.treeNext }.takeWhile { it.elementType != RPAR }.none { it.isWhiteSpaceWithNewline }

    // a lambda's braces are its function literal's; other blocks hold their own braces or sit between them
    private fun ASTNode.startOfBlock(): ASTNode? =
        if (parent?.elementType == FUNCTION_LITERAL) {
            parent!!.findChildByType(LBRACE)
        } else {
            firstChildLeafOrSelf.let { if (it.elementType == LBRACE) it else it.prevCodeSibling }
        }

    private fun ASTNode.endOfBlock(): ASTNode? =
        if (parent?.elementType == FUNCTION_LITERAL) {
            parent!!.findChildByType(RBRACE)
        } else {
            lastChildLeafOrSelf.let { if (it.elementType == RBRACE) it else it.nextCodeSibling }
        }
}
